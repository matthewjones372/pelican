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
 * Turns a classified refusal into the body a caller reads.
 *
 * One of these per service rather than one per backend: the three interpreters
 * render every refusal through the configured value, which is what stops them
 * answering the same condition three ways.
 */
fun interface RefusalRenderer {
    fun render(refusal: Refusal): RefusalBody
}

/**
 * `{"status":…,"error":…,"detail":…}` — what Pelican refused with before there
 * was a choice, and what it still refuses with unless a service says otherwise.
 */
object ApiErrorEnvelope : RefusalRenderer {
    override fun render(refusal: Refusal): RefusalBody = RefusalBody(
        "application/json",
        ApiError(refusal.status, refusal.reason, refusal.detail).toJson().render().utf8(),
    )
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
    override fun render(refusal: Refusal): RefusalBody = RefusalBody(
        "application/problem+json",
        jsonObj {
            "type" to "about:blank"
            "title" to refusal.reason
            "status" to refusal.status
            putIfNotNull("detail", refusal.detail)
            putIfNotNull("instance", refusal.pathTemplate)
        }.render().utf8(),
    )
}

private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
