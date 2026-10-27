package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.failureNamedBy
import io.github.matthewjones372.pelican.spi.successNamedBy
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

/** The header a key arrives in, as the IETF `Idempotency-Key` draft names it. */
const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"

/**
 * An endpoint's idempotency key, declared with `idempotencyKey()`: the header it arrives in, and the two failures
 * Pelican answers for it, which no handler produces. Both are refusals tagged under the envelope's `error` field, so
 * a failure of the endpoint's own may share their status.
 */
class IdempotencyKey internal constructor(
    val header: HeaderParam<String?>,
    /** 409: a request with this key is still being answered. */
    val inFlight: ErrorSpec,
    /** 422: this key was first sent with a different request. */
    val reused: ErrorSpec,
) {
    val required: Boolean get() = header.required

    override fun toString() = "idempotencyKey"
}

internal fun idempotencyKeyOf(required: Boolean): IdempotencyKey = IdempotencyKey(
    HeaderParam(
        IDEMPOTENCY_KEY_HEADER,
        StringCodec,
        required,
        default = null,
        description = "A key the caller chooses, unique per request. A repeat with the same key is answered " +
            "with the first response.",
    ),
    ErrorSpec(
        IN_FLIGHT_STATUS,
        "A request with this Idempotency-Key is still being answered. Retry after the time given.",
        type = null,
        headers = listOf(RETRY_AFTER),
        tag = IN_FLIGHT_TAG,
        discriminator = REFUSAL_REASON_FIELD,
    ),
    ErrorSpec(
        REUSED_STATUS,
        "This Idempotency-Key was first sent with a different request.",
        type = null,
        tag = REUSED_TAG,
        discriminator = REFUSAL_REASON_FIELD,
    ),
)

private const val IN_FLIGHT_STATUS = 409
private const val IN_FLIGHT_TAG = "idempotency_key_in_flight"
private const val REUSED_STATUS = 422
private const val REUSED_TAG = "idempotency_key_reused"

/** Where the refusal envelope writes its reason: `ApiError.error`. */
private const val REFUSAL_REASON_FIELD = "error"

// Optional: a failure of the endpoint's own may share the 409, and it sends none.
private val RETRY_AFTER =
    responseHeader<Long>("Retry-After", "Seconds to wait before sending the request again.").optional()

/** What an idempotency key names: one key, on one operation, from one caller. */
data class IdempotencyScope(
    val operation: String,
    /** The caller's subject, or null on an endpoint anyone may call unnamed. */
    val subject: String?,
    val key: String,
)

/** A response as it was answered, kept to answer a repeat with. Plain fields, so a store can persist it as it likes. */
class StoredResponse(
    val status: Int,
    val headers: List<Pair<String, String>>,
    /** Null for a response with no body. */
    val mediaType: String?,
    val body: ByteArray,
) {
    override fun toString() = "StoredResponse($status, ${body.size} bytes)"
}

/** What a store holds under a key, as [IdempotencyStore.reserve] answers it. */
sealed interface Reservation {
    /** This request now holds the key, and runs the handler. */
    data object Reserved : Reservation

    /** Another request holds the key and has not finished. */
    data object InFlight : Reservation

    /** The key was first sent with a different request. */
    data object Mismatch : Reservation

    /** The first request finished, and this is what it answered. */
    class Done(val response: StoredResponse) : Reservation {
        override fun toString() = "Done($response)"
    }
}

/**
 * Where idempotency keys and the responses they answered are kept. Pelican holds none itself, so a service picks
 * where they live; [InMemoryIdempotencyStore] is the one for tests.
 *
 * Calls block, as an [Authenticator]'s do, and every one must be atomic across the nodes sharing the store: two
 * requests reserving one key at once must see one [Reservation.Reserved] between them.
 */
interface IdempotencyStore {
    /**
     * Takes [scope] for a request whose fingerprint is [fingerprint], or says why not. A reservation never completed
     * is given up after [lease], so a node that died mid-request does not hold its key until the ttl.
     */
    fun reserve(scope: IdempotencyScope, fingerprint: String, lease: Duration): Reservation

    /** Keeps [response] for [ttl], answered to every repeat of the request that reserved [scope]. */
    fun complete(scope: IdempotencyScope, response: StoredResponse, ttl: Duration)

    /** What is held under [scope] without taking it: [Reservation.InFlight], [Reservation.Done] or null. */
    fun lookup(scope: IdempotencyScope): Reservation?

    /** Gives up a reservation that was never completed, so a retry runs the handler again. A completed one stays. */
    fun release(scope: IdempotencyScope)
}

/**
 * An [IdempotencyStore] in this process's memory. For tests and a single node: nothing is shared with another
 * process and nothing survives a restart. Expired entries are dropped when their key is next touched.
 */
class InMemoryIdempotencyStore(private val clock: Clock = Clock.systemUTC()) : IdempotencyStore {

    private class Entry(val fingerprint: String, val until: Instant, val response: StoredResponse?)

    private val entries = ConcurrentHashMap<IdempotencyScope, Entry>()

    override fun reserve(scope: IdempotencyScope, fingerprint: String, lease: Duration): Reservation {
        val now = clock.instant()
        var answer: Reservation = Reservation.Reserved
        // compute holds the key's bin for the whole decision, which is the atomicity the interface promises.
        entries.compute(scope) { _, held ->
            val live = held?.takeUnless { now.isAfter(it.until) }
            answer = when {
                live == null -> Reservation.Reserved
                live.fingerprint != fingerprint -> Reservation.Mismatch
                live.response == null -> Reservation.InFlight
                else -> Reservation.Done(live.response)
            }
            if (answer == Reservation.Reserved) Entry(fingerprint, now.plus(lease), null) else live
        }
        return answer
    }

    override fun complete(scope: IdempotencyScope, response: StoredResponse, ttl: Duration) {
        val until = clock.instant().plus(ttl)
        entries.compute(scope) { _, held -> Entry(held?.fingerprint.orEmpty(), until, response) }
    }

    override fun lookup(scope: IdempotencyScope): Reservation? {
        val now = clock.instant()
        val live = entries.computeIfPresent(scope) { _, held -> held.takeUnless { now.isAfter(it.until) } }
        return when {
            live == null -> null
            live.response == null -> Reservation.InFlight
            else -> Reservation.Done(live.response)
        }
    }

    override fun release(scope: IdempotencyScope) {
        entries.computeIfPresent(scope) { _, held -> held.takeIf { it.response != null } }
    }
}

/** Where an [Api] keeps the responses its keyed endpoints answered, and for how long. Set with `idempotency(...)`. */
class Idempotency internal constructor(
    val store: IdempotencyStore,
    /** How long a response is answered to repeats. */
    val ttl: Duration,
    /** How long a request may run before a repeat of it may run in its place. */
    val lease: Duration,
)

/** The handler behind [key], answering a repeat from [settings]'s store rather than running it again. */
internal fun Api.keyed(
    endpoint: Endpoint<*, *>,
    key: IdempotencyKey,
    settings: Idempotency,
    invoke: (Params) -> CompletionStage<Any?>,
): (Params) -> CompletionStage<Any?> {
    val responses = KeptResponses(endpoint, codecs)
    val store = settings.store
    return { params ->
        val sent = params[key.header]
        if (sent == null) {
            invoke(params)
        } else {
            val scope = IdempotencyScope(endpoint.operationName, subjectOf(endpoint, params), sent)
            when (val held = store.reserve(scope, responses.fingerprint(params, key), settings.lease)) {
                Reservation.Reserved -> {
                    // What a filter set before the handler ran, a request id say, belongs to this request alone.
                    val setByFilters = params.responseHeaders()
                    attempt(params, invoke).whenComplete { result, failure ->
                        if (failure == null) {
                            store.complete(scope, responses.kept(params, result, setByFilters), settings.ttl)
                        } else {
                            store.release(scope)
                        }
                    }
                }

                Reservation.InFlight -> throw ApiException(
                    IN_FLIGHT_STATUS,
                    IN_FLIGHT_TAG,
                    key.inFlight.description,
                    headers = listOf("Retry-After" to IN_FLIGHT_RETRY_SECONDS),
                )

                Reservation.Mismatch -> throw ApiException(REUSED_STATUS, REUSED_TAG, key.reused.description)

                is Reservation.Done -> completed(responses.replayed(params, held.response))
            }
        }
    }
}

/**
 * The verified subject, so one caller's key never answers another's request. Asked of the authenticator again
 * rather than carried from the caller check, which hands the handler only the service's own type.
 */
private fun Api.subjectOf(endpoint: Endpoint<*, *>, params: Params): String? {
    val caller = endpoint.caller ?: return null
    val credentials = params.credentials ?: return null
    return authenticators.getValue(caller.scheme).authenticate(credentials)?.subject
}

/**
 * A response as the store keeps it: the declared output it was, by position, the headers it went out with, and its
 * value written by the endpoint's own codec, so a replay hands the backend the same value the handler did.
 */
private class KeptResponses(private val endpoint: Endpoint<*, *>, private val codecs: Codecs) {
    private val declared = endpoint.output as? DeclaredResponses<*, *>
    private val outputs: List<Any> = declared?.let { it.successes + it.failures } ?: listOf(endpoint.output)

    fun kept(params: Params, result: Any?, setByFilters: List<Pair<String, String>>): StoredResponse {
        val (output, value, headers) = when (result) {
            is Outcome.Ok<*> -> Triple(checkNotNull(declared).successNamedBy(result), result.value, result.headers)
            is Outcome.Err<*> -> Triple(checkNotNull(declared).failureNamedBy(result), result.error, result.headers)
            else -> Triple(endpoint.output, result, emptyList())
        }
        val position = OUTPUT_HEADER to outputs.indexOfFirst { it === output }.toString()
        val body = encode(output, value)
        val mediaType = when {
            output is TextOutput -> TEXT
            typeOf(output) == null -> null
            else -> JSON
        }
        val setByHandler = params.responseHeaders().filter { it !in setByFilters }
        return StoredResponse(statusOf(output), listOf(position) + setByHandler + headers, mediaType, body)
    }

    fun replayed(params: Params, response: StoredResponse): Any? {
        val output = outputs[response.headers.first { it.first == OUTPUT_HEADER }.second.toInt()]
        response.headers
            .filter { it.first != OUTPUT_HEADER }
            .forEach { (name, value) -> params.setRawHeader(name, value) }
        params.setRawHeader(REPLAYED_HEADER, "true")
        val value = decode(output, response.body)
        @Suppress("UNCHECKED_CAST")
        return when {
            declared == null -> value
            output is ErrorOutput<*> -> Outcome.Err(output as ErrorOutput<Any?>, value)
            else -> Outcome.Ok(value, output as Output<Any?>)
        }
    }

    /**
     * The request as the handler read it, so whitespace or field order in what a client sent does not make two
     * requests of one. The key itself is left out: it is what names the request, not part of it.
     */
    fun fingerprint(params: Params, key: IdempotencyKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(part: String) {
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        add(endpoint.operationName)
        endpoint.pathSpec.captures.forEach { add(plain(it.codec, params[it])) }
        endpoint.queries.forEach { add(plain(it.codec, params[it])) }
        endpoint.headerParams.filter { it !== key.header }.forEach { add(plain(it.codec, params[it])) }
        endpoint.cookieParams.forEach { add(plain(it.codec, params[it])) }
        when (val body = endpoint.bodyInput) {
            is MultipartBody -> body.textParts.forEach { add(plain(it.codec, params[it])) }
            null -> Unit
            else -> add(codecs.codec<Any?>(checkNotNull(body.payloadType)).encodeToString(params[body]))
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun encode(output: Any, value: Any?): ByteArray = when (val type = typeOf(output)) {
        null -> if (output is TextOutput) (value as String).toByteArray(StandardCharsets.UTF_8) else ByteArray(0)
        else -> codecs.codec<Any?>(type).encodeToString(value).toByteArray(StandardCharsets.UTF_8)
    }

    private fun decode(output: Any, bytes: ByteArray): Any? = when (val type = typeOf(output)) {
        null -> if (output is TextOutput) String(bytes, StandardCharsets.UTF_8) else Unit
        else -> codecs.codec<Any?>(type).decodeFrom(bytes)
    }

    private fun typeOf(output: Any): KType? =
        if (output is ErrorOutput<*>) output.type else (output as Output<*>).payloadType

    private fun statusOf(output: Any): Int =
        if (output is ErrorOutput<*>) output.status else (output as Output<*>).status
}

@Suppress("UNCHECKED_CAST")
private fun plain(codec: PlainCodec<*>, value: Any?): String = when (value) {
    null -> "\u0000"
    is List<*> -> value.joinToString("\u0000") { plain(codec, it) }
    else -> (codec as PlainCodec<Any>).encode(value)
}

/** Which declared output a kept response was. A pseudo-header's name, so no real header can collide with it. */
private const val OUTPUT_HEADER = ":output"

private const val REPLAYED_HEADER = "Idempotent-Replayed"
private const val IN_FLIGHT_RETRY_SECONDS = "1"
private const val JSON = "application/json"
private const val TEXT = "text/plain"
