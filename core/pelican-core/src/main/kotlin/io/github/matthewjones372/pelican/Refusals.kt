package io.github.matthewjones372.pelican

import java.nio.charset.StandardCharsets

/**
 * A refusal as the renderer is allowed to see it: the classification, never the
 * throwable and never the request.
 *
 * The exception is deliberately absent. A renderer handed one could put a
 * message written for a log into a 500 body, and the whole reason the 500 path
 * says nothing but a reference is that it must not. [pathTemplate] is the route
 * that refused rather than the URL that was asked for, so a renderer cannot echo
 * anything a caller sent either.
 */
class Refusal(
    val status: Int,
    /** The short sentence: "Malformed request body", "Not acceptable". */
    val reason: String,
    val detail: String?,
    /** Set on a 500 nobody described — the id the log line carries. */
    val reference: String?,
    /** The matched route's template, or null where nothing matched. */
    val pathTemplate: String?,
)

/** One rendered refusal body, and what to label it on the wire. */
class RefusalBody(val mediaType: String, val bytes: ByteArray)

/**
 * Why a request was refused before it reached the filter chain.
 *
 * A closed set, because it is a metric dimension: the label of every reason is
 * written here, so the number of series a refusal counter can grow is a
 * property of this file rather than of what a caller sends.
 */
enum class RefusalReason(val label: String) {
    /** Nothing described the path, or nothing described it under this method. */
    UNMATCHED("unmatched"),

    /** A parameter or a body that would not decode into what was declared. */
    DECODE("decode"),

    /** A body over `Api.maxBodyBytes`, or over a multipart part's own bound. */
    BODY_LIMIT("body_limit"),

    /** A `Content-Type` no codec on the endpoint reads. */
    CONTENT_TYPE("content_type"),

    /** An `Accept` that takes nothing the endpoint produces. */
    ACCEPT("accept"),
}

/**
 * Told about each request refused before any filter ran.
 *
 * `http.server.requests` counts what reached the chain, and by construction
 * cannot count what did not: a 413 is answered where the body is read, several
 * layers outside the outermost filter. That gap is the traffic a dashboard is
 * least able to do without, because it widens exactly during an attack or a
 * broken-client rollout. This is where it is reported, and `pelican-metrics`
 * turns it into a counter.
 *
 * It is handed the classification and nothing else. Not the request, not the
 * throwable, and not the detail that goes in the body: every one of those
 * varies per caller, and an observer given one would sooner or later be tagging
 * a meter with it. [pathTemplate] is the route that refused — `/orders/{orderId}`
 * — and is null where nothing matched.
 *
 * Called on the thread that is about to answer, so an implementation that
 * blocks delays a response. Incrementing a counter is what it is for.
 */
fun interface RefusalObserver {
    fun refused(reason: RefusalReason, status: Int, pathTemplate: String?)
}

/**
 * Turns a classified refusal into the body a caller reads.
 *
 * One of these per service rather than one per backend: the three interpreters
 * render every refusal through the configured value, which is what stops them
 * answering the same condition three ways.
 *
 * A renderer describes itself as well as writing bytes, and the document reads
 * [mediaType] and [schema] from the same value the wire is written by. Two
 * members rather than one, because a renderer whose document had to be written
 * separately is a renderer whose document goes stale.
 */
interface RefusalRenderer {
    /** What a refusal this writes is labelled with, on the wire and in the document. */
    val mediaType: String

    /**
     * What the document calls the schema below.
     *
     * A component rather than a schema inlined per operation, because every
     * operation refuses in the same envelope and a document repeating it is a
     * document a reader — or an importer — turns into one type per operation.
     * The name is the envelope's own: a service already describing that type
     * keeps its description, since it is the same shape being described twice.
     */
    val componentName: String

    /** The schema of the body it writes, as a document publishes it. */
    val schema: JsonObj

    fun render(refusal: Refusal): ByteArray
}

/**
 * `{"status":…,"error":…,"detail":…}` — what Pelican refused with before there
 * was a choice, and what it still refuses with unless a service says otherwise.
 */
object ApiErrorEnvelope : RefusalRenderer {
    override val mediaType: String = "application/json"

    /** [ApiError] itself — the type a service already writes as `errorJson<ApiError>`. */
    override val componentName: String = "ApiError"

    // `required` is alphabetical because that is the order a codec describing
    // the class itself writes, and a service declaring `errorJson<ApiError>`
    // publishes that description under this same component name.
    // `RefusalEnvelopeSchemaTest` holds the two together.
    override val schema: JsonObj = objectSchema(
        required = listOf("error", "status"),
        "status" to integer,
        "error" to string,
        "detail" to nullableString,
    )

    override fun render(refusal: Refusal): ByteArray =
        ApiError(refusal.status, refusal.reason, refusal.detail).toJson().render().utf8()
}

/**
 * RFC 9457 problem details.
 *
 * `type` is `about:blank`, which the RFC defines as "this problem has no
 * semantics beyond its status code" — the honest reading of a refusal raised
 * before any handler ran. A service wanting its own type URIs writes a
 * [RefusalRenderer] of its own; core is not the place to invent a URI namespace.
 *
 * `instance` is the path template, not the request's URL: the renderer never
 * sees the request, so what it can say is which route refused.
 */
object ProblemDetails : RefusalRenderer {
    override val mediaType: String = "application/problem+json"

    override val componentName: String = "ProblemDetails"

    override val schema: JsonObj = objectSchema(
        required = listOf("type", "title", "status"),
        "type" to jsonObj {
            "type" to "string"
            "format" to "uri-reference"
        },
        "title" to string,
        "status" to integer,
        "detail" to string,
        "instance" to jsonObj {
            "type" to "string"
            "format" to "uri-reference"
        },
    )

    override fun render(refusal: Refusal): ByteArray = jsonObj {
        "type" to "about:blank"
        "title" to refusal.reason
        "status" to refusal.status
        putIfNotNull("detail", refusal.detail)
        putIfNotNull("instance", refusal.pathTemplate)
    }.render().utf8()
}

private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

private val string = jsonObj { "type" to "string" }
private val nullableString = jsonObj { put("type", jsonStrings(listOf("string", "null"))) }
private val integer = jsonObj {
    "type" to "integer"
    "format" to "int32"
}

/** Written once so the two envelopes below cannot spell an object schema two ways. */
private fun objectSchema(required: List<String>, vararg properties: Pair<String, JsonObj>): JsonObj = jsonObj {
    "type" to "object"
    put("properties", jsonObj { properties.forEach { (name, schema) -> put(name, schema) } })
    put("required", jsonStrings(required))
}
