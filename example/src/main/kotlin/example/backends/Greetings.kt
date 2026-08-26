package example.backends

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.LongCodec
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.UploadedFile
import io.github.matthewjones372.pelican.Webhook
import io.github.matthewjones372.pelican.bufferedFile
import io.github.matthewjones372.pelican.commaSeparated
import io.github.matthewjones372.pelican.cookieParam
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.filePart
import io.github.matthewjones372.pelican.formBody
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.ndjsonIn
import io.github.matthewjones372.pelican.nonEmpty
import io.github.matthewjones372.pelican.of
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.path
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.positive
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.rawBody
import io.github.matthewjones372.pelican.repeated
import io.github.matthewjones372.pelican.responseHeader
import io.github.matthewjones372.pelican.textPart
import io.github.matthewjones372.pelican.webhook
import kotlin.time.Duration.Companion.seconds

/**
 * One description, whichever server ends up serving it.
 *
 * This file imports `io.github.matthewjones372.pelican` and nothing else — no
 * server library, no JSON library. That is what makes `OnPekko.kt` possible: it
 * binds *these same values* to handlers, and neither the endpoints below nor
 * the OpenAPI document generated from them knows which server it was.
 */

data class Greeting(val greeting: String, val language: String)

data class Tick(val seq: Int, val at: String)

data class Note(val text: String)

data class Tally(val notes: Int)

data class Echoed(val text: String, val trace: String?)

data class Preferences(val locale: String, val session: String?)

data class SignIn(val user: String, val remember: Boolean, val visits: Int)

data class Session(val user: String, val remember: Boolean, val visits: Int)

data class Uploaded(
    val caption: String,
    val filename: String?,
    val contentType: String?,
    val content: String,
    val notes: String,
)

data class Filters(val tags: List<String>, val ids: List<Long>, val features: List<String>, val seen: List<String>)

val name = pathParam<String>("name", description = "Who to greet")
val from = pathParam<Int>("from", description = "Where the countdown starts")
val shout = queryParam<Boolean>("shout", description = "Upper-case the greeting").default(false)
val traceId = headerParam<String>("X-Trace-Id", description = "Carried through to the answer").optional()
val note = jsonBody<Note>(description = "Anything worth saying twice")

/**
 * Two cookies, and the whole point of them being *parameters*: a cookie the
 * caller may not have sent, and one with a default the server supplies. Neither
 * is a credential, so neither is a security scheme — and both are decoded and
 * documented like any other input.
 */
val locale = cookieParam<String>("locale", description = "Which language to answer in").default("en")
val session = cookieParam<String>("session", description = "An opaque session id").optional()

/**
 * Four inputs carrying several values each, one per encoding OpenAPI can
 * describe — and the point of them being here is that the interpreters
 * below read all four without any of them saying so.
 */
val tags = queryParam<String>("tag", description = "Only entries with these tags").repeated().optional()
val ids = queryParam("id", LongCodec.positive(), description = "Only these ids").commaSeparated().optional()
val features = headerParam<String>("X-Feature", description = "Feature flags the caller has on")
    .commaSeparated()
    .optional()
val seenBefore = cookieParam<String>("seen", description = "Entries this browser has already been shown")
    .repeated()
    .optional()

/**
 * The same sign-in, posted either way.
 */
val credentials = formBody<SignIn>(description = "The sign-in details, as a form or as JSON") or
    jsonBody<SignIn>()

/**
 * An upload: a text field and two files.
 */
val caption = textPart("caption", StringCodec.nonEmpty(), description = "What to call the file")
val notes = bufferedFile("notes", maxBytes = 512, contentType = "text/plain", description = "A short note")
val upload = filePart("file", contentType = "text/plain", description = "The file itself")

/**
 * A header every endpoint here sends back, declared once like any input.
 */
val requestId = responseHeader<String>("X-Request-Id", description = "Correlates this answer with the server's log")

/**
 * A header belonging to one *failure* rather than to the endpoint.
 */
val retryAfter = responseHeader<Long>("Retry-After", description = "Seconds to wait before saying it again")

/** The pair a 429 nearly always is: a body saying what happened, a header saying when to come back. */
val tooMuch = errorJson<ApiError>(429, "The caller is being asked to slow down", retryAfter)

/**
 * Where a newly remembered greeting lives.
 */
val greetingAt = responseHeader<String>("Location", "Where the remembered greeting lives")

/*
 * Two successful answers to one question, and the case a payload type cannot
 * settle: both carry a `Greeting`, so what tells them apart is which one the
 * handler named. Declared as values rather than inside the block below,
 * because a response a handler names has to be nameable — the same reason a
 * failure shared between endpoints is declared as a value.
 */

/** The service already knew this greeting; nothing was written. */
val alreadyKnown = json<Greeting>(status = 200)

/** It did not, and now does. The `Location` says where. */
val newlyLearned = json<Greeting>(status = 201, greetingAt)

/** A plain JSON response. */
val greet = endpoint(name, shout) {
    get("hello" / name)
    summary = "Greet someone"
    operationId = "greet"
    tag("greetings")
    emits(requestId)
    json<Greeting>()
}

/**
 * A streamed response, which is where a backend is visible at all: the
 * description says "a stream of Tick" and the binder demands the backend's own
 * stream type — a `Source` on Pekko. The wire format, newline-delimited JSON,
 * is rendered by core whichever type that is.
 */
val countdown = endpoint(from) {
    get("countdown" / from)
    summary = "Count down, one row every 100ms"
    operationId = "countdown"
    tag("greetings")
    emits(requestId)
    ndjson<Tick>()
}

/**
 * A POST, which is what makes this service interesting to a browser: a JSON
 * body and a declared header are both things a script has to be given
 * permission to send, and the permission is worked out from these two lines
 * rather than configured again beside them. See [greetingsApi].
 */
val echo = endpoint(traceId, note) {
    post("echo")
    summary = "Say something back"
    operationId = "echo"
    tag("greetings")
    emits(requestId)
    errorResponse(403, "Refused by the gate filter")
    errorResponse(413, "The body was larger than this service will read")
    json<Echoed>() orFail tooMuch
}

/**
 * The endpoint that answers two ways.
 */
val remember = endpoint(name, note) {
    put("greetings" / name)
    summary = "Remember a greeting, saying whether it was new"
    operationId = "remember"
    tag("greetings")
    emits(requestId)
    newlyLearned or alreadyKnown
}

/** What the caller's cookies say, read back as the types they were declared as. */
val preferences = endpoint(locale, session) {
    get("preferences")
    summary = "Read the caller's cookies"
    operationId = "preferences"
    tag("greetings")
    emits(requestId)
    json<Preferences>()
}

/**
 * A form body. `remember=on` is what a checkbox posts, and `visits=3` is three
 * characters — the schema published for [SignIn] is what says one is a boolean
 * and the other a number, and that is what both codec modules then agree on.
 */
val signIn = endpoint(credentials) {
    post("sign-in")
    summary = "Sign in from an HTML form"
    operationId = "signIn"
    tag("greetings")
    emits(requestId)
    json<Session>()
}

/**
 * A multipart upload: a text part, a small file held in memory, and a streamed
 * one after it.
 */
val uploadFile = endpoint(caption, notes, upload) {
    post("upload")
    servers("https://uploads.example.com")
    summary = "Upload a file with a caption"
    operationId = "uploadFile"
    tag("greetings")
    emits(requestId)
    json<Uploaded>()
}

/**
 * Every multi-valued input at once, read back as the lists they were declared
 * as. `?tag=a&tag=b`, `?id=1,2`, `X-Feature: a,b` and `Cookie: seen=a; seen=b`
 * all reach the handler as a `List`, and no handler splits a string.
 */
val filters = endpoint(tags, ids, features, seenBefore) {
    get("filters")
    summary = "Echo back the multi-valued inputs, decoded"
    operationId = "filters"
    tag("greetings")
    emits(requestId)
    json<Filters>()
}

/**
 * Three inputs nobody may leave out, which is what the rest of this file has
 * none of: every other query, header and cookie here is optional or defaulted,
 * so the 400 each interpreter raises for a missing one had nothing to raise it
 * for. One endpoint, so that refusal is asked of every backend.
 */
val requiredTerm = queryParam<String>("term", description = "What to look for")
val requiredKey = headerParam<String>("X-Key", description = "Who is asking")
val requiredJar = cookieParam<String>("jar", description = "A cookie with no default")

data class Strictly(val term: String, val key: String, val jar: String)

val strict = endpoint(requiredTerm, requiredKey, requiredJar) {
    get("strict")
    summary = "Answer only when every declared input arrived"
    operationId = "strict"
    tag("greetings")
    json<Strictly>()
}

/**
 * One value in the path and the same value in the query, handed straight back.
 *
 * Everything else here decodes a segment into a `Long`, a `Boolean` or a name,
 * and none of those can carry a `+`, a `%2F` or a space. This one is a string
 * that goes nowhere near a domain, so what comes back is only what the request
 * line said — which is what `AllBackendsTest` and `RequestLinePropertyTest` ask
 * of all three backends.
 */
data class RoundTrip(val fromPath: String, val fromQuery: String?)

val segment = pathParam<String>("segment", description = "Whatever the caller put in the path")
val q = queryParam<String>("q", description = "Whatever the caller put in the query").optional()

val roundtrip = endpoint(segment, q) {
    get("items" / segment)
    summary = "Hand back the path segment and the query value, decoded"
    operationId = "roundtrip"
    tag("greetings")
    emits(requestId)
    json<RoundTrip>()
}

/**
 * Plain text and a routed 204. Neither was on the shared surface, so neither
 * was ever asked through this seam — each backend described them in its own
 * test fixtures instead, which is how a difference between them could hide.
 */
val motd = endpoint {
    get("motd")
    summary = "A line of text, as text"
    operationId = "motd"
    tag("greetings")
    emits(requestId)
    text()
}

val forget = endpoint(name) {
    delete("greetings" / name)
    summary = "Forget a remembered greeting"
    operationId = "forget"
    tag("greetings")
    emits(requestId)
    empty(204)
}

/**
 * A declared HEAD, which every router here maps and no description used. The
 * headers are the whole answer — see `HeadRequestsTest`, which also pins what
 * each engine does with a HEAD nobody declared.
 */
val peek = endpoint {
    route(Method.HEAD, path("peek"))
    summary = "Say the service is up, in headers alone"
    operationId = "peek"
    tag("greetings")
    emits(requestId)
    empty(200)
}

/**
 * The three remaining output kinds core frames itself, and the one input it
 * hands over unread. Same reason as [motd]: described once here rather than
 * twice in two backends' fixtures and nowhere on the third.
 */
val everyone = endpoint {
    get("everyone")
    summary = "The whole list, framed as an array while it is produced"
    operationId = "everyone"
    tag("greetings")
    emits(requestId)
    jsonArray<Greeting>()
}

val logo = endpoint {
    get("logo")
    summary = "Opaque bytes, with the media type the description gave them"
    operationId = "logo"
    tag("greetings")
    emits(requestId)
    bytes("image/png")
}

/**
 * A stream in the other direction, which is where a backend is visible a second
 * time: the frames arrive as the backend's own stream type — a `Source` on
 * Pekko — and the framing at both ends is core's.
 *
 * The answer is a value rather than a second stream, which is what makes the
 * two refusals below reachable: a response that has already begun cannot change
 * its status, so a frame that will not decode is a 400 only for an endpoint
 * that reads the upload before it answers. `UploadTimingTest` is where the
 * other shape — a stream each way, answered as it arrives — is exercised.
 */
val tally = endpoint(ndjsonIn<Note>(description = "One note per line, read as it arrives")) {
    post("tally")
    summary = "Count the notes in a streamed upload"
    operationId = "tally"
    tag("greetings")
    emits(requestId)
    errorResponse(400, "A frame that would not decode, named by the line it was on")
    errorResponse(413, "A frame longer than this service will hold")
    json<Tally>()
}

val echoRaw = endpoint(rawBody(description = "Whatever was sent, unread")) {
    post("echo-raw")
    summary = "Hand the body straight back without reading it"
    operationId = "echoRaw"
    tag("greetings")
    emits(requestId)
    bytes()
}

/**
 * Server-sent events, the last kind core frames. `SseKeepAliveTest` covers what
 * each backend does when a stream goes quiet, which genuinely differs; the
 * frames themselves are core's and should not.
 */
val ticker = endpoint {
    get("ticker")
    summary = "A short run of events, named"
    operationId = "ticker"
    tag("greetings")
    emits(requestId)
    sse<Tick>(eventName = "tick")
}

/**
 * The same events with the two fields a dropped connection needs: an `id:` per
 * frame and the `retry:` the stream opens with. A description of its own rather
 * than a change to [ticker], so the frames of a stream that sends neither stay
 * pinned as they were.
 */
val replay = endpoint {
    get("replay")
    summary = "A short run of events a caller can pick up again"
    operationId = "replay"
    tag("greetings")
    emits(requestId)
    sse<Tick>(eventName = "tick", id = { it.seq.toString() }, retry = 15.seconds)
}

/** Every endpoint, so a server and a document cannot be built from different lists. */
val greetingEndpoints: List<Endpoint<*, *>> =
    listOf(
        greet, countdown, echo, remember, preferences, signIn, uploadFile, filters, strict,
        roundtrip, motd, forget, peek, everyone, logo, echoRaw, tally, ticker, replay,
    )

/**
 * What a subscriber signs the notification with. An ordinary header input: a
 * webhook says what it carries the way an endpoint says what it expects.
 */
val hookSignature = headerParam<String>("X-Signature", description = "HMAC of the body, so a receiver can trust it")

/**
 * The one description here that is not a route.
 */
val greetingRecorded = webhook("greetingRecorded") {
    body(note)
    header(hookSignature)
    summary = "Sent to a subscriber when a greeting is remembered"
    description = "The 204 is what the subscriber is expected to answer with, not what this service returns."
    tag("greetings")
    empty(status = 204)
}

/** The calls this service makes, kept apart from the ones it answers for the same reason. */
val greetingWebhooks: List<Webhook> = listOf(greetingRecorded)

/**
 * What `logo` serves. Deliberately ASCII: `pelican-test` reads a response body
 * as a `String`, so a real PNG header would come back with its high bytes
 * replaced and the assertion would be about the test client rather than about
 * the endpoint. The media type is the claim; these bytes only have to survive.
 */
val LOGO_BYTES: ByteArray = "PELICAN".toByteArray(Charsets.US_ASCII)

/** A short, fixed run of events, so backends can be compared frame for frame. */
fun ticks(): List<Tick> = listOf(Tick(1, "one"), Tick(2, "two"))

/**
 * The events after the one a reconnecting caller says it already saw. What
 * Pelican supplies is the resume point; deciding what is still worth sending
 * is the service's, and here that is a filter over a fixed list.
 */
fun ticksSince(lastEventId: String?): List<Tick> {
    val seen = lastEventId?.toIntOrNull() ?: 0
    return ticks().filter { it.seq > seen }
}

/** A short, fixed list, so backends can be compared byte for byte. */
fun greetingsOf(): List<Greeting> = listOf(Greeting("Hello", "en"), Greeting("Bonjour", "fr"))

internal fun greetingOf(who: String, shout: Boolean): Greeting {
    val text = "Hello, $who!"
    return Greeting(if (shout) text.uppercase() else text, language = "en")
}

internal fun tick(seq: Int) = Tick(seq, at = "T-minus-$seq")

internal fun echoed(trace: String?, note: Note) = Echoed(note.text, trace)

/** The note that this service refuses to repeat; see [echoOrRefuse]. */
internal const val FLOOD = "flood"

/** The note this service breaks on; see [echoOrRefuse]. */
internal const val BOOM = "boom"

/**
 * What the bug says. Nothing a caller is entitled to, which is the claim
 * `ThrowingHandlerTest` makes of the 500 that comes back.
 */
internal const val BOOM_DETAIL = "the note that broke the handler"

/** How long the refusal says to wait. */
private const val WAIT_SECONDS = 5L

/**
 * Either the echo, the declared 429 with the `Retry-After` that failure
 * promised, or — for [BOOM] — a failure nobody declared at all. A service has
 * bugs, and what a backend answers for one is a claim like any other.
 */
internal fun echoOrRefuse(trace: String?, note: Note): Outcome<ApiError, Echoed> = when (note.text) {
    FLOOD -> tooMuch(ApiError(429, "Slow down"), retryAfter of WAIT_SECONDS)
    BOOM -> error(BOOM_DETAIL)
    else -> ok(echoed(trace, note))
}

/** The names this service already knows. See [rememberGreeting]. */
private val known = setOf("ada", "grace")

/**
 * Either of [remember]'s two successes, with the `Location` the 201 promised.
 */
internal fun rememberGreeting(who: String, note: Note): Outcome<Nothing, Greeting> {
    val greeting = Greeting(note.text, language = "en")
    return if (who in known) alreadyKnown(greeting)
    else newlyLearned(greeting, greetingAt of "/hello/$who")
}

internal fun preferencesOf(locale: String, session: String?) = Preferences(locale, session)

internal fun sessionOf(form: SignIn) = Session(form.user, form.remember, form.visits)

/**
 * Reads the upload. `file.text()` is the one line that decides this endpoint
 * holds the whole thing in memory — the stream was handed over unread, so a
 * handler that wanted to copy it to disk a block at a time would say `stream()`
 * here instead and never allocate it.
 */
internal fun uploaded(caption: String, notes: UploadedFile, file: UploadedFile) =
    Uploaded(caption, file.filename, file.contentType, file.text(), notes.text())

/**
 * Absent and empty are told apart in the description and joined together here,
 * because what this endpoint answers with is a list either way — the endpoint
 * is what keeps the distinction, and the handler is where a service decides it
 * no longer needs it.
 */
internal fun filtersOf(
    tags: List<String>?,
    ids: List<Long>?,
    features: List<String>?,
    seen: List<String>?,
) = Filters(tags.orEmpty(), ids.orEmpty(), features.orEmpty(), seen.orEmpty())
