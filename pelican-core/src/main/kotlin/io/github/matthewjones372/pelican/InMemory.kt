package io.github.matthewjones372.pelican

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The API a generated client calls, called in memory: no socket, no port, no
 * bind.
 *
 * Not a stand-in for the server. Routing, input decoding, the filter chain, the
 * handler and the response building are the ones a bound server runs, because
 * they are the same functions reading the same descriptions — which is what
 * makes a test written against this a test of the service rather than of a
 * mock. A generated client takes it where it takes any other transport:
 *
 * ```
 * val client = OrdersClient(transport = InMemoryClientTransport(api))
 * ```
 *
 * Two things a backend owns cannot cross, because core has no name for them,
 * and both are refused by name rather than by class cast: a `bytes(...)`
 * request body, whose handle is the backend's own type, and a streamed response
 * a handler produced as something other than a `Sequence` — Pekko's `Source`,
 * Ktor's `Flow`. An http4k-bound API streams as a `Sequence` and crosses whole.
 */
class InMemoryClientTransport(private val api: Api) : ClientTransport {

    private val index = api.endpoints.routeIndex()

    /** Folded once when the transport is built, as a route-building server folds them. */
    private val handlers = api.endpoints.associateWith { api.handlerFor(it) }

    /** Resolved once, likewise: a broken codec is a construction failure, not a request's. */
    private val codecs = api.endpoints.associateWith { it.endpoint.resolveCodecs(api.codecs) }

    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val (path, query) = target(request.url)

        // The per-request bag the index writes captures into and the handler
        // reads its inputs from, exactly as each interpreter's.
        val values = LinkedHashMap<ParamKey<*>, Any?>()
        val matched = try {
            index.match(request.method, path, values)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // A capture the trie will not decode is that endpoint's 400 rather
            // than a path nobody described, which is the distinction the
            // backends draw at the same point.
            return completed(errorResponse(t, null))
        } ?: return completed(errorResponse(unrouted(request.method, path, index.describesPath(path)), null))

        val endpoint = matched.endpoint
        refuseWhatCannotCross(endpoint)

        val params = Params(values, request, endpoint)
        val resolved = codecs.getValue(matched)

        return try {
            negotiate(endpoint, request)
            decodeInputs(endpoint, request, query, values)
            decodeBody(endpoint, request, resolved, values)

            // A filter that rejects throws where it stands rather than failing a
            // stage, so the call itself is inside the try as well as its answer.
            handlers.getValue(matched)(params).handle { value, failure ->
                if (failure != null) errorResponse(failure, endpoint).withHeaders(params)
                else respond(endpoint.output, value, resolved).withHeaders(params)
            }
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // As each interpreter does: whatever was raised becomes the response
            // core's own table gives it.
            completed(errorResponse(t, endpoint).withHeaders(params))
        }
    }

    /**
     * What this transport will not pretend to serve, said before the handler
     * runs rather than as the `ClassCastException` a backend's own accessor
     * would raise a moment later.
     */
    private fun refuseWhatCannotCross(endpoint: Endpoint<*, *>) {
        if (endpoint.bodyInput is RawBody) {
            throw UnsupportedInMemoryCall(
                "$endpoint takes a bytes( ) request body, and the handle a handler reads it through belongs " +
                    "to whichever backend bound it — there is no core value to hand over. Call this one " +
                    "against a bound server.",
            )
        }
    }

    private fun negotiate(endpoint: Endpoint<*, *>, request: ClientRequest) {
        val produced = endpoint.output.produces
        if (produced.isEmpty()) return
        val accept = request.headers.filter { it.first.equals("Accept", ignoreCase = true) }.map { it.second }
        if (accept.isNotEmpty() && !acceptable(accept, produced)) throw NotAcceptable(produced)
    }

    private fun decodeInputs(
        endpoint: Endpoint<*, *>,
        request: ClientRequest,
        search: String,
        into: MutableMap<ParamKey<*>, Any?>,
    ) {
        val query = queryValues(search)
        endpoint.queries.forEach { q ->
            val wire = query[q.name].orEmpty()
            into[q] =
                if (q.listStyle != null) q.decodeList(wire)
                else single(wire.firstOrNull(), q.name, "query parameter", q.required, q.default, q.codec)
        }

        endpoint.headerParams.forEach { h ->
            val wire = request.headers.filter { it.first.equals(h.name, ignoreCase = true) }.map { it.second }
            into[h] =
                if (h.listStyle != null) h.decodeList(wire)
                else single(wire.firstOrNull(), h.name, "header", h.required, h.default, h.codec)
        }

        if (endpoint.cookieParams.isEmpty()) return
        val jar = Cookies.parseAll(
            request.headers.filter { it.first.equals("Cookie", ignoreCase = true) }.map { it.second },
        )
        endpoint.cookieParams.forEach { c ->
            val wire = jar[c.name].orEmpty()
            into[c] =
                if (c.listStyle != null) c.decodeList(wire)
                else single(wire.firstOrNull(), c.name, "cookie", c.required, c.default, c.codec)
        }
    }

    private fun decodeBody(
        endpoint: Endpoint<*, *>,
        request: ClientRequest,
        resolved: ResolvedCodecs,
        into: MutableMap<ParamKey<*>, Any?>,
    ) {
        when (val body = endpoint.bodyInput) {
            null -> Unit

            // Refused above, before the handler was reached.
            is RawBody -> Unit

            is MultipartBody -> body.decode(
                contentType = request.header("Content-Type"),
                input = request.body.stream(),
                maxInMemoryBytes = api.maxBodyBytes,
                into = into,
            )

            is JsonBody<*>, is FormBody<*>, is NegotiatedBody<*> -> {
                val text = readStrictBody(request.body.stream(), api.maxBodyBytes)
                into[body] = checkNotNull(resolved.body) { "No codec was resolved for the body of $endpoint" }
                    .decode(request.header("Content-Type"), text)
            }
        }
    }

    /**
     * The described response, rendered. The status, the media type and the
     * codec all come from the declaration the handler named, which is what the
     * three interpreters do with the same values.
     */
    @Suppress("UNCHECKED_CAST")
    private fun respond(out: Output<*>, value: Any?, resolved: ResolvedCodecs): ClientResponse {
        fun payload(): BodyCodec<Any?> =
            checkNotNull(resolved.payloadFor(out)) { "No codec was resolved for $out" }

        if (out is FallibleOutput<*, *>) {
            return when (val outcome = value as Outcome<*, *>) {
                is Outcome.Ok<*> ->
                    respond(out.successNamedBy(outcome), outcome.value, resolved).plus(outcome.headers)

                is Outcome.Err<*> -> {
                    val declared = out.failureNamedBy(outcome)
                    val codec = checkNotNull(resolved.alternatives[declared]) { "No codec for $declared" }
                    body(declared.status, "application/json", codec.encodeToString(outcome.error))
                        .plus(outcome.headers)
                }
            }
        }

        return when (out) {
            is JsonOutput<*> -> body(out.status, "application/json", payload().encodeToString(value))

            is TextOutput -> body(out.status, "text/plain; charset=utf-8", value as String)

            is EmptyOutput -> ClientResponse(out.status, emptyList(), empty())

            is NdjsonOutput<*> -> {
                val o = out as NdjsonOutput<Any?>
                val codec = payload()
                streamed(out.status, out.mediaType, elements(value, out).map { o.frame(codec, it) })
            }

            is SseOutput<*> -> {
                val o = out as SseOutput<Any?>
                val codec = payload()
                streamed(out.status, out.mediaType, elements(value, out).map { o.frame(codec, it) })
            }

            is JsonArrayOutput<*> ->
                streamed(out.status, out.mediaType, jsonArrayFrames(elements(value, out), payload()))

            // Handed over as it stands; whoever takes the response closes it.
            is ByteStreamOutput ->
                ClientResponse(out.status, listOf(CONTENT_TYPE to out.mediaType), value as InputStream)

            // Handled above, before any payload was touched.
            is FallibleOutput<*, *> -> error("Unreachable")
        }
    }

    /**
     * Rendered through core's own table, so a request refused here is refused
     * in the same words a bound server would use. An unexpected failure reaches
     * [Api.onServerError] where one is set; core has no logger to fall back on,
     * so where none is set it is reported by the reference in the body alone.
     */
    private fun errorResponse(raw: Throwable, endpoint: Endpoint<*, *>?): ClientResponse {
        val rendered = renderError(raw, api.exposeInternalErrors)
        rendered.unexpected?.let { failure ->
            val reference = checkNotNull(rendered.reference) { "an unexpected failure with no reference" }
            api.onServerError?.invoke(reference, endpoint, failure)
        }
        return body(rendered.error.status, "application/json", rendered.error.toJson().render())
            .plus(rendered.headers)
    }
}

/** Raised where an in-memory call needs something only a backend can supply. */
class UnsupportedInMemoryCall(message: String) : RuntimeException(message)

// -------------------------------------------------------------------- pieces

private const val CONTENT_TYPE = "Content-Type"

/**
 * The codecs one endpoint needs, resolved ahead of any request — the same
 * shape, and for the same reason, as each backend's own resolution.
 */
private class ResolvedCodecs(
    val body: RequestBodyCodecs?,
    val payload: BodyCodec<Any?>?,
    /** Keyed by identity: two declared responses can carry one payload type. */
    val alternatives: Map<Any, BodyCodec<Any?>>,
) {
    fun payloadFor(out: Output<*>): BodyCodec<Any?>? = alternatives[out] ?: payload
}

private fun Endpoint<*, *>.resolveCodecs(codecs: Codecs): ResolvedCodecs = ResolvedCodecs(
    body = codecs.requestBodyCodec(bodyInput),
    payload = output.payloadType?.let { codecs.codec(it) },
    alternatives = (output as? FallibleOutput<*, *>)?.let { declared ->
        (
            declared.successes.mapNotNull { s -> s.payloadType?.let { s as Any to codecs.codec<Any?>(it) } } +
                declared.failures.map { f -> f as Any to codecs.codec<Any?>(f.type) }
            ).associateTo(IdentityHashMap<Any, BodyCodec<Any?>>()) { it }
    }.orEmpty(),
)

/**
 * What a request that matched nothing is answered with. A path some other
 * method describes is a 405 and anything else a 404 — the distinction each
 * backend's router makes, made here because there is no router underneath to
 * decline to.
 */
private fun unrouted(method: Method, path: String, described: Boolean): ApiException =
    if (described) ApiException(405, "Method not allowed", "$method $path")
    else ApiException(404, "Not found", "$method $path")

private fun completed(response: ClientResponse): CompletionStage<ClientResponse> =
    CompletableFuture.completedStage(response)

private fun empty(): InputStream = ByteArrayInputStream(ByteArray(0))

private fun body(status: Int, contentType: String, text: String): ClientResponse = ClientResponse(
    status,
    listOf(CONTENT_TYPE to contentType),
    ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)),
)

private fun streamed(status: Int, contentType: String?, frames: Sequence<String>): ClientResponse =
    ClientResponse(status, listOfNotNull(contentType?.let { CONTENT_TYPE to it }), FrameStream(frames))

/** The same response carrying more headers — a declared response's, or a handler's. */
private fun ClientResponse.plus(extra: List<Pair<String, String>>): ClientResponse =
    if (extra.isEmpty()) this else ClientResponse(status, headers + extra, body)

private fun ClientResponse.withHeaders(params: Params): ClientResponse = plus(params.responseHeaders())

/** The request body as bytes, whichever of the three shapes it was given in. */
private fun ClientRequest.Body.stream(): InputStream = when (this) {
    is ClientRequest.Body.Empty -> empty()
    is ClientRequest.Body.Text -> ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8))
    is ClientRequest.Body.Streaming -> open()
}

/**
 * One value off the wire, or what its declaration says an absent one means.
 * Written once because "present" is the only thing a query parameter, a header
 * and a cookie disagree about, and that disagreement is already resolved by the
 * time this is called.
 */
@Suppress("LongParameterList") // One declaration's facets, from three classes with no common supertype.
private fun single(
    raw: String?,
    name: String,
    noun: String,
    required: Boolean,
    default: Any?,
    codec: PlainCodec<*>,
): Any? = when {
    raw != null -> codec.decode(name, raw)
    required -> throw ApiException(400, "Missing required $noun '$name'")
    else -> default
}

/**
 * The path and the query string of an assembled URL, split without
 * `java.net.URI`.
 *
 * `URI` refuses a malformed escape where it stands, and a segment that will not
 * decode is the 400 the trie raises with the offending segment named — the
 * answer a bound server gives, and one this transport would otherwise replace
 * with a `URISyntaxException` out of `send`.
 */
private fun target(url: String): Pair<String, String> {
    val afterScheme = url.indexOf("://").let { if (it < 0) 0 else it + "://".length }
    val start = url.indexOf('/', afterScheme)
    val rest = (if (start < 0) "/" else url.substring(start)).substringBefore('#')
    return rest.substringBefore('?').ifEmpty { "/" } to rest.substringAfter('?', "")
}

/**
 * The query string as the values each name carries, percent-decoded as a form:
 * a `+` in a query is a space, which is the one place that rule holds.
 */
private fun queryValues(search: String): Map<String, List<String>> =
    search
        .split('&')
        .filter { it.isNotEmpty() }
        .map { pair ->
            val at = pair.indexOf('=')
            if (at < 0) decoded(pair) to "" else decoded(pair.substring(0, at)) to decoded(pair.substring(at + 1))
        }
        .groupBy({ it.first }, { it.second })

private fun decoded(raw: String): String = URLDecoder.decode(raw, StandardCharsets.UTF_8)

/**
 * What a streaming handler produced, as elements. A `Sequence` is what a
 * handler bound by a backend that answers on the calling thread returns; the
 * others hand back their own library's stream, which core cannot read without
 * becoming a dependent of it.
 */
private fun elements(value: Any?, out: Output<*>): Sequence<Any?> = when (value) {
    is Sequence<*> -> value

    is Iterable<*> -> value.asSequence()

    else -> throw UnsupportedInMemoryCall(
        "$out was handed a ${value?.let { it::class.simpleName }}, and this transport can only read a " +
            "stream a handler produced as a Sequence. A Source or a Flow is that backend's own type: call " +
            "this one against a bound server.",
    )
}

/**
 * One JSON array, framed as core leaves it to a backend to frame. The opening
 * bracket travels with the first element, so an empty stream renders `[]` and a
 * first-element failure has not yet committed to an array.
 */
private fun jsonArrayFrames(elements: Sequence<Any?>, codec: BodyCodec<Any?>): Sequence<String> = sequence {
    var seen = false
    for (element in elements) {
        yield((if (seen) "," else "[") + codec.encodeToString(element))
        seen = true
    }
    yield(if (seen) "]" else "[]")
}

/**
 * A body that produces its frames as they are read, which is what keeps a
 * streamed response streamed: one `read` pulls exactly as much of the sequence
 * as it hands back, so a client taking two elements of ten waits for two.
 */
private class FrameStream(frames: Sequence<String>) : InputStream() {
    private val iterator = frames.iterator()
    private var current: ByteArray = ByteArray(0)
    private var position = 0

    /** True while there are bytes to hand over; false at the end of the stream. */
    private fun advance(): Boolean {
        while (position >= current.size) {
            if (!iterator.hasNext()) return false
            current = iterator.next().toByteArray(StandardCharsets.UTF_8)
            position = 0
        }
        return true
    }

    override fun read(): Int = if (!advance()) -1 else current[position++].toInt() and BYTE_MASK

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!advance()) return -1
        val taken = minOf(len, current.size - position)
        System.arraycopy(current, position, b, off, taken)
        position += taken
        return taken
    }

    override fun available(): Int = current.size - position

    override fun close() {
        (iterator as? AutoCloseable)?.close()
    }
}

/** A Kotlin Byte is signed; `InputStream.read` promises 0..255. */
private const val BYTE_MASK = 0xFF
