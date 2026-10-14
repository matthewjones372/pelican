package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.renderError
import io.github.matthewjones372.pelican.spi.statusOfError

/**
 * What status this endpoint is about to answer with, given what came back out
 * of the handler.
 *
 * A filter sees the request on the way in and the handler's *return value* on
 * the way out, which is one step short of what anything measuring the service
 * wants: `Outcome.Err(notFound, ...)` is a 404, but only the endpoint knows
 * that, because the status is on the declaration rather than on the payload.
 * Working it out is a handful of lines — and a handful of lines copied into
 * three interpreters, or into every service that wants a request metric, is a
 * handful of lines that will disagree with the response eventually. So it is
 * written here, once, in the same module that decides what the response is.
 *
 * Every branch below reads the same value the interpreter reads when it renders
 * the response a moment later: [chosenSuccess] for which success an `Outcome.Ok`
 * named, [ErrorOutput.status] for a declared failure, and [statusOfError] for a
 * throwable, which shares its table with [renderError]. `MetricsAcrossBackendsTest`
 * in the example holds the three backends to that, by comparing what a filter
 * was told against what came back over the socket.
 *
 * One case it cannot answer, and says so here rather than pretending: a
 * response that fails while it is being *written* — a codec that throws, a
 * handler naming a success the endpoint never declared — becomes a 500 after
 * the filter chain has already unwound, so a filter records the status the
 * handler asked for rather than the one the caller received. That is rare, it
 * is a bug in the service rather than in a request, and the alternative is
 * holding the chain open until the last byte is on the wire, which would change
 * what a filter is.
 */
fun Endpoint<*, *>.statusFor(result: Any?, error: Throwable?): Int {
    if (error != null) return statusOfError(error)

    val out = output
    return when {
        // The declaration supplies the status, not the payload's type: two
        // failures carrying the same type stay distinct. A bare `err(...)`
        // means the single declared failure; one with several to mean is
        // refused when the response is written, so a 500 is what goes out.
        result is Outcome.Err<*> ->
            result.declared?.status
                ?: (out as? DeclaredResponses<*, *>)?.failures?.singleOrNull()?.status
                ?: UNDECLARED

        result is Outcome.Ok<*> && out is DeclaredResponses<*, *> -> out.chosenSuccess(result).status

        // One declared response, or a stream: the description already said.
        else -> out.status
    }
}

/** What an `UndeclaredResponse` is answered with, and so what this reports for one. */
private const val UNDECLARED = 500
