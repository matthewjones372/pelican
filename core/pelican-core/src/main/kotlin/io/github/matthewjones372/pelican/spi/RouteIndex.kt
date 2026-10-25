package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.DecodedSegment
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.PathSegment
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.decodeSegment
// File-level rather than in a companion: `const` in a private companion is
// still a public static field on the class. `String.hashCode`'s, which the
// literal table is sorted by.
private const val HASH_MULTIPLIER = 31

/**
 * The endpoint a request reaches, found without trying the others.
 *
 * Both http4k's `routes(...)` and Pekko's `Directives.concat` are ordered
 * scans: given a list they try each in turn, because a handler is opaque and
 * there is nothing else they could do. At two hundred endpoints that measured
 * about 150µs a request — the same registered by hand, so it is what a scan
 * costs rather than what interpreting costs.
 *
 * A description is not opaque. Every path template is known before a request
 * arrives, so they can be walked into a trie once and matched by segment
 * afterwards, which is what this is. The cost stops depending on how many
 * endpoints a service has.
 *
 * It answers nothing. A path nobody described comes back null, and the
 * interpreter declines in its own backend's vocabulary — a Pekko rejection, an
 * http4k no-match — so a Pelican route concatenated with hand-written ones
 * still passes on what it does not describe, and each backend keeps the 404 or
 * 405 it gives today.
 */
class RouteIndex internal constructor(private val root: Node) {

    /**
     * The endpoint for this method and path, or null where nothing describes
     * it. Captured segments are written into [into] only when a match is
     * found, so a walk that backtracks leaves nothing behind.
     *
     * [path] is the **raw** request path, as it arrived on the request line:
     * segments are found by `/` before anything is decoded, so `%2F` stays
     * inside the segment that carried it rather than becoming a separator.
     * The path is read in place, and a match with no captures allocates
     * nothing.
     */
    fun match(method: Method, path: String, into: MutableMap<ParamKey<*>, Any?>): ServerEndpoint? {
        val found = walk(root, method, path, at = 0) ?: return null

        // Read off the endpoint that matched, not the node that captured: two
        // endpoints may name one position differently — `/users/{userId}`
        // beside `/users/{name}/posts` — and each handler reads the key it
        // declared. Segment i of the path is segment i of its template.
        val segments = found.endpoint.pathSpec.segments
        var at = 0
        for (i in segments.indices) {
            val start = skipSlashes(path, at)
            at = segmentEnd(path, start)
            val segment = segments[i]
            if (segment is PathSegment.Capture) {
                into[segment.param] = segment.param.codec.decode(segment.param.name, decoded(path, start, at))
            }
        }
        return found
    }

    /**
     * The endpoint this method and path reach, decoding nothing.
     *
     * For the refusal path alone. A *capture* is decoded inside [match], which
     * knows the endpoint it matched and throws to a caller that does not — so
     * a path parameter that will not decode would be reported as a request
     * nobody described, and counted under `_unmatched` rather than against the
     * route it was aimed at. A query, header or cookie is decoded after the
     * route is already in hand and never had the problem.
     *
     * Total by construction: this is called from a `catch`, and a path that is
     * malformed rather than merely undecodable — `/things/%zz` — throws out of
     * the walk itself. A second throw there would replace the refusal being
     * rendered with one nobody is catching, so the answer is "no route to
     * name" and the original refusal goes out as it would have.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun routeFor(method: Method, path: String): ServerEndpoint? =
        try {
            walk(root, method, path, at = 0)
        } catch (_: Throwable) {
            null
        }

    /**
     * Whether any method describes this path.
     *
     * For the backend that separates "no such path" from "not that method" and
     * answers each differently. Walked only when [match] has already come back
     * empty, which is a request that is going to be refused either way.
     */
    fun describesPath(path: String): Boolean =
        Method.entries.any { method -> walk(root, method, path, at = 0) != null }

    /**
     * One segment at a time, literals before the capture.
     *
     * A literal that matches can still fail further down — `/orders/watch`
     * beside `/orders/{id}/items` — so a failed descent falls through to the
     * capture rather than giving up.
     *
     * A segment with an escape in it is decoded before either comparison: a
     * literal and a capture then answer the same question about the same text,
     * so `/%61dmin` reaches the `admin` route rather than only the one that
     * captures. One without is compared where it lies.
     */
    private fun walk(node: Node, method: Method, path: String, at: Int): ServerEndpoint? {
        val start = skipSlashes(path, at)
        if (start >= path.length) return node.endpoints[method]

        val end = segmentEnd(path, start)
        val literal = if (hasEscape(path, start, end)) {
            decoded(path, start, end).let { node.literal(it, 0, it.length) }
        } else {
            node.literal(path, start, end)
        }
        val byLiteral = literal?.let { walk(it, method, path, end) }
        if (byLiteral != null) return byLiteral

        return node.capture?.let { walk(it, method, path, end) }
    }

    private fun segmentEnd(path: String, start: Int): Int =
        path.indexOf('/', start).let { if (it < 0) path.length else it }

    private fun hasEscape(path: String, start: Int, end: Int): Boolean {
        for (i in start until end) if (path[i] == '%') return true
        return false
    }

    private fun decoded(path: String, start: Int, end: Int): String {
        val raw = path.substring(start, end)
        return when (val decoded = decodeSegment(raw)) {
            is DecodedSegment.Ok -> decoded.text
            DecodedSegment.Malformed -> throw malformed(raw)
        }
    }

    /** Where the next segment starts: `//a` and `/a` name the same path. */
    private fun skipSlashes(path: String, from: Int): Int {
        var at = from
        while (at < path.length && path[at] == '/') at++
        return at
    }

    /** One node of the trie: where a literal goes, where a capture goes, what ends here. */
    internal class Node(
        literals: Map<String, Node>,
        /** Where any captured segment goes, whatever the endpoints beneath call it. */
        val capture: Node?,
        val endpoints: Map<Method, ServerEndpoint>,
    ) {
        // Sorted by `String.hashCode`, so a segment still lying in the path is
        // looked up by hashing it there rather than by cutting it out first.
        private val names: Array<String> = literals.keys.sortedBy { it.hashCode() }.toTypedArray()
        private val hashes: IntArray = IntArray(names.size) { names[it].hashCode() }
        private val children: Array<Node> = Array(names.size) { literals.getValue(names[it]) }

        /** The child for the literal `text[start, end)`, or null. */
        fun literal(text: String, start: Int, end: Int): Node? {
            var hash = 0
            for (i in start until end) hash = HASH_MULTIPLIER * hash + text[i].code
            var i = hashes.binarySearch(hash)
            if (i < 0) return null
            while (i > 0 && hashes[i - 1] == hash) i--
            while (i < names.size && hashes[i] == hash) {
                val name = names[i]
                if (name.length == end - start && text.regionMatches(start, name, 0, name.length)) return children[i]
                i++
            }
            return null
        }
    }

    private companion object {
        /**
         * A refusal rather than a 404: a request line nobody could have written
         * is the caller's mistake, and answering "no such path" would send them
         * looking for a path that is not the problem.
         */
        fun malformed(raw: String) = ApiException(
            400,
            "Malformed request path",
            "'$raw' is not a valid percent-encoded path segment",
        )
    }
}

/**
 * The trie these endpoints match through, built once.
 *
 * Refuses nothing that [Api] does not already refuse: two endpoints on one
 * method and path are a clash `Api` rejects at construction, so a node holds
 * at most one endpoint per method by the time this runs.
 */
fun List<ServerEndpoint>.routeIndex(): RouteIndex = RouteIndex(node(this, depth = 0))

private fun node(endpoints: List<ServerEndpoint>, depth: Int): RouteIndex.Node {
    val (ending, continuing) = endpoints.partition { it.endpoint.pathSpec.segments.size == depth }

    val literals = continuing
        .filter { it.endpoint.pathSpec.segments[depth] is PathSegment.Literal }
        .groupBy { (it.endpoint.pathSpec.segments[depth] as PathSegment.Literal).value }
        .mapValues { (_, group) -> node(group, depth + 1) }

    // One subtree for every endpoint capturing here, whatever each calls it: a
    // request carries a segment, not a name, and the name is the matched
    // endpoint's to supply afterwards.
    val capture = continuing
        .filter { it.endpoint.pathSpec.segments[depth] is PathSegment.Capture }
        .takeIf { it.isNotEmpty() }
        ?.let { node(it, depth + 1) }

    return RouteIndex.Node(literals, capture, ending.associateBy { it.endpoint.method })
}
