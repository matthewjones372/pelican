package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.*
import java.util.concurrent.CompletionStage
import kotlin.reflect.KClass

/**
 * The handler with this API's filters wrapped around it. Called once per
 * endpoint at route-build time, so the chain is folded once, not per request.
 */
fun Api.handlerFor(se: ServerEndpoint): (Params) -> CompletionStage<Any?> =
    filters.wrap(se.invoke)

/**
 * How many values a request will decode, for sizing the bag they go into. A
 * hash map asked for no capacity allocates sixteen buckets, which is most of a
 * request's allocation for an endpoint declaring two.
 */
fun Endpoint<*, *>.declaredInputCount(): Int {
    val declared = pathSpec.captures.size + queries.size + headerParams.size +
        cookieParams.size + (if (bodyInput == null) 0 else 1)
    return if (declared == 0) 1 else declared * INVERSE_LOAD_FACTOR_NUMERATOR / INVERSE_LOAD_FACTOR_DENOMINATOR + 1
}

/** A hash map grows at three quarters full; 4/3 of what goes in is what to ask for. */
private const val INVERSE_LOAD_FACTOR_NUMERATOR = 4
private const val INVERSE_LOAD_FACTOR_DENOMINATOR = 3

/**
 * Which declared success an [Outcome.Ok] names, and the one place a success is
 * checked against what it promised.
 */
fun FallibleOutput<*, *>.successNamedBy(ok: Outcome.Ok<*>): Output<*> {
    val chosen = chosenSuccess(ok)
    if (successes.none { it === chosen }) {
        throw UndeclaredResponse(
            "$chosen was returned by a handler but $this never declared it. It declares " +
                (successes + failures).joinToString(),
        )
    }

    // As in `failureNamedBy`, and for the same reason: `or` widens T to what
    // the successes have in common, so `ok(value)` names the first success
    // while carrying the second's payload and only the codec finds out.
    if (chosen.doesNotCarry(ok.value)) {
        throw UndeclaredResponse(
            "$chosen carries ${chosen.payloadType} but the handler returned ${ok.value?.let { it::class }}",
        )
    }

    val promised = chosen.headers.filter { header ->
        header.required && ok.headers.none { (name, _) -> name.equals(header.name, ignoreCase = true) }
    }
    check(promised.isEmpty()) {
        "$chosen declares ${promised.joinToString { it.name }} and this response carries no value for " +
            "${if (promised.size == 1) "it" else "them"}. `ok(...)` names no response, so it carries no " +
            "headers either: name the response instead, with what it promised — " +
            "$chosen(value, ${promised.first().name} of ...). Or declare the header as " +
            "responseHeader(...).optional() if it is only sometimes sent."
    }
    return chosen
}

/**
 * Whether [value] is something other than the payload this response declared.
 *
 * A stream is exempt: what a handler produces for one is the backend's own type
 * — a `Source`, a `Flow` — and never the element type the output names.
 */
private fun Output<*>.doesNotCarry(value: Any?): Boolean {
    if (streams() || value == null) return false
    val carried = payloadType?.classifier as? KClass<*> ?: return false
    return !carried.isInstance(value)
}

/**
 * Which declared failure an [Outcome.Err] names, and the one place a failure is
 * checked against what it promised.
 *
 * The sibling of [successNamedBy], and here for the same reason: `E` widens to
 * the common supertype of the failures an endpoint declares, so a handler may
 * name a failure belonging to a different endpoint of the same hierarchy and
 * the compiler will not say. Three interpreters deciding that separately is
 * three chances to send an undescribed response.
 */
fun FallibleOutput<*, *>.failureNamedBy(err: Outcome.Err<*>): ErrorOutput<*> {
    val declared = err.declared
    if (failures.none { it === declared }) {
        throw UndeclaredResponse(
            "$declared was returned by a handler but $this never declared it. It declares " +
                (successes + failures).joinToString() +
                ". A handler may name a failure another endpoint declared, because `orFail` widens E to " +
                "the common supertype of the failures it is given; declare it here, or return one of these.",
        )
    }
    val carried = declared.type.classifier as? KClass<*>
    if (carried != null && !carried.isInstance(err.error)) {
        throw UndeclaredResponse(
            "$declared carries ${declared.type} but the handler returned ${err.error?.let { it::class }}",
        )
    }
    return declared
}
