package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.gannet.HttpRequest
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.Cookies
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.parseFormBody
import io.github.matthewjones372.pelican.spi.RequestView
import io.github.matthewjones372.pelican.spi.decodeList
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

/** Query, header and cookie inputs, read as `pelican-pekko` reads them. Path captures are already in [into]. */
internal fun decodePlainInputs(ep: Endpoint<*, *>, view: RequestView, into: MutableMap<ParamKey<*>, Any?>) {
    if (ep.queries.isNotEmpty()) {
        val query = queryOf(view.rawQuery)
        ep.queries.forEach { q ->
            val values = query[q.name]
            into[q] = when {
                q.listStyle != null -> q.decodeList(values.orEmpty())
                values != null -> q.codec.decode(q.name, values.first())
                q.required -> throw ApiException(400, "Missing required query parameter '${q.name}'")
                else -> q.default
            }
        }
    }

    ep.headerParams.forEach { h ->
        val raw = view.header(h.name)
        into[h] = when {
            // RFC 9110 reads two field lines under one name as one joined field, so a list reads every line.
            h.listStyle != null -> h.decodeList(view.headers(h.name))

            raw != null -> h.codec.decode(h.name, raw)

            h.required -> throw ApiException(400, "Missing required header '${h.name}'")

            else -> h.default
        }
    }

    if (ep.cookieParams.isEmpty()) return
    val cookies = Cookies.parseAll(view.headers("Cookie"))
    ep.cookieParams.forEach { c ->
        val raw = cookies[c.name]?.first()
        into[c] = when {
            c.listStyle != null -> c.decodeList(cookies[c.name].orEmpty())
            raw != null -> c.codec.decode(c.name, raw)
            c.required -> throw ApiException(400, "Missing required cookie '${c.name}'")
            else -> c.default
        }
    }
}

/**
 * The query as the values each name carries, decoded as a form, where `+` is a space. One that will not decode is
 * the caller's 400, as on Pekko.
 */
private fun queryOf(rawQuery: String?): Map<String, List<String>> = try {
    parseFormBody(rawQuery.orEmpty()).groupBy({ it.first }, { it.second })
} catch (e: IllegalArgumentException) {
    throw ApiException(400, "Illegal query string", e.message, cause = e)
}
