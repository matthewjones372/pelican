package dev.pelican.arrow

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.ensure as arrowEnsure
import arrow.core.raise.ensureNotNull as arrowEnsureNotNull

/**
 * The Raise DSL, reachable from a handler lambda.
 *
 * Arrow declares `raise`, `ensure` and friends against a `Raise<E>` *receiver*.
 * A Pelican handler has already spent its receiver slot on [dev.pelican.Params]
 * — that is what lets it call `setHeader` — so `Raise<E>` arrives as a context
 * parameter instead, and Kotlin's context parameters are deliberately not
 * implicit receivers: members of a context parameter's type are not in scope.
 * Writing `raise(...)` in that lambda would not compile.
 *
 * These four are the bridge. Each declares the `Raise<E>` it needs as its own
 * context parameter, which the compiler *does* supply from the enclosing
 * lambda's, and forwards to Arrow. Nothing is reimplemented here — the
 * short-circuit, and its interaction with `fold`, `either` and cancellation, is
 * Arrow's.
 *
 * ```
 * import dev.pelican.arrow.*
 *
 * getItem.handledRaise(toResponse) { id ->
 *     ensure(id > 0) { NotAnId(id) }
 *     val item = Items.find(id) ?: raise(Unknown(id))
 *     item
 * }
 * ```
 *
 * For anything past these — `withError`, `recover`, an accumulating block —
 * take the scope itself with [raiseScope] and call Arrow directly:
 *
 * ```
 * with(raiseScope<Problem>()) { /* every Raise extension Arrow has */ }
 * ```
 *
 * When Arrow's own DSL grows context-parameter overloads these become
 * redundant, and deleting them will be the whole migration.
 */

/** Short-circuits with [error]. Arrow's `Raise.raise`. */
context(r: Raise<E>)
fun <E> raise(error: E): Nothing = r.raise(error)

/** Raises what [otherwise] builds unless [condition] holds. */
context(r: Raise<E>)
inline fun <E> ensure(condition: Boolean, otherwise: () -> E) = with(r) { arrowEnsure(condition, otherwise) }

/** Returns [value] unless it is null, in which case it raises what [otherwise] builds. */
context(r: Raise<E>)
inline fun <E, A : Any> ensureNotNull(value: A?, otherwise: () -> E): A =
    with(r) { arrowEnsureNotNull(value, otherwise) }

/**
 * The right, or a raise of the left.
 *
 * Spelled out rather than forwarded: Arrow's `bind` is a member of `Raise` with
 * an `Either` extension receiver, so `with(r) { bind() }` here would have this
 * function as a candidate for its own body.
 */
context(r: Raise<E>)
fun <E, A> Either<E, A>.bind(): A = fold(ifLeft = { r.raise(it) }, ifRight = { it })

/**
 * The [Raise] itself, for the parts of Arrow's DSL not mirrored here. The type
 * argument is usually inferable from what you do with it; name it when it
 * is not.
 */
context(r: Raise<E>)
fun <E> raiseScope(): Raise<E> = r
