package dev.pelican.arrow.http4k

import arrow.core.raise.Raise
import dev.pelican.Endpoint
import dev.pelican.Fallible
import dev.pelican.Outcome
import dev.pelican.Params
import dev.pelican.ServerEndpoint
import dev.pelican.StreamOf
import dev.pelican.arrow.ErrorMapper
import dev.pelican.arrow.raising
import dev.pelican.ok
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Raise-style binders for streaming endpoints on http4k.
 *
 * `handledRaise` and `handledByRaise` in pelican-arrow already work on this
 * backend: binding a value-returning handler names no http4k type. A stream
 * does — it is a `Sequence` here and a `Flow` on Ktor — so these two are the
 * part that cannot be backend-agnostic.
 *
 * The failure is decided before the first element, which is the only place a
 * failure can still choose a status: once bytes are on the wire the response
 * has a status already.
 */

/** Binds a streaming endpoint whose handler raises instead of returning a sequence. */
fun <I, E, F : Any, T> Endpoint<I, Fallible<F, StreamOf<T>>>.streamedRaise(
    mapErr: ErrorMapper<E, F>,
    f: context(Raise<E>) Params.(I) -> Sequence<T>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    CompletableFuture.completedStage(p.raising(inputs.extract(p), mapErr, f) as Any?)
}

/**
 * As [streamedRaise], for a handler that reaches its decision through a
 * [CompletionStage]. The raise happens before the stage is handed back — see
 * `handledByRaise` in pelican-arrow for why that is the only place it can.
 */
fun <I, E, F : Any, T> Endpoint<I, Fallible<F, StreamOf<T>>>.streamedByRaise(
    mapErr: ErrorMapper<E, F>,
    f: context(Raise<E>) Params.(I) -> CompletionStage<Sequence<T>>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    when (val started = p.raising(inputs.extract(p), mapErr, f)) {
        is Outcome.Err -> CompletableFuture.completedStage(started as Any?)
        is Outcome.Ok -> started.value.thenApply { ok(it) as Any? }
    }
}
