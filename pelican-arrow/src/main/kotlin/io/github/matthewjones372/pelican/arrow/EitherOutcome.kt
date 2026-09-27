package io.github.matthewjones372.pelican.arrow

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.err
import io.github.matthewjones372.pelican.ok

/**
 * A `Right` is [ok] and a `Left` is [err] — the single declared failure. An
 * endpoint declaring several failures uses the overload that names one, for
 * the same reason a bare `err` is refused there: the declaration is what
 * fixes the status.
 */
fun <E, A> Either<E, A>.toOutcome(): Outcome<E, A> = fold({ err(it) }, { ok(it) })

/** The naming form: a `Left` becomes [failure], which supplies the status. */
fun <E, A> Either<E, A>.toOutcome(failure: ErrorOutput<E>): Outcome<E, A> = fold({ failure(it) }, { ok(it) })

/**
 * The form for an endpoint declaring several failures: [refused] names which
 * declaration each `Left` becomes, and a `Right` is [ok]. It answers a failure
 * only, so a `Left` cannot become a success by mistake (spec 0060).
 */
inline fun <L, E, A> Either<L, A>.toOutcome(refused: (L) -> Outcome<E, Nothing>): Outcome<E, A> =
    fold(refused) { ok(it) }

/** The way back, for a caller who wants their domain type on the outside of a client call. */
fun <E, A> Outcome<E, A>.toEither(): Either<E, A> = when (this) {
    is Outcome.Ok -> value.right()
    is Outcome.Err -> error.left()
}
