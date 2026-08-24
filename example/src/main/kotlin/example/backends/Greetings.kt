package example.backends

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.LongCodec
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
import io.github.matthewjones372.pelican.nonEmpty
import io.github.matthewjones372.pelican.of
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.positive
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.repeated
import io.github.matthewjones372.pelican.responseHeader
import io.github.matthewjones372.pelican.textPart
import io.github.matthewjones372.pelican.webhook

/*
 * One description, three servers.
 *
 * This file imports `io.github.matthewjones372.pelican` and nothing else — no Pekko, no http4k, no
 * Ktor, no JSON library. That is what makes the next three files possible:
 * `OnPekko.kt`, `OnHttp4k.kt` and `OnKtor.kt` bind *these same values* to
 * handlers, and neither the endpoints below nor the OpenAPI document generated
 * from them knows which server ends up serving them.
 *
 * The larger orders example does the same thing at scale; this one is small
 * enough to read end to end.
 */

data class Greeting(val greeting: String, val language: String)

data class Tick(val seq: Int, val at: String)

data class Note(val text: String)

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

/*
 * Four inputs carrying several values each, one per encoding OpenAPI can
 * describe — and the point of them being here is that the three interpreters
 * below read all four without any of them saying so.
 *
 * Each is optional, so an absent one arrives as `null` rather than as an empty
 * list. That distinction is the reason the modifier exists: `?tag=` carries no
 * element, so a list is never empty on the wire, and reading absence as empty
 * would leave "filtered by nothing" and "did not filter" spelled the same way.
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
 *
 * A browser with no JavaScript on the page posts a form; the same page's script
 * posts JSON, and both are a `SignIn`. `or` is what says so — one payload, two
 * encodings, and the request's `Content-Type` picks the decode. The form comes
 * first because that is what a client with no way to ask sends, and because the
 * document's order is the answer to "which one?" everywhere else too.
 *
 * A `Content-Type` that is neither is a 415 naming the two that are, which is
 * the one thing a single-encoding body deliberately does not do: with nothing
 * to choose between, the header carries no information and a body that will not
 * decode explains itself better than a refusal to look at it would.
 */
val credentials = formBody<SignIn>(description = "The sign-in details, as a form or as JSON") or
    jsonBody<SignIn>()

/**
 * An upload: a text field and two files, which is the shape an ordinary upload
 * form has and the shape this library used to refuse.
 *
 * The refusal was sound as far as it went. Reading stops at a streamed part —
 * that is what handing a handler a live window on the request means — so a
 * second file could only ever be reached by holding the first, and holding one
 * silently is exactly what a streaming upload exists not to do.
 *
 * `bufferedFile` says it out loud instead. `maxBytes` has no default, so the
 * memory this endpoint spends on [notes] is written where somebody choosing it
 * has to look at it, and a caller who sends more gets a 413 naming the part.
 * [upload] is unchanged and still streamed, because it is declared last.
 */
val caption = textPart("caption", StringCodec.nonEmpty(), description = "What to call the file")
val notes = bufferedFile("notes", maxBytes = 512, contentType = "text/plain", description = "A short note")
val upload = filePart("file", contentType = "text/plain", description = "The file itself")

/**
 * A header every endpoint here sends back, declared once like any input.
 *
 * Nothing in a handler sets it — a filter on the `Api` does, for all three
 * endpoints at once (see `greetingsApi`). That is the pairing worth noticing:
 * the *declaration* is what puts it in the document and what permits it to be
 * set, and the *filter* is what sets it everywhere without a handler being
 * asked to remember.
 */
val requestId = responseHeader<String>("X-Request-Id", description = "Correlates this answer with the server's log")

/**
 * A header belonging to one *failure* rather than to the endpoint.
 *
 * [requestId] above is declared with `emits(...)`, so it is documented on the
 * success response and any handler here may set it. This one is declared on
 * the 429 below and nowhere else: it is documented on that response alone, the
 * handler supplies its value when it returns that failure, and a successful
 * echo therefore cannot carry it.
 */
val retryAfter = responseHeader<Long>("Retry-After", description = "Seconds to wait before saying it again")

/** The pair a 429 nearly always is: a body saying what happened, a header saying when to come back. */
val tooMuch = errorJson<ApiError>(429, "The caller is being asked to slow down", retryAfter)

/**
 * Where a newly remembered greeting lives.
 *
 * Declared on the 201 below and on nothing else, for the same reason
 * [retryAfter] is declared on the 429: a `Location` on the 200 would be
 * pointing at something that was already there, and [requestId]'s `emits(...)`
 * would have permitted exactly that.
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
 * A streamed response, which is where the backends visibly differ: the
 * description says "a stream of Tick" and each backend's binder demands its own
 * stream type — a Pekko `Source`, an http4k `Sequence`, a Ktor `Flow`. The wire
 * format, newline-delimited JSON, is rendered by core in all three cases.
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
 *
 * `newlyLearned or alreadyKnown` is the whole declaration: an
 * `Endpoint<In2<String, Note>, Fallible<Nothing, Greeting>>`, whose binder
 * demands a handler returning an `Outcome` — so a 200 this endpoint never
 * declared does not compile, exactly as an undeclared failure does not.
 *
 * `emits(requestId)` sits beside it and is the endpoint's own header, set by a
 * filter on whichever response comes back. The `Location` belongs to the 201
 * alone. Both readings reach the document, on the responses that carry them.
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
 *
 * Also the one endpoint here that says it is served from somewhere else, which
 * is what an upload host usually is. That claim reaches the document and a
 * generated client and stops there: all three servers below route and answer
 * this path exactly as they do the rest, because a server serves what it
 * serves and no description moves a request. Nothing is actually running at
 * `uploads.example.com` — the URL is here to be read, and `AllBackendsTest`
 * asserts that it changes nothing about the serving.
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

/** Every endpoint, so a server and a document cannot be built from different lists. */
val greetingEndpoints: List<Endpoint<*, *>> =
    listOf(greet, countdown, echo, remember, preferences, signIn, uploadFile, filters)

/**
 * What a subscriber signs the notification with. An ordinary header input: a
 * webhook says what it carries the way an endpoint says what it expects.
 */
val hookSignature = headerParam<String>("X-Signature", description = "HMAC of the body, so a receiver can trust it")

/**
 * The one description here that is not a route.
 *
 * A webhook is the same shape as everything above — a method, a body, a
 * response — pointed the other way: this service *sends* it, to a URL somebody
 * subscribed with, and the response is what that subscriber answers. So there
 * is no path to write and nothing on this side to bind, which is exactly what
 * `AllBackendsTest` holds all three interpreters to: it reaches the document
 * and no server routes it.
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

internal fun greetingOf(who: String, shout: Boolean): Greeting {
    val text = "Hello, $who!"
    return Greeting(if (shout) text.uppercase() else text, language = "en")
}

internal fun tick(seq: Int) = Tick(seq, at = "T-minus-$seq")

internal fun echoed(trace: String?, note: Note) = Echoed(note.text, trace)

/** The note that this service refuses to repeat; see [echoOrRefuse]. */
internal const val FLOOD = "flood"

/** How long the refusal says to wait. */
private const val WAIT_SECONDS = 5L

/**
 * Either the echo or the declared 429, with the `Retry-After` that failure
 * promised.
 *
 * What decides it is the note rather than a counter, deliberately. A real
 * limiter has state, and this suite asks three servers the same question in
 * whatever order the runner feels like — an answer that depended on which one
 * was asked first would be a test of the runner.
 */
internal fun echoOrRefuse(trace: String?, note: Note): Outcome<ApiError, Echoed> =
    if (note.text == FLOOD) tooMuch(ApiError(429, "Slow down"), retryAfter of WAIT_SECONDS)
    else ok(echoed(trace, note))

/** The names this service already knows. See [rememberGreeting]. */
private val known = setOf("ada", "grace")

/**
 * Either of [remember]'s two successes, with the `Location` the 201 promised.
 *
 * What decides it is the name rather than what has been stored, deliberately,
 * and for the reason [echoOrRefuse] gives: three servers answer this suite in
 * whatever order the runner picks, so an answer that depended on which one was
 * asked first would be a test of the runner.
 *
 * Both alternatives carry a `Greeting`, so the value cannot say which response
 * this is — invoking the declaration is what does, and it is what fixes the
 * status on all three backends.
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
 *
 * `notes` is the other way round: it was already read, within the bound its
 * declaration named, and the handler is handed the same [UploadedFile] either
 * way. Which is the point — where the bytes are is a decision the description
 * makes, and a handler that stops caring does not have to be rewritten.
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
