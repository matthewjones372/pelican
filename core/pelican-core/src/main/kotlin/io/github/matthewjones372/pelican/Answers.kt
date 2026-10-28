package io.github.matthewjones372.pelican

/** What a [ResponseHeaderPolicy] is told about one response as it goes out. */
class Answer internal constructor(
    /** The request's path, as it arrived. */
    val path: String,
    /** The endpoint that answered, or null where none matched or the docs modules, the MCP handler or pages did. */
    val endpoint: Endpoint<*, *>?,
    val status: Int,
    /** Whether the docs modules served it: the document or one of its pages. */
    val isDocs: Boolean,
) {
    override fun toString() = "Answer($status $path)"
}

/** What a `responseHeaders { }` rule writes into, for the one response it was called for. */
class AnswerHeaders internal constructor() {
    private val added = ArrayList<Pair<String, String>>()

    /** Another header under [name]: adding a name twice sends it twice. */
    fun add(name: String, value: String) {
        require(name.isNotBlank()) { "A response header needs a name" }
        require(!isServerOwned(name)) {
            "$name is set by the server from the response itself, so a policy cannot add it."
        }
        added += name to value
    }

    internal fun toList(): List<Pair<String, String>> = added.toList()
}

/**
 * The headers [Api.responseHeaders] puts on every response its backend writes: endpoints, refusals, unmatched
 * routes, the docs modules and MCP. A header the response already carries under a name wins over one added here.
 */
class ResponseHeaderPolicy internal constructor(private val rule: AnswerHeaders.(Answer) -> Unit) {

    /** What [rule] adds for this response, less any name [present] already answers to. */
    fun headersFor(
        path: String,
        endpoint: Endpoint<*, *>?,
        status: Int,
        isDocs: Boolean = false,
        present: (String) -> Boolean,
    ): List<Pair<String, String>> {
        val headers = AnswerHeaders().apply { rule(Answer(path, endpoint, status, isDocs)) }.toList()
        return if (headers.isEmpty()) headers else headers.filterNot { (name, _) -> present(name) }
    }
}
