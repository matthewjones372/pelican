package io.github.matthewjones372.pelican.test.http4k

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.http4k.toHttpHandler
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Method as Http4kMethod

/**
 * Runs requests straight through the interpreted handler. No socket, no port,
 * no bind — but not a shortcut either: path matching, parameter decoding, body
 * handling and response building are the ones a bound server would use, because
 * they are literally the same function.
 *
 * What it does *not* exercise is everything below the handler: chunk framing on
 * the wire, connection handling, TLS. Keep a socket-level test for those — and
 * note that a streamed response is fully drained here, so this is the wrong
 * transport for asserting elements arrive as they are produced.
 */
class InMemoryTransport(api: Api) : Transport {

    private val handler: HttpHandler = api.toHttpHandler()

    override fun send(request: RequestSpec): ResponseSpec {
        val res = handler(request.toHttp4k())
        val headers = res.headers.mapNotNull { (name, value) -> value?.let { name to it } }
        return ResponseSpec(res.status.code, headers, res.bodyString())
    }
}

private fun RequestSpec.toHttp4k(): Request {
    var req = Request(method.toHttp4kMethod(), target)
    headers.forEach { (name, value) -> req = req.header(name, value) }
    if (body != null) {
        if (headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
            req = req.header("Content-Type", "application/json")
        }
        req = req.body(body!!)
    }
    return req
}

private fun Method.toHttp4kMethod(): Http4kMethod = when (this) {
    Method.GET -> Http4kMethod.GET
    Method.POST -> Http4kMethod.POST
    Method.PUT -> Http4kMethod.PUT
    Method.PATCH -> Http4kMethod.PATCH
    Method.DELETE -> Http4kMethod.DELETE
    Method.HEAD -> Http4kMethod.HEAD
    Method.OPTIONS -> Http4kMethod.OPTIONS
}

/**
 * A client that talks to this API in memory, through http4k.
 *
 * ```
 * val app = ordersApi().inMemoryHttp4k()
 * ```
 *
 * Nothing to close: there is no actor system and no thread pool behind it.
 */
fun Api.inMemoryHttp4k(): ApiClient = ApiClient(InMemoryTransport(this), codecs)
