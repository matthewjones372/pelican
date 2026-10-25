package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.gannet.HttpRequest
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.Cookies
import io.github.matthewjones372.pelican.Credentials
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.parseFormBody
import io.github.matthewjones372.pelican.spi.decodeList

/**
 * The path as the request line spelled it, with its dot segments resolved and every other escape still there, so
 * `%2F` stays inside its segment until the index splits and then decodes, once. Null for a path that climbs above
 * the root or starts with an empty segment, which nothing here routes: Pekko's 404 for both.
 */
internal fun HttpRequest.routedPath(): String? {
    if (path.startsWith("//")) return null
    return withoutDotSegments(path)
}

/**
 * RFC 3986's remove_dot_segments, reading `%2E` as the `.` it spells as Pekko does, so no `.` or `..` reaches the
 * index as a value: a handler using a path param as a name is never handed one. Null when a `..` would climb above
 * the root.
 */
internal fun withoutDotSegments(path: String): String? {
    if (!path.startsWith('/')) return path
    val segments = path.split('/').drop(1)
    val kept = segments.foldIndexed(emptyList<String>()) { i, acc, segment ->
        // A dot segment last leaves the path ending in a slash, as RFC 3986 resolves `/a/b/..` to `/a/`.
        val trailing = if (i == segments.lastIndex) listOf("") else emptyList()
        when (segment.replace("%2e", ".", ignoreCase = true)) {
            "." -> acc + trailing
            ".." -> if (acc.isEmpty()) return null else acc.dropLast(1) + trailing
            else -> acc + segment
        }
    }
    return "/" + kept.joinToString("/")
}

/** Cookies parsed by core, as [decodePlainInputs] parses them, so a session reads the same on every backend. */
internal fun HttpRequest.credentials(): Credentials = object : Credentials {
    override fun header(name: String): String? = headers[name]

    override fun cookie(name: String): String? = Cookies.parseAll(headers.all("Cookie"))[name]?.firstOrNull()
}

/** Query, header and cookie inputs, read as `pelican-pekko` reads them. Path captures are already in [into]. */
internal fun decodePlainInputs(ep: Endpoint<*, *>, request: HttpRequest, into: MutableMap<ParamKey<*>, Any?>) {
    if (ep.queries.isNotEmpty()) {
        val query = queryOf(request)
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
        val raw = request.headers[h.name]
        into[h] = when {
            // RFC 9110 reads two field lines under one name as one joined field, so a list reads every line.
            h.listStyle != null -> h.decodeList(request.headers.all(h.name))

            raw != null -> h.codec.decode(h.name, raw)

            h.required -> throw ApiException(400, "Missing required header '${h.name}'")

            else -> h.default
        }
    }

    if (ep.cookieParams.isEmpty()) return
    val cookies = Cookies.parseAll(request.headers.all("Cookie"))
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
private fun queryOf(request: HttpRequest): Map<String, List<String>> = try {
    parseFormBody(request.query.orEmpty()).groupBy({ it.first }, { it.second })
} catch (e: IllegalArgumentException) {
    throw ApiException(400, "Illegal query string", e.message, cause = e)
}
