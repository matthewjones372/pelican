package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.gannet.HttpRequest
import io.github.matthewjones372.pelican.spi.RequestView
import io.github.matthewjones372.pelican.spi.withoutDotSegments

/**
 * The path as the request line spelled it, with its dot segments resolved and every other escape still there, so
 * `%2F` stays inside its segment until the index splits and then decodes, once. Null for a path that climbs above
 * the root or starts with an empty segment, which nothing here routes: Pekko's 404 for both.
 */
internal fun HttpRequest.routedPath(): String? {
    if (path.startsWith("//")) return null
    return withoutDotSegments(path)
}

/** Gannet's request read in place: what an authenticator, `Params.requestHeader` and the plain inputs read. */
internal class GannetView(val request: HttpRequest) : RequestView {
    override val method: String get() = request.method
    override val rawPath: String get() = request.path
    override val rawQuery: String? get() = request.query

    override fun header(name: String): String? = request.headers[name]

    // Most requests carry no line under most names, and `Headers.all` builds two lists to say so.
    override fun headers(name: String): List<String> =
        if (name in request.headers) request.headers.all(name) else emptyList()
}
