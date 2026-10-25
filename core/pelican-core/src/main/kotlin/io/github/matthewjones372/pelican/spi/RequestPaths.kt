package io.github.matthewjones372.pelican.spi

/**
 * RFC 3986's remove_dot_segments over a raw path, reading `%2E` as the `.` it spells as Pekko does, so no `.` or
 * `..` reaches a [RouteIndex] as a value: a handler using a path param as a name is never handed one. Every other
 * escape is left for the index. Null when a `..` would climb above the root. A path with no dot segment, which is
 * nearly every one, comes back as itself, having been scanned and not split.
 */
fun withoutDotSegments(path: String): String? {
    if (!path.startsWith('/') || !hasDotSegment(path)) return path
    val segments = path.split('/').drop(1)
    val kept = segments.foldIndexed(emptyList<String>()) { i, acc, segment ->
        // A dot segment last leaves the path ending in a slash, as RFC 3986 resolves `/a/b/..` to `/a/`.
        val trailing = if (i == segments.lastIndex) listOf("") else emptyList()
        when (dots(segment, 0, segment.length)) {
            1 -> acc + trailing
            2 -> if (acc.isEmpty()) return null else acc.dropLast(1) + trailing
            else -> acc + segment
        }
    }
    return "/" + kept.joinToString("/")
}

private fun hasDotSegment(path: String): Boolean {
    var start = 1
    while (start <= path.length) {
        val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
        if (dots(path, start, end) > 0) return true
        start = end + 1
    }
    return false
}

/** 1 for `.` and 2 for `..` in `text[start, end)`, either dot spelled `%2E` or not; 0 for any other segment. */
private fun dots(text: String, start: Int, end: Int): Int {
    var at = start
    var count = 0
    while (at < end && count < 2) {
        at += when {
            text[at] == '.' -> 1
            text.regionMatches(at, ENCODED_DOT, 0, ENCODED_DOT.length, ignoreCase = true) -> ENCODED_DOT.length
            else -> return 0
        }
        count++
    }
    return if (at == end) count else 0
}

private const val ENCODED_DOT = "%2E"
