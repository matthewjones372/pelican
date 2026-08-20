package example.backends

import dev.pelican.Endpoint
import dev.pelican.StringCodec
import dev.pelican.UploadedFile
import dev.pelican.cookieParam
import dev.pelican.default
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.filePart
import dev.pelican.formBody
import dev.pelican.headerParam
import dev.pelican.jsonBody
import dev.pelican.nonEmpty
import dev.pelican.optional
import dev.pelican.pathParam
import dev.pelican.queryParam
import dev.pelican.responseHeader
import dev.pelican.textPart

/*
 * One description, three servers.
 *
 * This file imports `dev.pelican` and nothing else — no Pekko, no http4k, no
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

data class Uploaded(val caption: String, val filename: String?, val contentType: String?, val content: String)

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

/** A form, which is what an HTML page posts when nobody has written any JavaScript. */
val credentials = formBody<SignIn>(description = "The sign-in form, as a browser posts it")

/**
 * An upload: one text field and one file. The file arrives as a stream, so the
 * handler below decides what it costs — and the text field is declared with the
 * same codecs and refinements a query parameter takes.
 */
val caption = textPart("caption", StringCodec.nonEmpty(), description = "What to call the file")
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
    json<Echoed>()
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

/** A multipart upload: one text part, one file part, streamed. */
val uploadFile = endpoint(caption, upload) {
    post("upload")
    summary = "Upload a file with a caption"
    operationId = "uploadFile"
    tag("greetings")
    emits(requestId)
    json<Uploaded>()
}

/** Every endpoint, so a server and a document cannot be built from different lists. */
val greetingEndpoints: List<Endpoint<*, *>> =
    listOf(greet, countdown, echo, preferences, signIn, uploadFile)

internal fun greetingOf(who: String, shout: Boolean): Greeting {
    val text = "Hello, $who!"
    return Greeting(if (shout) text.uppercase() else text, language = "en")
}

internal fun tick(seq: Int) = Tick(seq, at = "T-minus-$seq")

internal fun echoed(trace: String?, note: Note) = Echoed(note.text, trace)

internal fun preferencesOf(locale: String, session: String?) = Preferences(locale, session)

internal fun sessionOf(form: SignIn) = Session(form.user, form.remember, form.visits)

/**
 * Reads the upload. `file.text()` is the one line that decides this endpoint
 * holds the whole thing in memory — the stream was handed over unread, so a
 * handler that wanted to copy it to disk a block at a time would say `stream()`
 * here instead and never allocate it.
 */
internal fun uploaded(caption: String, file: UploadedFile) =
    Uploaded(caption, file.filename, file.contentType, file.text())
