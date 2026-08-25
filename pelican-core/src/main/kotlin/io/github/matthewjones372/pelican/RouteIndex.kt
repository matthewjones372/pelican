package io.github.matthewjones372.pelican

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
     */
    fun match(method: Method, path: String, into: MutableMap<ParamKey<*>, Any?>): ServerEndpoint? {
        val captured = ArrayList<String>(INITIAL_CAPTURES)
        val found = walk(root, method, path, at = 0, captured = captured) ?: return null

        // Zipped against the endpoint that matched, not against the node that
        // captured. Two endpoints may name one position differently —
        // `/users/{userId}` beside `/users/{name}/posts` — and each handler
        // reads the key it declared.
        //
        // Decoded here rather than during the walk: a branch that is abandoned
        // should not have paid for it.
        found.endpoint.pathSpec.captures.forEachIndexed { i, param ->
            into[param] = param.codec.decode(param.name, decode(captured[i]))
        }
        return found
    }

    /**
     * Whether any method describes this path.
     *
     * For the backend that separates "no such path" from "not that method" and
     * answers each differently. Walked only when [match] has already come back
     * empty, which is a request that is going to be refused either way.
     */
    fun describesPath(path: String): Boolean =
        Method.entries.any { method ->
            walk(root, method, path, at = 0, captured = ArrayList(INITIAL_CAPTURES)) != null
        }

    /**
     * One segment at a time, literals before the capture.
     *
     * A literal that matches can still fail further down — `/orders/watch`
     * beside `/orders/{id}/items` — so a failed descent falls through to the
     * capture rather than giving up, and anything the failed branch captured is
     * dropped with it.
     */
    private fun walk(
        node: Node,
        method: Method,
        path: String,
        at: Int,
        captured: MutableList<String>,
    ): ServerEndpoint? {
        val start = skipSlashes(path, at)
        if (start >= path.length) return node.endpoints[method]

        val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
        val segment = path.substring(start, end)

        val byLiteral = node.literals[segment]?.let { walk(it, method, path, end, captured) }
        if (byLiteral != null) return byLiteral

        return node.capture?.let { capture -> byCapture(capture, method, path, end, segment, captured) }
    }

    /** The capture branch, with what it wrote unwound if it comes to nothing. */
    @Suppress("LongParameterList") // One walk's state, threaded rather than held.
    private fun byCapture(
        capture: Node,
        method: Method,
        path: String,
        end: Int,
        segment: String,
        captured: MutableList<String>,
    ): ServerEndpoint? {
        val depth = captured.size
        captured.add(segment)
        val found = walk(capture, method, path, end, captured)
        if (found == null) while (captured.size > depth) captured.removeAt(captured.size - 1)
        return found
    }

    /** Where the next segment starts: `//a` and `/a` name the same path. */
    private fun skipSlashes(path: String, from: Int): Int {
        var at = from
        while (at < path.length && path[at] == '/') at++
        return at
    }

    /** One node of the trie: where a literal goes, where a capture goes, what ends here. */
    internal class Node(
        val literals: Map<String, Node>,
        /** Where any captured segment goes, whatever the endpoints beneath call it. */
        val capture: Node?,
        val endpoints: Map<Method, ServerEndpoint>,
    )

    private companion object {
        const val INITIAL_CAPTURES = 4

        /** Most segments have nothing in them to decode, and decoding allocates. */
        fun decode(raw: String): String =
            if (raw.any { it == '%' || it == '+' }) URLDecoder.decode(raw, StandardCharsets.UTF_8) else raw
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
