package io.github.matthewjones372.pelican.ktor

import io.github.matthewjones372.pelican.ByteStream
import io.github.matthewjones372.pelican.ByteStreamHandle
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Fallible
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.StreamOf
import io.ktor.server.application.ApplicationCall
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import io.ktor.http.HttpMethod as KtorMethod

/**
 * The typed bridge between a backend-agnostic [Endpoint] and Ktor. Core cannot
 * name a stream, so streaming endpoints carry the phantom marker [StreamOf];
 * this cashes it in for a `Flow<T>`, with the element type still checked.
 *
 * Every binder takes a `suspend` function. A lambda that suspends nowhere is
 * still valid, so there is no second, blocking set.
 *
 * `ServerEndpoint` speaks `CompletionStage`, the only handler type core can
 * name without picking a concurrency library. The gap is closed here: the
 * handler is launched in the call's own scope, so a disconnected client
 * cancels it.
 */

// ------------------------------------------------------------- value outputs
//
// Every binder takes `Params.(I) -> ...` — a typed tuple with endpoint(a, b),
// Params in the lens style. The receiver lets a typed handler still reach
// `setHeader`, an attribute, or the backend's request. A lambda that ignores
// it is unchanged.

/** Binds an endpoint whose output is a single value. */
infix fun <I, T : Any> Endpoint<I, T>.handledNow(f: suspend Params.(I) -> T): ServerEndpoint =
    ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

/** Binds an endpoint that returns no body. */
infix fun <I> Endpoint<I, Unit>.handledWith(f: suspend Params.(I) -> Unit): ServerEndpoint =
    ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

// ------------------------------------------------------------- declared failures
//
// `orFail` makes an `Endpoint<I, Fallible<E, T>>`, and these are the only
// binders that fit it: the handler returns an `Outcome`, so an undeclared
// error is a compile error.
//
// Named apart rather than overloaded, because a lambda's return type is
// inferred after overload resolution.

/** Binds an endpoint that either succeeds with [T] or returns a declared failure. */
infix fun <I, E : Any, T : Any> Endpoint<I, Fallible<E, T>>.handledOrFail(
    f: suspend Params.(I) -> Outcome<E, T>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

// ------------------------------------------------------------- several successes
//
// The same binder under the name that reads right when the alternatives are
// not failures — `Fallible<Nothing, T>` is the shape above with an empty
// failure side. Two names because `handledOrFail` on an endpoint declaring no
// failure reads as a mistake.

/** Binds an endpoint that answers with one of several declared responses. */
infix fun <I, E : Any, T : Any> Endpoint<I, Fallible<E, T>>.handledOneOf(
    f: suspend Params.(I) -> Outcome<E, T>,
): ServerEndpoint = handledOrFail(f)

/** Binds a streaming endpoint that may fail before the first element. */
infix fun <I, E : Any, T> Endpoint<I, Fallible<E, StreamOf<T>>>.streamedOrFail(
    f: suspend Params.(I) -> Outcome<E, Flow<T>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

// ------------------------------------------------------------- streams

/**
 * Binds a streaming endpoint. A cold `Flow` does its work as it is collected,
 * so elements reach the socket as they are produced.
 */
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedNow(f: suspend Params.(I) -> Flow<T>): ServerEndpoint =
    ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

/** Binds an endpoint that streams opaque bytes. */
infix fun <I> Endpoint<I, ByteStream>.bytesNow(f: suspend Params.(I) -> ByteReadChannel): ServerEndpoint =
    ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

// ------------------------------------------------------------- accessors

internal class KtorByteStream(val channel: ByteReadChannel) : ByteStreamHandle

/**
 * The request body as a channel, unread. Ktor's own body is back-pressured, so
 * a handler that never reads it never pulls the request into memory.
 */
fun ByteStreamHandle.toChannel(): ByteReadChannel = (this as KtorByteStream).channel

/** Escape hatch: the raw Ktor call behind this request. */
val Params.call: ApplicationCall
    get() = underlying as ApplicationCall

/**
 * Runs a handler as a child coroutine of the call and hands core the stage it
 * asked for, so the interpreter's await is a real suspension.
 *
 * The failure is caught here because a child that fails cancels its parent: a
 * handler throwing `notFound(...)` would tear the call down before the 404
 * could be rendered. Cancellation still travels the other way, so it is
 * rethrown rather than swallowed.
 */
@Suppress("TooGenericExceptionCaught") // Catching everything is the contract; see the KDoc above.
private fun Params.launch(f: suspend () -> Any?): CompletionStage<Any?> {
    val stage = CompletableFuture<Any?>()
    call.launch {
        try {
            stage.complete(f())
        } catch (cancelled: CancellationException) {
            stage.completeExceptionally(cancelled)
            throw cancelled
        } catch (t: Throwable) {
            stage.completeExceptionally(t)
        }
    }
    return stage
}

internal fun Method.toKtor(): KtorMethod = when (this) {
    Method.GET -> KtorMethod.Get
    Method.POST -> KtorMethod.Post
    Method.PUT -> KtorMethod.Put
    Method.PATCH -> KtorMethod.Patch
    Method.DELETE -> KtorMethod.Delete
    Method.HEAD -> KtorMethod.Head
    Method.OPTIONS -> KtorMethod.Options
}
