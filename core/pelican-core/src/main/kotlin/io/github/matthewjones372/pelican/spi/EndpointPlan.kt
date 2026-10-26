package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.Cookies
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.MultipartBody
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.QueryParam
import io.github.matthewjones372.pelican.checkFormEncoded
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.AbstractMap

/**
 * One endpoint's inputs, compiled once: a slot for every key a request of it can carry, and the plain inputs read
 * as `pelican-pekko` reads them, the query in one pass.
 */
class EndpointPlan internal constructor(val endpoint: Endpoint<*, *>) {

    /** In the order Pekko decodes them: captures, the caller, the plain inputs, then the body. */
    internal val keys: Array<ParamKey<*>> = buildList {
        addAll(endpoint.pathSpec.captures)
        endpoint.caller?.let(::add)
        addAll(endpoint.queries)
        addAll(endpoint.headerParams)
        addAll(endpoint.cookieParams)
        endpoint.bodyInput?.let { body ->
            add(body)
            if (body is MultipartBody) addAll(body.parts)
        }
    }.toTypedArray()

    private val queries: List<QueryParam<*>> = endpoint.queries
    private val firstQuery = endpoint.pathSpec.captures.size + if (endpoint.caller == null) 0 else 1
    private val firstHeader = firstQuery + queries.size
    private val firstCookie = firstHeader + endpoint.headerParams.size

    /** Where one request's values go: a slot per key, none of them set. */
    fun slots(): Slots = Slots(this)

    /** [key]'s slot, or -1 for a key this endpoint never declared. A scan: an endpoint declares a handful. */
    internal fun slotOf(key: Any?): Int {
        for (i in keys.indices) if (keys[i] === key) return i
        return -1
    }

    /** Query, header and cookie inputs, in declaration order; the captures and the caller are already in [into]. */
    fun decodePlainInputs(view: RequestView, into: Slots) {
        if (queries.isNotEmpty()) decodeQueries(view.rawQuery.orEmpty(), into)

        endpoint.headerParams.forEachIndexed { i, h ->
            val raw = view.header(h.name)
            into.setAt(
                firstHeader + i,
                when {
                    // RFC 9110 reads two field lines under one name as one joined field, so a list reads every line.
                    h.listStyle != null -> h.decodeList(view.headers(h.name))

                    raw != null -> h.codec.decode(h.name, raw)

                    h.required -> throw ApiException(400, "Missing required header '${h.name}'")

                    else -> h.default
                },
            )
        }

        if (endpoint.cookieParams.isEmpty()) return
        val cookies = Cookies.parseAll(view.headers("Cookie"))
        endpoint.cookieParams.forEachIndexed { i, c ->
            val raw = cookies[c.name]?.first()
            into.setAt(
                firstCookie + i,
                when {
                    c.listStyle != null -> c.decodeList(cookies[c.name].orEmpty())
                    raw != null -> c.codec.decode(c.name, raw)
                    c.required -> throw ApiException(400, "Missing required cookie '${c.name}'")
                    else -> c.default
                },
            )
        }
    }

    /**
     * The query decoded as a form, where `+` is a space. One pass checks every pair and keeps each single value's
     * first occurrence, still text; then each is decoded in declaration order, so the first refusal is the same one.
     */
    private fun decodeQueries(query: String, into: Slots) {
        forEachPair(query) { start, equals, end ->
            // Every pair, declared or not: an illegal escape or bytes not UTF-8 anywhere in the query is a 400.
            checkPair(query, start, equals, end)
            val q = queries.indexOfFirst { it.listStyle == null && nameIs(query, start, equals, it.name) }
            if (q >= 0 && !into.isSet(firstQuery + q)) into.setAt(firstQuery + q, formDecoded(query, equals + 1, end))
        }
        queries.forEachIndexed { i, q ->
            val slot = firstQuery + i
            into.setAt(
                slot,
                when {
                    q.listStyle != null -> q.decodeList(valuesNamed(query, q.name))
                    into.isSet(slot) -> q.codec.decode(q.name, into.at(slot) as String)
                    q.required -> throw ApiException(400, "Missing required query parameter '${q.name}'")
                    else -> q.default
                },
            )
        }
    }
}

/** What a request of this endpoint is decoded through; built once, where the interpreter is. */
fun Endpoint<*, *>.plan(): EndpointPlan = EndpointPlan(this)

/**
 * One request's decoded inputs, a slot per key its [EndpointPlan] declares. A map, so `Params` and the decoders that
 * already write into one take it as they are; a key with no slot reads as absent, so `Params.get` still throws for
 * a key the endpoint never declared, and writing one is a bug.
 */
class Slots internal constructor(private val plan: EndpointPlan) : AbstractMutableMap<ParamKey<*>, Any?>() {
    private val cells: Array<Any?> = arrayOfNulls<Any?>(plan.keys.size).apply { fill(Unset) }

    internal fun isSet(slot: Int): Boolean = cells[slot] !== Unset

    internal fun at(slot: Int): Any? = cells[slot]

    internal fun setAt(slot: Int, value: Any?) {
        cells[slot] = value
    }

    override fun containsKey(key: ParamKey<*>): Boolean = plan.slotOf(key).let { it >= 0 && isSet(it) }

    override fun get(key: ParamKey<*>): Any? = plan.slotOf(key).let { if (it >= 0 && isSet(it)) cells[it] else null }

    override fun put(key: ParamKey<*>, value: Any?): Any? {
        val slot = plan.slotOf(key)
        check(slot >= 0) { "$key has no slot: ${plan.endpoint} never declared it" }
        return at(slot).takeUnless { it === Unset }.also { setAt(slot, value) }
    }

    override fun remove(key: ParamKey<*>): Any? {
        val slot = plan.slotOf(key)
        return if (slot < 0) null else at(slot).takeUnless { it === Unset }.also { setAt(slot, Unset) }
    }

    override val size: Int get() = cells.count { it !== Unset }

    /** A copy, in declaration order. Only `lensInputs` walks the bag, and no request does. */
    override val entries: MutableSet<MutableMap.MutableEntry<ParamKey<*>, Any?>>
        get() = plan.keys.indices.filter(::isSet)
            .mapTo(LinkedHashSet()) { AbstractMap.SimpleEntry(plan.keys[it], cells[it]) }

    private object Unset
}

/** Each non-empty `&`-separated pair of [query]: where it starts, its first `=` (or its end), and its end. */
private inline fun forEachPair(query: String, pair: (start: Int, equals: Int, end: Int) -> Unit) {
    var start = 0
    while (start <= query.length) {
        val end = query.indexOf('&', start).let { if (it < 0) query.length else it }
        if (end > start) {
            var equals = start
            while (equals < end && query[equals] != '=') equals++
            pair(start, equals, end)
        }
        start = end + 1
    }
}

/** Every value carried under [name], decoded, in arrival order. */
private fun valuesNamed(query: String, name: String): List<String> = buildList {
    forEachPair(query) { start, equals, end ->
        if (nameIs(query, start, equals, name)) add(formDecoded(query, minOf(equals + 1, end), end))
    }
}

/** Whether `query[start, end)` decodes to [name]; compared in place unless it carries something to decode. */
private fun nameIs(query: String, start: Int, end: Int, name: String): Boolean =
    if (needsDecoding(query, start, end)) formDecoded(query, start, end) == name
    else name.length == end - start && query.regionMatches(start, name, 0, name.length)

private fun formDecoded(query: String, start: Int, end: Int): String {
    if (start >= end) return ""
    val raw = query.substring(start, end)
    return if (needsDecoding(query, start, end)) URLDecoder.decode(raw, StandardCharsets.UTF_8) else raw
}

private fun needsDecoding(query: String, start: Int, end: Int): Boolean {
    for (i in start until end) if (query[i] == '%' || query[i] == '+') return true
    return false
}

/**
 * Throws the 400 a query that will not decode strictly is refused with, for the in-memory client, which reads no
 * plan: an illegal escape is `URLDecoder`'s detail, and bytes that are not UTF-8 one fixed sentence.
 */
internal fun refuseIllegalQuery(query: String) {
    forEachPair(query) { start, equals, end -> checkPair(query, start, equals, end) }
}

private fun checkPair(query: String, start: Int, equals: Int, end: Int) {
    try {
        checkFormEncoded(query, start, equals)
        checkFormEncoded(query, minOf(equals + 1, end), end)
    } catch (e: IllegalArgumentException) {
        throw ApiException(400, "Illegal query string", e.message, cause = e)
    }
}
