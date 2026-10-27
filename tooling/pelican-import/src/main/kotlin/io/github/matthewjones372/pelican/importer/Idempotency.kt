package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.IDEMPOTENCY_KEY_HEADER
import io.github.matthewjones372.pelican.endpoint

/** The header and the two failures an `idempotencyKey()` stands for. */
internal class ImportedKey(val header: IrParam, val failures: Set<IrFailure>)

/**
 * An `Idempotency-Key` header whose 409 and 422 are the ones Pelican writes beside it, so that the three read back
 * as the one `idempotencyKey()` that wrote them. Anything else, another service's 409 under the same header or one
 * sharing its status with a failure of the endpoint's own, stays the plain header and failures it says it is.
 */
internal fun idempotencyKeyIn(params: List<IrParam>, failures: List<IrFailure>): ImportedKey? {
    val header = params.singleOrNull { it.location == "header" && it.name.equals(IDEMPOTENCY_KEY_HEADER, true) }
        ?: return null
    val inFlight = failures.singleOrNull { it.status == pelicans.inFlight.status } ?: return null
    val reused = failures.singleOrNull { it.status == pelicans.reused.status } ?: return null
    val written = inFlight.description == pelicans.inFlight.description &&
        reused.description == pelicans.reused.description
    return if (written) ImportedKey(header, setOf(inFlight, reused)) else null
}

/** What `idempotencyKey()` declares today, read off a declaration rather than copied, so the two cannot drift. */
private val pelicans = checkNotNull(
    endpoint {
        post("keyed")
        idempotencyKey()
        empty()
    }.idempotency,
)
