package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.spi.RequestView
import io.github.matthewjones372.pelican.spi.withoutDotSegments
import org.eclipse.jetty.server.Request

/**
 * The path as the request line spelled it, below any context the handler is mounted under and with its dot
 * segments resolved. Every other escape is still there, so `%2F` stays inside its segment until the index
 * splits and then decodes, once. Null for a path that climbs above the root, which Jetty's own parser refuses
 * with a 400 before any handler runs; nothing here routes one.
 */
internal fun Request.routedPath(): String? {
    val raw = httpURI.path
    val context = Request.getContextPath(this)
    return withoutDotSegments(if (context.isNullOrEmpty() || context == "/") raw else raw.removePrefix(context))
}

/** Jetty's request read in place: what an authenticator, `Params.requestHeader` and the plain inputs read. */
internal class JettyView(private val request: Request) : RequestView {
    override val method: String get() = request.method
    override val rawPath: String get() = request.httpURI.path
    override val rawQuery: String? get() = request.httpURI.query

    override fun header(name: String): String? = request.headers.get(name)

    override fun headers(name: String): List<String> = request.headers.getValuesList(name)
}
