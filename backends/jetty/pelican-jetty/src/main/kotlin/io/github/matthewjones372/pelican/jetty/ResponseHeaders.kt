package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ResponseHeaderPolicy
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import java.nio.ByteBuffer

/**
 * This handler, with [api]'s `responseHeaders { }` on every response it writes. [isDocs] marks a handler serving the
 * docs modules' document and pages. [toHandler] already does this for the endpoints, and [Unanswered] for a 404 or
 * 405 once a handler so wrapped has passed the request on.
 */
fun Handler.withResponseHeaders(api: Api, isDocs: Boolean = false): Handler {
    val policy = api.responseHeaders ?: return this
    return answeredUnder(policy, isDocs) { null }
}

/** [withResponseHeaders], told which endpoint answered once a response is being written. */
internal fun Handler.answeredUnder(
    policy: ResponseHeaderPolicy,
    isDocs: Boolean,
    endpointOf: (Request) -> Endpoint<*, *>?,
): Handler = object : Handler.Wrapper(this) {
    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        val answering = AnsweringResponse(request, response, policy, isDocs, endpointOf)
        return super.handle(request, answering, callback).also { taken -> if (!taken) request.declinedUnder(policy) }
    }
}

/** Adds the policy's headers as the first byte is written, when the status and the handler's own headers are set. */
internal class AnsweringResponse(
    request: Request,
    response: Response,
    private val policy: ResponseHeaderPolicy,
    private val isDocs: Boolean,
    private val endpointOf: (Request) -> Endpoint<*, *>?,
) : Response.Wrapper(request, response) {
    @Volatile
    private var added = false

    override fun write(last: Boolean, byteBuffer: ByteBuffer?, callback: Callback) {
        if (!added) {
            added = true
            policy.headersFor(request.httpURI.path, endpointOf(request), status, isDocs) { headers.contains(it) }
                .forEach { (name, value) -> headers.add(name, value) }
        }
        super.write(last, byteBuffer, callback)
    }
}
