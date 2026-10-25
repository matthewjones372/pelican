package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.gannet.Headers
import io.github.matthewjones372.gannet.HttpExchange
import io.github.matthewjones372.pelican.CorsPolicy
import io.github.matthewjones372.pelican.Pages
import io.github.matthewjones372.pelican.spi.CorsHeaders
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * [pages] as Pekko's pages route serves them (spec 0059), asked after the endpoints and the preflight. A path no
 * file answers is passed on, so it is still the 404 or 405 it was before pages existed. Resources come from
 * [classLoader], a directory or a jar alike.
 */
internal class PagesHandler(
    private val pages: Pages,
    private val cors: CorsPolicy?,
    private val classLoader: ClassLoader,
) : GannetHandler {

    override fun handle(exchange: HttpExchange): Boolean {
        val request = exchange.request
        val path = request.routedPath()?.let(::pekkoPath) ?: return false
        val below = when {
            pages.at == "/" -> path.removePrefix("/")
            path == pages.at -> ""
            path.startsWith("${pages.at}/") -> path.removePrefix("${pages.at}/")
            else -> null
        }
        val resolved = below?.let { pages.resolve(it, ::isFile) } ?: return false
        val asked = request.query?.let { "$path?$it" } ?: path
        val sendTo = if (resolved.redirect) null else pages.guard?.redirectFor(asked, GannetView(request))
        when {
            resolved.redirect -> redirect(MOVED_PERMANENTLY, "$path/").writeTo(exchange)

            // A guard's answer is a place to go now, not where the page lives: 302, not 301.
            sendTo != null -> redirect(FOUND, sendTo).writeTo(exchange)

            else -> {
                val allowed = cors?.actualResponseHeaders(request.headers[CorsHeaders.ORIGIN]).orEmpty()
                // A page behind a guard is someone's own: a copy kept by the browser would outlive signing out.
                val private = if (pages.guard != null) listOf(CACHE_CONTROL to "no-store") else emptyList()
                val url = checkNotNull(classLoader.getResource(resolved.resource)) { resolved.resource }
                // Reading the file may block, so off the carrier; Gannet answers 500 if it throws.
                exchange.offload(OFF_CARRIER) { held ->
                    serve(request.headers, url, pageContentType(resolved.resource), allowed + private).writeTo(held)
                }
            }
        }
        return true
    }

    /** Whether [name] is a file on the classpath: a directory resolves to a URL too, and is not one. */
    private fun isFile(name: String): Boolean {
        val url = classLoader.getResource(name) ?: return false
        return when (url.protocol) {
            "file" -> File(url.toURI()).isFile
            "jar" -> (url.openConnection() as JarURLConnection).jarEntry?.isDirectory == false
            else -> true
        }
    }
}

/**
 * The path as Pekko's `Uri.path` spells it: each segment decoded, then escaped again only where a segment must be,
 * so `%6F` is `o` and a space stays `%20`. A segment holding an escaped `/` or `\` names no page, whatever a class
 * loader might make of it.
 */
private fun pekkoPath(path: String): String? = path.split('/').map { segment ->
    if (MALFORMED.containsMatchIn(segment)) return null
    val decoded = ESCAPED.replace(segment) { run ->
        String(run.value.chunked(ESCAPE_LENGTH).map { it.drop(1).toInt(HEX).toByte() }.toByteArray(), Charsets.UTF_8)
    }
    if ('/' in decoded || '\\' in decoded) return null
    decoded.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
        val char = (byte.toInt() and BYTE).toChar()
        if (char in
            SEGMENT_CHARS
        ) char.toString() else "%" + (byte.toInt() and BYTE).toString(HEX).uppercase().padStart(2, '0')
    }
}.joinToString("/")

private val ESCAPED = Regex("(%[0-9A-Fa-f]{2})+")
private val MALFORMED = Regex("%(?![0-9A-Fa-f]{2})")

/** RFC 3986's `pchar` less the escape itself: what Pekko leaves unescaped in a path segment. */
private val SEGMENT_CHARS: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "-._~!$&'()*+,;=:@".toList()).toSet()
private const val ESCAPE_LENGTH = 3
private const val HEX = 16
private const val BYTE = 0xFF
private const val MILLIS = 1000

/**
 * Pekko's `getFromResource`: its entity tag and `Last-Modified`, and its conditional requests (RFC 9110 13.2.2).
 * Read whole: pages are small static assets, and a strict reply is queued without waiting on the client.
 */
private fun serve(asked: Headers, url: URL, contentType: String, headers: List<Pair<String, String>>): Strict {
    val connection = url.openConnection()
    val length = connection.contentLengthLong
    // Whole seconds, as Pekko's `DateTime` keeps it, so a client sending back the date it was given matches it.
    val modified = minOf(connection.lastModified, System.currentTimeMillis()) / MILLIS * MILLIS
    val tag = "\"${java.lang.Long.toHexString(connection.lastModified xor java.lang.Long.reverse(length))}\""
    val validators = listOf(ETAG to tag, LAST_MODIFIED to HTTP_DATE.format(Instant.ofEpochMilli(modified)))
    return when (precondition(asked, tag, modified)) {
        NOT_MODIFIED -> Strict(NOT_MODIFIED, null, ByteArray(0), headers + validators)

        PRECONDITION_FAILED -> Strict(PRECONDITION_FAILED, TEXT, PRECONDITION_FAILED_TEXT, headers)

        else -> {
            val body = connection.getInputStream().use { it.readAllBytes() }
            Strict(OK, if (body.isEmpty()) null else contentType, body, headers + validators)
        }
    }
}

/** Pekko's `conditional` directive, in its order: 412, 304, or null to answer in full. */
private fun precondition(headers: Headers, tag: String, modified: Long): Int? = when {
    failsPrecondition(headers, tag, modified) -> PRECONDITION_FAILED
    notModified(headers, tag, modified) -> NOT_MODIFIED
    else -> null
}

private fun failsPrecondition(headers: Headers, tag: String, modified: Long): Boolean {
    val ifMatch = headers[IF_MATCH]
    if (ifMatch != null) return !matches(tag, ifMatch, weak = false)
    val since = dateOf(headers[IF_UNMODIFIED_SINCE])
    return since != -1L && !unmodified(modified, since)
}

private fun notModified(headers: Headers, tag: String, modified: Long): Boolean {
    val ifNoneMatch = headers[IF_NONE_MATCH]
    if (ifNoneMatch != null) return matches(tag, ifNoneMatch, weak = true)
    val since = dateOf(headers[IF_MODIFIED_SINCE])
    return since != -1L && unmodified(modified, since)
}

/** An HTTP date as epoch milliseconds, or -1 when absent or unreadable, which then says nothing. */
private fun dateOf(value: String?): Long {
    if (value == null) return -1L
    return try {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        -1L
    }
}

/** IMF-fixdate, its day always two digits, as Jetty and Pekko write it. */
private val HTTP_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

/** A date in the future says nothing about a file, so it never makes one unmodified. */
private fun unmodified(modified: Long, since: Long): Boolean = modified <= since && since < System.currentTimeMillis()

/** Whether [tag], a strong tag, is among [range]: `*`, or a list of tags, weak ones matching only when [weak]. */
private fun matches(tag: String, range: String, weak: Boolean): Boolean = range.split(',').map(String::trim).any {
    it == "*" || it == tag || (weak && it.removePrefix("W/") == tag)
}

/** Pekko's `redirect`: its status, its location, and its one line of HTML. */
private fun redirect(status: Int, location: String): Strict {
    val reason = if (status == MOVED_PERMANENTLY) "This and all future requests should be directed to"
    else "The requested resource temporarily resides under"
    val body = """$reason <a href="$location">this URI</a>."""
    return Strict(status, HTML, body.toByteArray(), listOf(LOCATION to location))
}

/** By extension, as Pekko's default `ContentTypeResolver` types a resource: its media types and charsets. */
internal fun pageContentType(resource: String): String {
    val name = resource.substringAfterLast('/')
    val extension = if ('.' in name) name.substringAfterLast('.').lowercase() else ""
    return CONTENT_TYPES[extension] ?: "application/octet-stream"
}

private const val HTML = "text/html; charset=UTF-8"
private const val TEXT = "text/plain; charset=UTF-8"

private val CONTENT_TYPES = mapOf(
    "html" to HTML,
    "htm" to HTML,
    "js" to "application/javascript; charset=UTF-8",
    "mjs" to "application/javascript; charset=UTF-8",
    "css" to "text/css; charset=UTF-8",
    "txt" to TEXT,
    "md" to "text/markdown; charset=UTF-8",
    "csv" to "text/csv; charset=UTF-8",
    "xml" to "text/xml; charset=UTF-8",
    "json" to "application/json",
    "svg" to "image/svg+xml",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "ico" to "image/x-icon",
    "woff" to "font/woff",
    "woff2" to "font/woff2",
    "otf" to "application/x-font-opentype",
    "ttf" to "application/x-font-truetype",
    "pdf" to "application/pdf",
    "zip" to "application/zip",
)

private const val CACHE_CONTROL = "Cache-Control"
private const val ETAG = "ETag"
private const val LAST_MODIFIED = "Last-Modified"
private const val LOCATION = "Location"
private const val IF_MATCH = "If-Match"
private const val IF_NONE_MATCH = "If-None-Match"
private const val IF_MODIFIED_SINCE = "If-Modified-Since"
private const val IF_UNMODIFIED_SINCE = "If-Unmodified-Since"

private const val OK = 200
private const val MOVED_PERMANENTLY = 301
private const val FOUND = 302
private const val NOT_MODIFIED = 304
private const val PRECONDITION_FAILED = 412
private val PRECONDITION_FAILED_TEXT =
    "The server does not meet one of the preconditions that the requester put on the request.".toByteArray()
