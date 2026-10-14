package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.Cookies
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.spi.decodeList
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.util.Fields

/**
 * The path as the request line spelled it, below any context the handler is mounted under. Every escape is
 * still there, so `%2F` stays inside its segment until the index splits and then decodes, once.
 */
internal fun Request.encodedPathInContext(): String {
    val raw = httpURI.path
    val context = Request.getContextPath(this)
    return if (context.isNullOrEmpty() || context == "/") raw else raw.removePrefix(context)
}

/** Query, header and cookie inputs, read as `pelican-pekko` reads them. Path captures are already in [into]. */
internal fun decodePlainInputs(ep: Endpoint<*, *>, request: Request, into: MutableMap<ParamKey<*>, Any?>) {
    if (ep.queries.isNotEmpty()) {
        val query = queryOf(request)
        ep.queries.forEach { q ->
            into[q] = when {
                q.listStyle != null -> q.decodeList(query.getValuesOrEmpty(q.name))
                query.get(q.name) != null -> q.codec.decode(q.name, query.getValue(q.name))
                q.required -> throw ApiException(400, "Missing required query parameter '${q.name}'")
                else -> q.default
            }
        }
    }

    ep.headerParams.forEach { h ->
        val raw = request.headers.get(h.name)
        into[h] = when {
            // RFC 9110 reads two field lines under one name as one joined field, so a list reads every line.
            h.listStyle != null -> h.decodeList(request.headers.getValuesList(h.name))

            raw != null -> h.codec.decode(h.name, raw)

            h.required -> throw ApiException(400, "Missing required header '${h.name}'")

            else -> h.default
        }
    }

    if (ep.cookieParams.isEmpty()) return
    // Core's parser rather than Jetty's, so a cookie decodes the same on every backend.
    val cookies = Cookies.parseAll(request.headers.getValuesList("Cookie"))
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

/** A query that will not decode is the caller's 400, as in Pekko; Jetty's `BadMessageException` is one of these. */
private fun queryOf(request: Request): Fields = try {
    Request.extractQueryParameters(request)
} catch (e: IllegalArgumentException) {
    throw ApiException(400, "Illegal query string", e.message, cause = e)
}
