package dev.pelican.test

import dev.pelican.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType
import kotlin.reflect.typeOf

/** A call that came back with a status outside 2xx. */
class ApiCallFailed(
    val endpoint: Endpoint<*, *>,
    val request: RequestSpec,
    val response: ResponseSpec,
) : RuntimeException("$request -> ${response.status}: ${response.body.take(500)}")

/**
 * A client built out of the endpoint descriptions themselves.
 *
 * ```
 * val app = ordersApi().inMemory()
 *
 * val user: User = app.call(getUser, 1L)
 * val orders: List<Order> = app.collect(streamOrders, In4(1L, 7, null, null))
 * assertEquals(401, app.response(placeOrder, In3(1L, "wrong", CreateOrder("anvil"))).status)
 * ```
 *
 * Two things follow from building the request out of the description rather
 * than out of a string.
 *
 * The first is that it is type-checked: `call(getUser, 1L)` returns `User`
 * because `getUser` is an `Endpoint<Long, User>`, and passing anything but a
 * `Long` does not compile. Rename a path parameter or change an input's type
 * and the *tests* stop compiling, rather than starting to 404.
 *
 * The second is that a passing test is evidence about the documented contract,
 * not just the served one. The path template, the parameter names and the
 * payload types used here are the same values the OpenAPI interpreter reads.
 *
 * The response is decoded with the [Codecs] the API is configured with — the
 * same instance that encoded it — so a green test also proves the codec
 * round-trips.
 */
class ApiClient(
    val transport: Transport,
    val codecs: Codecs,
) : AutoCloseable {

    /** Builds the request an endpoint call would send, without sending it. */
    fun <I> request(endpoint: Endpoint<I, *>, input: I): RequestSpec {
        val values = endpoint.inputs.inject(input)

        val path = "/" + endpoint.pathSpec.segments.joinToString("/") { segment ->
            when (segment) {
                is PathSegment.Literal -> encodeSegment(segment.value)

                is PathSegment.Capture -> {
                    val param = segment.param
                    val value = values[param]
                        ?: error("No value for path parameter '${param.name}' of $endpoint")
                    encodeSegment(encodePlain(param.codec, value))
                }
            }
        }

        // Absent optional inputs are simply not sent, which is what exercises
        // the server's own defaulting rather than duplicating it here.
        val query = endpoint.queries.flatMap { q -> wire(q.name, q.codec, q.listStyle, values[q]) }
        // Cookies travel as one header, so they are gathered rather than
        // mapped one to one — which is also why an absent optional cookie
        // costs nothing here.
        val cookies = endpoint.cookieParams.flatMap { c -> wire(c.name, c.codec, c.listStyle, values[c]) }

        val payload = payload(endpoint, values)

        val headers = endpoint.headerParams.flatMap { h -> wire(h.name, h.codec, h.listStyle, values[h]) } +
            (if (cookies.isEmpty()) emptyList() else listOf("Cookie" to Cookies.render(cookies))) +
            listOfNotNull(payload.contentType?.let { "Content-Type" to it })

        return RequestSpec(endpoint.method, path, query, headers, payload.body)
    }

    /** A request body and the media type that says how to read it. */
    private class Payload(val body: String?, val contentType: String? = null)

    private fun payload(endpoint: Endpoint<*, *>, values: Map<ParamKey<*>, Any?>): Payload =
        when (val input = endpoint.bodyInput) {
            null -> Payload(null)

            // Left to the transport, which sends application/json when nothing
            // says otherwise — the behaviour every suite here was written
            // against before there was anything else to send.
            is JsonBody<*> -> Payload(
                codecs.codec<Any?>(input.type).encodeToString(
                    values[input] ?: error("No body supplied for $endpoint"),
                ),
            )

            is FormBody<*> -> Payload(
                codecs.formCodec<Any?>(input.type).encodeToString(
                    values[input] ?: error("No body supplied for $endpoint"),
                ),
                "application/x-www-form-urlencoded",
            )

            is MultipartBody -> multipart(endpoint, input, values)

            is RawBody -> when (val handle = values[input]) {
                is TextBody -> Payload(handle.text)

                else -> error(
                    "$endpoint takes a raw body. Pass rawText(\"...\") as its input, " +
                        "or drive `transport` directly for anything a String cannot hold.",
                )
            }
        }

    /**
     * Writes the envelope by hand, which is the honest amount of work: the
     * parts are already described, so there is nothing to configure and
     * nothing to keep in step.
     *
     * Text parts go first whatever order they were declared in, because the
     * server stops reading at the file part — see `MultipartBody.decode`. A
     * client that sent them in declaration order would be able to build a
     * request its own server refuses, which is a worse thing for a test client
     * to be able to do than a reordering is.
     *
     * A part's content is a `String` here, since [RequestSpec] carries a
     * `String` body. That is the one thing this cannot do: a file part whose
     * bytes are not text has to go through `transport` directly. It is
     * documented as a gap rather than hidden behind a lossy encoding.
     */
    private fun multipart(
        endpoint: Endpoint<*, *>,
        body: MultipartBody,
        values: Map<ParamKey<*>, Any?>,
    ): Payload {
        val sections = (body.textParts + body.fileParts).mapNotNull { part ->
            val value = values[part] ?: return@mapNotNull null
            when (part) {
                is TextPart<*> -> buildString {
                    append("Content-Disposition: form-data; name=\"${part.name}\"\r\n\r\n")
                    append(encodePlain(part.codec, value))
                }

                is FilePart<*> -> {
                    val file = value as? UploadedFile
                        ?: error("$endpoint takes a file part '${part.name}'; pass an UploadedFile as its input")
                    buildString {
                        append("Content-Disposition: form-data; name=\"${part.name}\"")
                        file.filename?.let { append("; filename=\"$it\"") }
                        append("\r\n")
                        append("Content-Type: ${file.contentType ?: "application/octet-stream"}\r\n\r\n")
                        append(file.text())
                    }
                }
            }
        }

        // A boundary that appears inside a part would end the envelope early,
        // so it is chosen after the content is known rather than hoped for.
        var boundary = "PelicanBoundary"
        while (sections.any { boundary in it }) boundary += "-"

        val text = sections.joinToString("") { "--$boundary\r\n$it\r\n" } + "--$boundary--\r\n"
        return Payload(text, "multipart/form-data; boundary=$boundary")
    }

    /**
     * Sends the call and hands back the undecoded response — status, headers,
     * body — without throwing on a failure status.
     *
     * This is the one to reach for whenever the status itself is the subject:
     *
     * ```
     * val response = app.response(getBookmark, 9_999L)
     * response shouldHaveStatus 404
     * ```
     */
    fun <I> response(endpoint: Endpoint<I, *>, input: I): ResponseSpec =
        transport.send(request(endpoint, input))

    /**
     * Sends the call and decodes the declared response type. Throws
     * [ApiCallFailed] on a non-2xx, so a test that cares about the error path
     * uses [response] instead.
     */
    @Suppress("UNCHECKED_CAST")
    fun <I, R> call(endpoint: Endpoint<I, R>, input: I): R {
        val req = request(endpoint, input)
        val res = transport.send(req)
        if (!res.isSuccess) throw ApiCallFailed(endpoint, req, res)
        return decodeSuccess(endpoint, endpoint.output, res) as R
    }

    /**
     * As [call], for an endpoint that declares its failures: hands back the
     * success value and throws [ApiCallFailed] on anything else, so a test
     * about the happy path reads the same whether failures are declared or
     * not. Use [outcome] when the failure is the subject.
     */
    @JvmName("callFallible")
    @Suppress("UNCHECKED_CAST")
    fun <I, E, T> call(endpoint: Endpoint<I, Fallible<E, T>>, input: I): T {
        val req = request(endpoint, input)
        val res = transport.send(req)
        if (!res.isSuccess) throw ApiCallFailed(endpoint, req, res)
        val out = endpoint.output as FallibleOutput<E, T>
        return decodeSuccess(endpoint, out.success, res) as T
    }

    /**
     * Sends a call to an endpoint that declares its failures, and decodes
     * whichever of them came back — as the type the endpoint declared, not as
     * a string to grep:
     *
     * ```
     * when (val result = app.outcome(getUser, 999L)) {
     *     is Outcome.Ok  -> fail("expected a failure")
     *     is Outcome.Err -> result.error.error shouldBe "No user 999"
     * }
     * ```
     *
     * A status the endpoint never declared still throws [ApiCallFailed]: the
     * point is to assert on the contract, so a response from outside it is a
     * finding rather than a value to inspect.
     *
     * The headers the failure declared come back on it, decoded by their own
     * codecs:
     *
     * ```
     * val err = app.outcome(placeOrder, input) as Outcome.Err
     * err[retryAfter] shouldBe 30L
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    fun <I, E, T> outcome(endpoint: Endpoint<I, Fallible<E, T>>, input: I): Outcome<E, T> {
        val req = request(endpoint, input)
        val res = transport.send(req)
        val out = endpoint.output as FallibleOutput<E, T>

        val declared = out.failures.firstOrNull { it.status == res.status }
        return when {
            // Built rather than produced by invoking the declaration, which
            // would apply the server's bargain to the client: a required
            // header the server left off is a thing a test asserts about the
            // response, not a reason for the client to throw instead of
            // handing back the failure that did arrive.
            declared != null -> Outcome.Err(
                declared,
                codecs.codec<Any?>(declared.type).decodeFromString(res.body) as E,
                declared.headers.mapNotNull { h -> res.header(h.name)?.let { h.name to it } },
            )

            res.isSuccess -> Outcome.Ok(decodeSuccess(endpoint, out.success, res) as T)

            else -> throw ApiCallFailed(endpoint, req, res)
        }
    }

    private fun decodeSuccess(endpoint: Endpoint<*, *>, out: Output<*>, res: ResponseSpec): Any? =
        when (out) {
            is JsonOutput<*> -> codecs.codec<Any?>(out.type).decodeFromString(res.body)

            is TextOutput -> res.body

            is EmptyOutput -> Unit

            is NdjsonOutput<*>, is SseOutput<*>, is JsonArrayOutput<*> ->
                error("$endpoint streams its response; use collect(...) rather than call(...)")

            is ByteStreamOutput ->
                error("$endpoint returns opaque bytes; use response(...) and read the body")

            is FallibleOutput<*, *> ->
                error("$endpoint declares its failures; use outcome(...) rather than call(...)")
        }

    /**
     * Sends a streaming call and collects every element.
     *
     * Collecting is the right default for asserting on *content*. It is the
     * wrong tool for asserting that elements arrive as they are produced —
     * that needs the elements as they land, which is a backend-shaped
     * question; see `InMemoryTransport.exchange`.
     */
    @Suppress("UNCHECKED_CAST")
    fun <I, T> collect(endpoint: Endpoint<I, StreamOf<T>>, input: I): List<T> {
        val req = request(endpoint, input)
        val res = transport.send(req)
        if (!res.isSuccess) throw ApiCallFailed(endpoint, req, res)
        return decodeStream(endpoint, endpoint.output, res) as List<T>
    }

    /**
     * As [collect], for a stream whose endpoint declares failures. A declared
     * failure is still a failed call here — reach for [response] or [outcome]
     * when the error path itself is the subject.
     */
    @JvmName("collectFallible")
    @Suppress("UNCHECKED_CAST")
    fun <I, E, T> collect(endpoint: Endpoint<I, Fallible<E, StreamOf<T>>>, input: I): List<T> {
        val req = request(endpoint, input)
        val res = transport.send(req)
        if (!res.isSuccess) throw ApiCallFailed(endpoint, req, res)
        val out = endpoint.output as FallibleOutput<E, StreamOf<T>>
        return decodeStream(endpoint, out.success, res) as List<T>
    }

    private fun decodeStream(endpoint: Endpoint<*, *>, output: Output<*>, res: ResponseSpec): List<Any?> {
        return when (val out = output) {
            is NdjsonOutput<*> -> res.body.lineSequence()
                .filter { it.isNotBlank() }
                .map { decodeElement(out.type, it) }
                .toList()

            is SseOutput<*> -> res.body.split("\n\n")
                .filter { it.isNotBlank() }
                .map { frame ->
                    // A frame's data may be split across several `data:` lines.
                    val data = frame.lineSequence()
                        .filter { it.startsWith("data:") }
                        .joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
                    decodeElement(out.type, data)
                }

            // Pekko frames this one, so the whole body is a single JSON array
            // and the configured codec can decode it as List<T> in one go.
            is JsonArrayOutput<*> ->
                codecs.codec<List<Any?>>(listTypeOf(out.type)).decodeFromString(res.body)

            else -> error("$endpoint does not stream; use call(...) rather than collect(...)")
        }
    }

    override fun close() {
        (transport as? AutoCloseable)?.close()
    }

    private fun decodeElement(type: KType, text: String): Any? =
        codecs.codec<Any?>(type).decodeFromString(text)
}

/**
 * Decodes a response body as [T] with the client's own codecs.
 *
 * For the payloads a call's declared type does not cover — chiefly error
 * bodies, which is where a test most wants to assert on structure rather than
 * grep a string.
 *
 * ```
 * val error: ApiError = app.decodeBody(app.response(getBookmark, 9999L))
 * ```
 */
inline fun <reified T> ApiClient.decodeBody(response: ResponseSpec): T =
    codecs.codec<T>(typeOf<T>()).decodeFromString(response.body)

/** A raw request body a test can actually construct. */
class TextBody(val text: String) : ByteStreamHandle

/** Supplies a [RawBody] input for a client call. */
fun rawText(text: String): ByteStreamHandle = TextBody(text)

/**
 * The occurrences one input puts on the wire: none for an absent optional,
 * one for an ordinary value, and for a list whatever its declared style says —
 * one per element, or one string with separators in it.
 *
 * Written from the declaration rather than from the value's Kotlin type, so a
 * request built here spreads a list exactly the way the document says the
 * server will read it back.
 */
private fun wire(
    name: String,
    codec: PlainCodec<*>,
    listStyle: ListStyle?,
    value: Any?,
): List<Pair<String, String>> = when {
    value == null -> emptyList()
    listStyle == null -> listOf(name to encodePlain(codec, value))
    else -> codec.encodeAll(name, listStyle, value as List<*>).map { name to it }
}

@Suppress("UNCHECKED_CAST")
private fun encodePlain(codec: PlainCodec<*>, value: Any): String =
    (codec as PlainCodec<Any>).encode(value)

private val listClass: KClass<List<*>> = List::class

private fun listTypeOf(element: KType): KType =
    listClass.createType(listOf(KTypeProjection.invariant(element)))
