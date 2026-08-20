package dev.pelican.ktor

import dev.pelican.ByteStream
import dev.pelican.ByteStreamHandle
import dev.pelican.Endpoint
import dev.pelican.Fallible
import dev.pelican.Method
import dev.pelican.Outcome
import dev.pelican.Params
import dev.pelican.ServerEndpoint
import dev.pelican.StreamOf
import io.ktor.server.application.ApplicationCall
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import io.ktor.http.HttpMethod as KtorMethod

/**
 * The typed bridge between a backend-agnostic [Endpoint] and Ktor.
 *
 * Core cannot name a stream, so it types streaming endpoints with the phantom
 * marker [StreamOf]. This file is where that marker is cashed in — for a
 * `Flow<T>` here, as `pelican-pekko` cashes the same marker in for a
 * `Source<T, NotUsed>` and `pelican-http4k` for a `Sequence<T>`. The compiler
 * still checks the element type, and the endpoint descriptions know about
 * neither.
 *
 * Every binder takes a `suspend` function, because that is how Ktor asks a
 * question: a handler runs inside the call's coroutine and may await anything
 * it likes. A lambda that suspends nowhere is still a valid argument, so there
 * is no second, blocking set of binders — `handledNow { it * 2 }` compiles as
 * happily as one that awaits a database.
 *
 * `ServerEndpoint` speaks `CompletionStage`, being the only handler type core
 * can name without picking a concurrency library. The gap is closed here and
 * only here: the handler is launched with [future] in the *call's own* scope —
 * `ApplicationCall` is a `CoroutineScope` — so a handler is a child of the call
 * it serves and a disconnected client cancels it, rather than leaving it
 * running on some scope of this module's invention.
 */

// ------------------------------------------------------------- value outputs
//
// Every binder takes `Params.(I) -> ...`, where I is the endpoint's declared
// input list. With endpoint(a, b, c) that is a typed tuple; with the lens style
// it is Params. One set of functions, both styles.
//
// The receiver is what lets a *typed* handler reach the things that are not
// inputs — `setHeader`, an attribute a filter set, the backend's own request —
// without giving up its typed inputs for the whole Params bag. A lambda that
// ignores it is unchanged: `handledNow { id -> ... }` still compiles, and so
// does `handledNow { (a, b) -> ... }`.

/** Binds an endpoint whose output is a single value. */
infix fun <I, T : Any> Endpoint<I, T>.handledNow(f: suspend Params.(I) -> T): ServerEndpoint =
    ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

/** Binds an endpoint that returns no body. */
infix fun <I> Endpoint<I, Unit>.handledWith(f: suspend Params.(I) -> Unit): ServerEndpoint =
    ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

// ------------------------------------------------------------- declared failures
//
// An endpoint that declares failures with `orFail` is an
// `Endpoint<I, Fallible<E, T>>`, and these are the only binders that fit it.
// The handler returns an `Outcome`, so producing an error the endpoint never
// declared is a compile error rather than a 500 nobody documented.
//
// They are named apart from the total binders rather than overloading them:
// a lambda's return type is inferred after overload resolution, so `(I) -> T`
// and `(I) -> Outcome<E, T>` cannot be told apart at the call site.

/** Binds an endpoint that either succeeds with [T] or returns a declared failure. */
infix fun <I, E : Any, T : Any> Endpoint<I, Fallible<E, T>>.handledOrFail(
    f: suspend Params.(I) -> Outcome<E, T>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

/** Binds a streaming endpoint that may fail before the first element. */
infix fun <I, E : Any, T> Endpoint<I, Fallible<E, StreamOf<T>>>.streamedOrFail(
    f: suspend Params.(I) -> Outcome<E, Flow<T>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

// ------------------------------------------------------------- streams

/**
 * Binds a streaming endpoint.
 *
 * Building a `Flow` is cheap and does no work — the work happens as it is
 * collected, one element at a time, as the response body is written. Return a
 * cold flow and elements reach the socket as they are produced; return
 * `list.asFlow()` and you have described a stream of something you already
 * assembled, which is legal and sometimes what you want.
 */
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedNow(f: suspend Params.(I) -> Flow<T>): ServerEndpoint =
    ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

/** Binds an endpoint that streams opaque bytes. */
infix fun <I> Endpoint<I, ByteStream>.bytesNow(f: suspend Params.(I) -> ByteReadChannel): ServerEndpoint =
    ServerEndpoint(this) { p -> p.launch { p.f(inputs.extract(p)) } }

// ------------------------------------------------------------- accessors

internal class KtorByteStream(val channel: ByteReadChannel) : ByteStreamHandle

/**
 * The request body as a channel. Nothing has been read from it yet, and
 * nothing will be until the handler reads it — Ktor's own body is a
 * back-pressured channel, so a handler that never reads it never pulls the
 * request into memory.
 */
fun ByteStreamHandle.toChannel(): ByteReadChannel = (this as KtorByteStream).channel

/** Escape hatch: the raw Ktor call behind this request. */
val Params.call: ApplicationCall
    get() = underlying as ApplicationCall

/**
 * Runs a handler as a child coroutine of the call and hands core the stage it
 * asked for. The interpreter awaits that stage, so the suspension is real
 * rather than a thread parked on a future.
 *
 * The failure is caught here rather than left to the coroutine builder, and
 * that is not tidiness: a child that *fails* cancels its parent, so a handler
 * throwing `notFound(...)` would tear the call down before the interpreter
 * could render the 404 it describes. Completing the stage by hand keeps the
 * exception inside the stage, where the interpreter is waiting for it, without
 * a supervisor job someone then has to remember to complete. Cancellation still
 * travels the other way — the coroutine is a child of the call, so a client
 * that disconnects cancels the handler, and that one is rethrown rather than
 * swallowed.
 */
@Suppress("TooGenericExceptionCaught") // Catching everything is the contract; see the KDoc above.
private fun Params.launch(f: suspend () -> Any?): CompletionStage<Any?> {
    val stage = CompletableFuture<Any?>()
    call.launch {
        try {
            stage.complete(f())
        } catch (cancelled: CancellationException) {
            // Travels the other way: a client that disconnects cancels the
            // handler, and that one is rethrown rather than swallowed.
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
