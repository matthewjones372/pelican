package dev.pelican.arrow.ktor

import arrow.core.raise.Raise
import arrow.core.raise.fold
import dev.pelican.Endpoint
import dev.pelican.Fallible
import dev.pelican.Outcome
import dev.pelican.Params
import dev.pelican.ServerEndpoint
import dev.pelican.StreamOf
import dev.pelican.arrow.ErrorMapper
import dev.pelican.ktor.handledOrFail
import dev.pelican.ktor.streamedOrFail
import dev.pelican.ok
import kotlinx.coroutines.flow.Flow

/**
 * Raise-style binders for Ktor, where handlers suspend.
 *
 * The versions in pelican-arrow compile against a Ktor `Api` too, but their
 * lambda is not `suspend`, so a handler bound with them cannot await anything —
 * which on this backend is most of the point. These take the suspend lambda
 * Ktor's own binders take, and `raise(...)` still works across a suspension:
 * the whole block, awaits included, runs inside one `fold`.
 *
 * ```
 * getUser.handledRaise(toResponse) { id ->
 *     val user = users.find(id) ?: raise(UnknownUser(id))   // find() suspends
 *     user
 * }
 * ```
 *
 * There is no `handledByRaise` here. Ktor's answer to "this takes a while" is
 * to suspend, so the `CompletionStage` variants the other backends need have
 * nothing to add.
 *
 * These are built on Ktor's own `handledOrFail` and `streamedOrFail` rather
 * than on `ServerEndpoint` directly, which is what puts the handler in the
 * call's coroutine scope: a client that disconnects cancels it, and a handler
 * that throws still reaches the interpreter as a failed stage rather than
 * cancelling the call.
 */

/**
 * Runs [f] with a [Raise] of [E] in scope and maps the result into an
 * [Outcome]. The suspend twin of `raising` in pelican-arrow.
 */
suspend fun <I, E, F : Any, T> Params.suspendRaising(
    input: I,
    mapErr: ErrorMapper<E, F>,
    f: suspend context(Raise<E>) Params.(I) -> T,
): Outcome<F, T> = fold(
    block = { f(this, this@suspendRaising, input) },
    recover = { raised: E -> mapErr(raised) },
    transform = { value: T -> ok(value) },
)

/** Binds an endpoint whose suspending handler returns [T] or raises a declared failure. */
fun <I, E, F : Any, T : Any> Endpoint<I, Fallible<F, T>>.handledRaise(
    mapErr: ErrorMapper<E, F>,
    f: suspend context(Raise<E>) Params.(I) -> T,
): ServerEndpoint = handledOrFail { input -> suspendRaising(input, mapErr, f) }

/**
 * Binds a streaming endpoint whose suspending handler raises instead of
 * returning a flow. The failure is decided before the first element — once
 * bytes are on the wire the response has a status already.
 */
fun <I, E, F : Any, T> Endpoint<I, Fallible<F, StreamOf<T>>>.streamedRaise(
    mapErr: ErrorMapper<E, F>,
    f: suspend context(Raise<E>) Params.(I) -> Flow<T>,
): ServerEndpoint = streamedOrFail { input -> suspendRaising(input, mapErr, f) }
