package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.*
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
 * Two things follow from building a request from the description rather than
 * from a string. It is type-checked — `call(getUser, 1L)` returns `User`, and
 * renaming an input stops the tests compiling rather than starting to 404. And
 * a passing test is evidence about the *documented* contract, since the path
 * template and payload types are the ones the OpenAPI interpreter reads.
 */
class ApiClient(
    val transport: Transport,
    val codecs: Codecs,
    /**
     * Which encoding to send where an endpoint declares several — see
     * [sending]. Null takes the first declared, as a generated client does.
     */
    val prefers: String? = null,
) : AutoCloseable {

    /**
     * The same client, sending a negotiated body as [mediaType]. A client
     * rather than a parameter on `call`, `response` and `request` alike, which
     * would widen three signatures for the one endpoint that declares a choice.
     */
    fun sending(mediaType: String): ApiClient = ApiClient(transport, codecs, mediaType)

    /**
     * Builds the request an endpoint call would send, without sending it.
     */
    fun <I> request(endpoint: Endpoint<I, *>, input: I): RequestSpec {
        require(endpoint.webhookName == null) {
            "${endpoint.webhookName} is a webhook: a call this service sends, to a URL a subscriber " +
                "registered. There is no route here to ask for it, so there is nothing for a test client " +
                "to call. Assert on the document, or on the sender a generated client provides."
        }

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

        // Not sent at all, which exercises the server's own defaulting.
        val query = endpoint.queries.flatMap { q -> wire(q.name, q.codec, q.listStyle, values[q]) }
        // Cookies travel as one header, so they are gathered.
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

            // Left to the transport, which sends application/json by default.
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

            // Looked up under the negotiated key, which is what the endpoint
            // declared and what a handler reads.
            is NegotiatedBody<*> -> {
                val chosen = input.alternatives.firstOrNull { it.mediaType == prefers }
                    ?: input.alternatives.first()
                val value = values[input] ?: error("No body supplied for $endpoint")
                val text = when (chosen) {
                    is FormBody<*> -> codecs.formCodec<Any?>(chosen.type).encodeToString(value)
                    else -> codecs.codec<Any?>(checkNotNull(chosen.payloadType)).encodeToString(value)
                }
                Payload(text, chosen.mediaType)
            }

            is RawBody -> when (val handle = values[input]) {
                is TextBody -> Payload(handle.text)

                else -> error(
                    "$endpoint takes a raw body. Pass rawText(\"...\") as its input, " +
                        "or drive `transport` directly for anything a String cannot hold.",
                )
            }

            // Framed here and sent whole. This client's job is to make the
            // contract easy to assert on, and a test that has already written
            // its rows down as a list is not testing that they leave one at a
            // time — `PekkoTransportClientTest` and the backends' own timing
            // tests are where that claim is made, over a socket.
            is NdjsonBody<*> -> when (val frames = values[input]) {
                is BufferedFrames<*> -> Payload(
                    frames.values.joinToString("") { codecs.codec<Any?>(input.type).encodeToString(it) + "\n" },
                    "application/x-ndjson",
                )

                else -> error(
                    "$endpoint takes an ndjsonIn body. Pass frames(...) as its input, or drive " +
                        "`transport` directly for an upload that has to arrive a frame at a time.",
                )
            }
        }

    /**
     * Writes the envelope by hand. The order is `partsInWireOrder`, not
     * declaration order, so this client cannot build a request its own server
     * refuses.
     */
    private fun multipart(
        endpoint: Endpoint<*, *>,
        body: MultipartBody,
        values: Map<ParamKey<*>, Any?>,
    ): Payload {
        val sections = body.partsInWireOrder.mapNotNull { part ->
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

        // A boundary appearing inside a part would end the envelope early, so
        // it is chosen once the content is known.
        var boundary = "PelicanBoundary"
        while (sections.any { boundary in it }) boundary += "-"

        val text = sections.joinToString("") { "--$boundary\r\n$it\r\n" } + "--$boundary--\r\n"
        return Payload(text, "multipart/form-data; boundary=$boundary")
    }

    /**
     * The undecoded response — status, headers, body — without throwing on a
     * failure status. The one to reach for when the status is the subject.
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
     * As [call], for an endpoint declaring alternatives, so a happy-path test
     * reads the same either way. Use [outcome] when *which* response came back
     * is the subject.
     */
    @JvmName("callFallible")
    @Suppress("UNCHECKED_CAST")
    fun <I, E, T> call(endpoint: Endpoint<I, Outcome<E, T>>, input: I): T {
        val req = request(endpoint, input)
        val res = transport.send(req)
        if (!res.isSuccess) throw ApiCallFailed(endpoint, req, res)
        val out = endpoint.output as FallibleOutput<E, T>
        return decodeSuccess(endpoint, chosenSuccess(out, res), res) as T
    }

    /**
     * Which declared success this is, read the only way a caller can: by
     * status. Two 2xx sharing one are refused at declaration, so at most one
     * matches.
     */
    private fun <E, T> chosenSuccess(out: FallibleOutput<E, T>, res: ResponseSpec): Output<out T> =
        out.successes.singleOrNull()
            ?: out.successes.firstOrNull { it.status == res.status }
            ?: error(
                "The server answered ${res.status}, which is not one of the successes declared " +
                    "(${out.successes.joinToString { it.status.toString() }}).",
            )

    /**
     * Decodes whichever declared response came back, as the type the endpoint
     * declared rather than a string to grep. The headers that response declared
     * come back on it, decoded by their own codecs.
     */
    @Suppress("UNCHECKED_CAST")
    fun <I, E, T> outcome(endpoint: Endpoint<I, Outcome<E, T>>, input: I): Outcome<E, T> {
        val req = request(endpoint, input)
        val res = transport.send(req)
        val out = endpoint.output as FallibleOutput<E, T>

        val declared = out.failures.firstOrNull { it.status == res.status }
        return when {
            declared != null -> Outcome.Err(
                declared,
                codecs.codec<Any?>(declared.type).decodeFromString(res.body) as E,
                declared.headers.mapNotNull { h -> res.header(h.name)?.let { h.name to it } },
            )

            // Which success it was travels back on the value, since two
            // successes carrying one type is what equality cannot settle.
            res.isSuccess -> {
                val chosen = chosenSuccess(out, res)
                Outcome.Ok(
                    decodeSuccess(endpoint, chosen, res) as T,
                    @Suppress("UNCHECKED_CAST") (chosen as Output<T>),
                    chosen.headers.mapNotNull { h -> res.header(h.name)?.let { h.name to it } },
                )
            }

            else -> throw ApiCallFailed(endpoint, req, res)
        }
    }

    private fun decodeSuccess(endpoint: Endpoint<*, *>, out: Output<*>, res: ResponseSpec): Any? =
        when (out) {
            is JsonOutput<*> -> codecs.codec<Any?>(out.type).decodeFromString(res.body)

            is TextOutput -> res.body

            // Read back by the same media type it was written as, which is the
            // one thing a `Codecs` answering that type has to be able to do
            // for a test to assert on the value rather than on the bytes.
            is MediaOutput<*> -> codecs.codec<Any?>(out.type, out.mediaType).decodeFromString(res.body)

            // This client names no `Accept`, so the server sends the first
            // rendering. Which one it sent is on the response, and that is what
            // is read: assuming would decode the wrong one the day that stops
            // being true.
            is NegotiatedOutput<*> -> decodeSuccess(endpoint, out.renderedAs(res.header("Content-Type")), res)

            is EmptyOutput -> Unit

            is NdjsonOutput<*>, is SseOutput<*>, is JsonArrayOutput<*> ->
                error("$endpoint streams its response; use collect(...) rather than call(...)")

            is ByteStreamOutput ->
                error("$endpoint returns opaque bytes; use response(...) and read the body")

            is FallibleOutput<*, *> ->
                error("$endpoint declares its failures; use outcome(...) rather than call(...)")
        }

    /**
     * Which rendering of a negotiated response came back, by the media type it
     * arrived under. Anything else is the first declared, which is what a
     * server answers a caller that named no preference.
     */
    private fun NegotiatedOutput<*>.renderedAs(contentType: String?): Output<*> {
        val media = contentType?.substringBefore(';')?.trim()
        return alternatives.firstOrNull { it.mediaType.equals(media, ignoreCase = true) }
            ?: alternatives.first()
    }

    /**
     * Sends a streaming call and collects every element — the right default for
     * asserting on content, and the wrong tool for asserting elements arrive as
     * produced. See `InMemoryTransport.exchange` for that.
     *
     * [lastEventId] is what a reconnecting caller would send; it means something
     * only to an event stream, which is the only kind that can be resumed.
     */
    @Suppress("UNCHECKED_CAST")
    fun <I, T> collect(endpoint: Endpoint<I, StreamOf<T>>, input: I, lastEventId: String? = null): List<T> {
        val req = request(endpoint, input).resuming(lastEventId)
        val res = transport.send(req)
        if (!res.isSuccess) throw ApiCallFailed(endpoint, req, res)
        return decodeStream(endpoint, endpoint.output, res) as List<T>
    }

    /**
     * As [collect], where the endpoint declares failures. A declared failure is
     * still a failed call here; use [response] or [outcome] for the error path.
     */
    @JvmName("collectFallible")
    @Suppress("UNCHECKED_CAST")
    fun <I, E, T> collect(
        endpoint: Endpoint<I, Outcome<E, StreamOf<T>>>,
        input: I,
        lastEventId: String? = null,
    ): List<T> {
        val req = request(endpoint, input).resuming(lastEventId)
        val res = transport.send(req)
        if (!res.isSuccess) throw ApiCallFailed(endpoint, req, res)
        val out = endpoint.output as FallibleOutput<E, StreamOf<T>>
        return decodeStream(endpoint, out.success, res) as List<T>
    }

    /** The same request, saying where a reconnecting caller left off. */
    private fun RequestSpec.resuming(lastEventId: String?): RequestSpec =
        if (lastEventId == null) this else withHeader(SseOutput.LAST_EVENT_ID, lastEventId)

    private fun decodeStream(endpoint: Endpoint<*, *>, output: Output<*>, res: ResponseSpec): List<Any?> {
        return when (val out = output) {
            is NdjsonOutput<*> -> res.body.lineSequence()
                .filter { it.isNotBlank() }
                .map { decodeElement(out.type, it) }
                .toList()

            is SseOutput<*> -> res.body.split("\n\n")
                .filter { it.isNotBlank() }
                .map { frame ->
                    // A frame's data may span several `data:` lines.
                    val data = frame.lineSequence()
                        .filter { it.startsWith("data:") }
                        .joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
                    decodeElement(out.type, data)
                }

            // The whole body is one JSON array, so the codec reads List<T>.
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
 * Decodes a response body as [T] with the client's own codecs, for payloads a
 * call's declared type does not cover — chiefly error bodies.
 */
inline fun <reified T> ApiClient.decodeBody(response: ResponseSpec): T =
    codecs.codec<T>(typeOf<T>()).decodeFromString(response.body)

/** A raw request body a test can actually construct. */
class TextBody(val text: String) : ByteStreamHandle

/** Supplies a [RawBody] input for a client call. */
fun rawText(text: String): ByteStreamHandle = TextBody(text)

/** A streamed request body a test can actually construct: the frames, already in hand. */
class BufferedFrames<T>(val values: List<T>) : StreamIn<T>

/** Supplies an [NdjsonBody] input for a client call. */
fun <T> frames(values: List<T>): StreamIn<T> = BufferedFrames(values)

fun <T> frames(vararg values: T): StreamIn<T> = BufferedFrames(values.toList())

/**
 * The occurrences one input puts on the wire: none for an absent optional, one
 * for a value, and for a list whatever its style says. From the declaration
 * rather than the Kotlin type, so a list is spread as the document says.
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
