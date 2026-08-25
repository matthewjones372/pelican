package io.github.matthewjones372.pelican

/**
 * Nothing this endpoint produces is something the caller will accept.
 *
 * Raised before the handler runs, so a handler that charges a card does not do
 * it for a response nobody will read. [produced] goes in the body, which RFC
 * 9110 allows and is the caller's only way to find out.
 */
class NotAcceptable(val produced: Set<String>) : RuntimeException(
    "This endpoint produces ${produced.joinToString()}, and the request's Accept header takes none of them",
)

/**
 * Whether a caller sending these `Accept` field lines would take any of
 * [produced]. Absent, empty or unparseable means yes — the header is often set
 * by a proxy or an SDK rather than by the caller. Empty [produced] — a 204 —
 * has nothing to negotiate.
 */
fun acceptable(accept: List<String>, produced: Set<String>): Boolean {
    if (produced.isEmpty()) return true

    val ranges = accept.flatMap { it.split(',') }.mapNotNull(::parseRange)
    if (ranges.isEmpty()) return true

    return produced.any { type -> qualityOf(type, ranges) > 0.0 }
}

/**
 * One entry of an `Accept` header. [specificity] is RFC 9110 §12.5.1's most
 * specific reference — exact beats `type/&#42;` beats wildcard — and decides
 * which range supplies the quality.
 */
private class AcceptRange(val main: String, val sub: String, val quality: Double) {
    val specificity: Int = when {
        main == "*" -> 0
        sub == "*" -> 1
        else -> 2
    }

    fun matches(mainType: String, subType: String): Boolean =
        (main == "*" || main.equals(mainType, ignoreCase = true)) &&
            (sub == "*" || sub.equals(subType, ignoreCase = true))
}

/**
 * The `q` of the most specific range matching [mediaType], or zero. Ties take
 * the highest quality, so naming a range twice cannot lower it.
 */
private fun qualityOf(mediaType: String, ranges: List<AcceptRange>): Double {
    val slash = mediaType.indexOf('/')
    val mainType = if (slash < 0) mediaType else mediaType.substring(0, slash)
    val subType = if (slash < 0) "" else mediaType.substring(slash + 1).substringBefore(';').trim()

    return ranges.asSequence()
        .filter { it.matches(mainType, subType) }
        .maxWithOrNull(compareBy({ it.specificity }, { it.quality }))
        ?.quality
        ?: 0.0
}

/** Null for an entry that is not a media range at all; such an entry is ignored. */
private fun parseRange(entry: String): AcceptRange? {
    val parts = entry.split(';')
    val range = parts[0].trim()
    if (range.isEmpty()) return null

    val slash = range.indexOf('/')
    if (slash <= 0 || slash == range.length - 1) return null

    val quality = parts.asSequence()
        .drop(1)
        .map { it.trim() }
        .firstOrNull { it.startsWith("q=", ignoreCase = true) }
        ?.substring(2)
        ?.trim()
        ?.toDoubleOrNull()
        ?: 1.0

    return AcceptRange(range.substring(0, slash), range.substring(slash + 1), quality.coerceIn(0.0, 1.0))
}
