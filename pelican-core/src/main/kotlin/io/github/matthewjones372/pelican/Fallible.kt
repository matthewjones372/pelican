package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.successNamedBy
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * One of the declared responses, carrying its payload.
 */
sealed interface Outcome<out E, out T> {
    /**
     * One of the declared successes. [declared] is which; null means the
     * first, which is what [ok] produces.
     */
    data class Ok<T>(
        val value: T,
        val declared: Output<T>? = null,
        /** As [Err.headers]: encoded on the way out, filled from the response on the way back. */
        val headers: List<Pair<String, String>> = emptyList(),
    ) : Outcome<Nothing, T> {
        /** One header back, decoded by its own codec. Null when it did not arrive, or did not decode. */
        operator fun <H : Any> get(header: ResponseHeader<H>): H? = headerValue(headers, header)
    }

    /** [declared] is the failure the endpoint listed; it supplies the status. */
    data class Err<E>(
        val declared: ErrorOutput<E>,
        val error: E,
        /**
         * The headers [declared] carries, encoded, in declaration order. One
         * field for both directions, so what a handler sent and what a client
         * reads cannot disagree.
         */
        val headers: List<Pair<String, String>> = emptyList(),
    ) : Outcome<E, Nothing> {
        /**
         * One header back, decoded by its own codec. Null when it did not
         * arrive or did not decode — a client reads this too, and a bad header
         * is a finding for the test, not a reason to lose the failure.
         */
        operator fun <T : Any> get(header: ResponseHeader<T>): T? = headerValue(headers, header)
    }
}

/**
 * Shared by both sides of an [Outcome]. A value that does not decode is null
 * rather than a throw: this is the reading end, where losing the response to
 * report a bad header would replace what the caller asked about.
 */
@Suppress("UNCHECKED_CAST")
private fun <T : Any> headerValue(headers: List<Pair<String, String>>, header: ResponseHeader<T>): T? =
    headers.firstOrNull { (name, _) -> name.equals(header.name, ignoreCase = true) }
        ?.let { (_, raw) ->
            try {
                header.codec.decode(header.name, raw) as T
            } catch (_: DecodeFailure) {
                null
            }
        }

/** The first declared success, carrying [value]. */
fun <T> ok(value: T): Outcome<Nothing, T> = Outcome.Ok(value)

/**
 * Which declared success an [Outcome.Ok] names, and nothing else: a bare
 * `ok(value)` names none, and the first declared success is what that means.
 *
 * Separate from [successNamedBy] because the two readers want different things
 * from the same rule. An interpreter about to write a response wants the checks
 * as well, and would rather refuse than send something undescribed. A filter
 * working out what status is going out wants the answer alone — it is
 * measuring, and a metric that throws is a worse outage than the missing
 * measurement it was trying to avoid.
 */
internal fun FallibleOutput<*, *>.chosenSuccess(ok: Outcome.Ok<*>): Output<*> =
    ok.declared ?: successes.first()

/**
 * A declared header paired with the value one response is sending it with —
 * `retryAfter of 30L`. See [ErrorOutput.invoke] and [Output.invoke].
 */
class HeaderValue internal constructor(
    internal val header: ResponseHeader<*>,
    private val value: Any,
) {
    /** By the header's own codec, so the wire carries what the schema describes. */
    @Suppress("UNCHECKED_CAST")
    internal fun encoded(): String = (header.codec as PlainCodec<Any>).encode(value)
}

/**
 * Supplies a declared response's header, typed by its declaration. Infix rather
 * than `Pair`, because `to` would type the value as `Any` and let
 * `retryAfter to "soon"` compile.
 */
infix fun <T : Any> ResponseHeader<T>.of(value: T): HeaderValue = HeaderValue(this, value)

/**
 * The headers a declared response is sent with, checked against what it
 * declared and encoded in *declaration* order rather than call order, so two
 * handlers producing the same response put the same bytes on the wire.
 */
internal fun encodeDeclaredHeaders(
    owner: Any,
    declared: List<ResponseHeader<*>>,
    supplied: Array<out HeaderValue>,
): List<Pair<String, String>> {
    supplied.forEach { given ->
        if (declared.none { it === given.header }) {
            error(
                "${given.header.name} was sent with $owner, which never declared it. " +
                    "List it on the response beside its status, or set it with setHeader " +
                    "if it belongs on every response this endpoint sends.",
            )
        }
    }
    return declared.mapNotNull { header ->
        val given = supplied.firstOrNull { it.header === header }
        when {
            given != null -> header.name to given.encoded()

            header.required -> error(
                "$owner declares ${header.name} and this call left it out. " +
                    "Pass ${header.name} with the payload, or declare it as " +
                    "responseHeader(...).optional() if it is only sometimes sent.",
            )

            else -> null
        }
    }
}

/** One declared failure: a status, a payload type, and the headers it sends. */
class ErrorOutput<E> @PublishedApi internal constructor(
    val status: Int,
    val type: KType,
    val description: String,
    /**
     * Declared here rather than with `emits(...)`: a `Retry-After` on the
     * endpoint's list would be permitted on a success nobody meant to throttle.
     */
    val headers: List<ResponseHeader<*>> = emptyList(),
) {
    init {
        checkStatus("error:$status", status, carriesBody = true)

        val clashes = headers.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys
        require(clashes.isEmpty()) { "error:$status declares the header(s) $clashes more than once" }
    }

    /**
     * Produces this failure, with a value for each header it declared —
     * `throttled(ApiError(429, "Slow down"), retryAfter of 30L)`.
     */
    operator fun invoke(error: E, vararg values: HeaderValue): Outcome<E, Nothing> =
        Outcome.Err(this, error, encodeDeclaredHeaders(this, headers, values))

    internal fun spec() = ErrorSpec(status, description, type, headers)

    override fun toString() = "error:$status"
}

/**
 * Declares a failure carrying [E] as JSON, outside an endpoint block so a
 * handler can name it. [headers] are the only ones it may be sent with.
 */
inline fun <reified E> errorJson(
    status: Int,
    description: String,
    vararg headers: ResponseHeader<*>,
): ErrorOutput<E> = ErrorOutput(status, typeOf<E>(), description, headers.toList())

/**
 * The responses one endpoint declares: at least one success, and the failures a
 * handler may return instead.
 */
class FallibleOutput<E, T> internal constructor(
    /** In declaration order. The first is the one a bare [ok] means. */
    val successes: List<Output<out T>>,
    val failures: List<ErrorOutput<E>>,
) : Output<Outcome<E, T>>() {
    /** The first declared success, which the three overrides below report. */
    val success: Output<out T> get() = successes.first()

    override val status get() = success.status
    override val mediaType get() = success.mediaType
    override val payloadType get() = success.payloadType

    /**
     * Successes only. Failures are all JSON, and an endpoint whose 200 is
     * `text/csv` should not be spared a 406 because its 404 was acceptable.
     */
    override val produces: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        successes.flatMap { it.produces }.toSet()
    }

    init {
        require(successes.isNotEmpty()) { "An output declares at least one success" }

        // A reader has the status and the bytes and nothing else, so two
        // responses sharing a status are a pair none can separate.
        val clashes = (successes.map { it.status } + failures.map { it.status })
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(clashes.isEmpty()) {
            "Two responses are declared for status ${clashes.joinToString()} on the same output. " +
                "An endpoint answers one status one way; give them different statuses, or declare one."
        }

        // Naming a response is what produces it, and producing a stream means
        // handing over the backend's own type — Source, Flow, Sequence — which
        // core cannot name. So a stream is a success, but the only one.
        if (successes.size > 1) {
            val streamed = successes.filter { it.streams() }
            require(streamed.isEmpty()) {
                "${streamed.joinToString()} streams, and an endpoint that declares several successes " +
                    "cannot stream one of them: naming a response is what produces it, and a stream is " +
                    "produced in the backend's own type. Declare the stream as the one success, or answer " +
                    "the other statuses from a different endpoint."
            }
        }
    }

    override fun toString() =
        "responses(${(successes + failures).joinToString { it.toString() }})"
}

/** Whether producing this output means handing over a stream rather than a value. */
internal fun Output<*>.streams(): Boolean =
    this is NdjsonOutput<*> || this is SseOutput<*> || this is JsonArrayOutput<*> || this is ByteStreamOutput

/**
 * Declares a second successful response — `json<Order>(201) or empty(202)`.
 */
infix fun <T> Output<out T>.or(other: Output<out T>): FallibleOutput<Nothing, T> =
    responses(listOf(this, other), emptyList())

/** Three or more, declared in one call rather than chained. */
fun <T> Output<out T>.or(vararg others: Output<out T>): FallibleOutput<Nothing, T> =
    responses(listOf(this) + others, emptyList())

/**
 * Another success on an output that already names alternatives, keeping the
 * payload type the first two agreed on. The more specific receiver, so Kotlin
 * picks it while the third payload fits and widens through the one above when
 * it does not.
 */
infix fun <E, T> FallibleOutput<E, T>.or(other: Output<out T>): FallibleOutput<E, T> =
    responses(listOf(this, other), emptyList())

/** Declares the one failure this output's handler may return instead. */
infix fun <E, T> Output<T>.orFail(failure: ErrorOutput<E>): FallibleOutput<E, T> =
    orFailAll(listOf(this), listOf(failure))

/**
 * Declares the failures this output's handler may return instead. [E] infers to
 * their common supertype, so a sealed hierarchy makes the handler's `when`
 * exhaustive.
 */
fun <E, T> Output<T>.orFail(vararg failures: ErrorOutput<out E>): FallibleOutput<E, T> =
    orFailAll(listOf(this), failures.toList())

/**
 * The same, on an output already declaring several successes. Only where it has
 * no failures yet (`Nothing` is what [or] produces), so declaring failures stays
 * one statement.
 */
infix fun <E, T> FallibleOutput<Nothing, T>.orFail(failure: ErrorOutput<E>): FallibleOutput<E, T> =
    orFailAll(listOf(this), listOf(failure))

/** As above, for several failures at once. */
fun <E, T> FallibleOutput<Nothing, T>.orFail(vararg failures: ErrorOutput<out E>): FallibleOutput<E, T> =
    orFailAll(listOf(this), failures.toList())

/** Every spelling of `orFail` ends here, so they cannot disagree about what one means. */
private fun <E, T> orFailAll(
    declared: List<Output<*>>,
    failures: List<ErrorOutput<out E>>,
): FallibleOutput<E, T> {
    require(failures.isNotEmpty()) { "orFail needs at least one declared failure" }
    return responses(declared, failures)
}

/**
 * The one place a list of declared responses is assembled.
 */
private fun <E, T> responses(
    declared: List<Output<*>>,
    added: List<ErrorOutput<out E>>,
): FallibleOutput<E, T> {
    val nested = declared.filterIsInstance<FallibleOutput<*, *>>()

    val already = nested.firstOrNull { it.failures.isNotEmpty() }
    require(added.isEmpty() || already == null) { "orFail is already applied to $already" }

    val successes = declared.flatMap { if (it is FallibleOutput<*, *>) it.successes else listOf(it) }
    val failures = nested.flatMap { it.failures } + added

    @Suppress("UNCHECKED_CAST")
    return FallibleOutput(successes as List<Output<out T>>, failures as List<ErrorOutput<E>>)
}
