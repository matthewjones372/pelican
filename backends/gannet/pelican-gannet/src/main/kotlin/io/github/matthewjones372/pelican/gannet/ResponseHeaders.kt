package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.gannet.HttpExchange
import io.github.matthewjones372.gannet.ResponseStream
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ResponseHeaderPolicy
import java.io.OutputStream
import java.util.concurrent.Executor

/**
 * This handler, with [api]'s `responseHeaders { }` on every response it writes. [isDocs] marks a handler serving the
 * docs modules' document and pages. [toHandler] already does this for the endpoints, and [start] for a 404 or 405.
 */
fun GannetHandler.withResponseHeaders(api: Api, isDocs: Boolean = false): GannetHandler {
    val policy = api.responseHeaders ?: return this
    return answeredUnder(policy, isDocs) { null }
}

/** [withResponseHeaders], told which endpoint answered once a response is being written. */
internal fun GannetHandler.answeredUnder(
    policy: ResponseHeaderPolicy,
    isDocs: Boolean,
    endpointOf: (HttpExchange) -> Endpoint<*, *>?,
): GannetHandler = GannetHandler { exchange -> handle(AnsweringExchange(exchange, policy, isDocs, endpointOf)) }

/** Adds the policy's headers to whichever response the handler starts, given its status and its own headers. */
internal class AnsweringExchange(
    val asked: HttpExchange,
    private val policy: ResponseHeaderPolicy,
    private val isDocs: Boolean,
    private val endpointOf: (HttpExchange) -> Endpoint<*, *>?,
) : HttpExchange by asked {

    private fun answered(status: Int, headers: List<Pair<String, String>>): List<Pair<String, String>> =
        headers + policy.headersFor(asked.request.path, endpointOf(asked), status, isDocs) { name ->
            headers.any { it.first.equals(name, ignoreCase = true) }
        }

    override fun respond(status: Int, headers: List<Pair<String, String>>, body: ByteArray) =
        asked.respond(status, answered(status, headers), body)

    override fun respond(status: Int, headers: List<Pair<String, String>>, writer: (OutputStream) -> Unit) =
        asked.respond(status, answered(status, headers), writer)

    override fun stream(status: Int, headers: List<Pair<String, String>>): ResponseStream =
        asked.stream(status, answered(status, headers))

    override fun offload(executor: Executor, work: (HttpExchange) -> Unit) =
        asked.offload(executor) { moved -> work(AnsweringExchange(moved, policy, isDocs, endpointOf)) }
}
