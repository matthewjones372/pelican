package dev.pelican.arrow

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.fold
import dev.pelican.ErrorOutput
import dev.pelican.Outcome
import dev.pelican.Params
import dev.pelican.ok

/**
 * How a raised error becomes a response.
 *
 * Pelican decides the status of a failure by *which* [ErrorOutput] produced it
 * — that is what keeps two failures sharing a payload type distinguishable —
 * so a domain error raised by Arrow has to be told which declared failure it
 * is. That is this function, supplied where the handler is bound:
 *
 * ```
 * val noSuchUser = errorJson<ApiError>(404, "No user with that id")
 * val badKey     = errorJson<ApiError>(401, "Missing or bad API key")
 *
 * val toResponse: ErrorMapper<OrderProblem, ApiError> = { problem ->
 *     when (problem) {
 *         is UnknownUser -> noSuchUser(ApiError(404, "No user ${problem.id}"))
 *         is BadKey      -> badKey(ApiError(401, "Bad API key"))
 *     }
 * }
 * ```
 *
 * Written against a sealed error type the `when` is exhaustive, so adding a
 * problem the endpoint has no failure for is a compile error rather than a 500
 * discovered in production.
 */
typealias ErrorMapper<E, F> = (E) -> Outcome<F, Nothing>

/**
 * Runs [f] with a [Raise] of [E] in scope and turns the outcome into Pelican's
 * [Outcome]: a value becomes [Outcome.Ok], a raised [E] goes through [mapErr].
 *
 * The binders in this module and in pelican-arrow-pekko, -http4k and -ktor are
 * all one line over this. It is public because that list is not closed — a
 * backend Pelican does not ship a module for binds its own handlers, and this
 * is the piece it would otherwise copy.
 */
fun <I, E, F : Any, T : Any> Params.raising(
    input: I,
    mapErr: ErrorMapper<E, F>,
    f: context(Raise<E>) Params.(I) -> T,
): Outcome<F, T> = fold(
    block = { f(this, this@raising, input) },
    recover = { raised: E -> mapErr(raised) },
    transform = { value: T -> ok(value) },
)

// ------------------------------------------------------------- conversions
//
// For code that already has an Either — a repository function written before
// anyone thought about HTTP — and for tests, which find it easier to assert on
// an Either than to match on Outcome.Err's declared output.

/**
 * The success side as [Outcome.Ok], the failure side through [mapErr].
 *
 * ```
 * getUser handledOrFail { id -> Users.find(id).toOutcome(toResponse) }
 * ```
 */
fun <E, F : Any, T : Any> Either<E, T>.toOutcome(mapErr: ErrorMapper<E, F>): Outcome<F, T> =
    fold(ifLeft = mapErr, ifRight = ::ok)

/**
 * The failure payload as the left, the value as the right.
 *
 * Which [ErrorOutput] the failure came from — and so the status it would have
 * been rendered with — is not part of an `Either` and is dropped here. When
 * that matters, match on [Outcome.Err] and read [Outcome.Err.declared].
 */
fun <E, T> Outcome<E, T>.toEither(): Either<E, T> = when (this) {
    is Outcome.Ok -> Either.Right(value)
    is Outcome.Err -> Either.Left(error)
}
