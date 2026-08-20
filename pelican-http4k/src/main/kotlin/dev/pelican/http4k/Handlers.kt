package dev.pelican.http4k

import dev.pelican.ByteStream
import dev.pelican.ByteStreamHandle
import dev.pelican.Endpoint
import dev.pelican.Fallible
import dev.pelican.Method
import dev.pelican.Outcome
import dev.pelican.Params
import dev.pelican.ServerEndpoint
import dev.pelican.StreamOf
import org.http4k.core.Body
import org.http4k.core.Request
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.http4k.core.Method as Http4kMethod

/**
 * The typed bridge between a backend-agnostic [Endpoint] and http4k.
 *
 * Core cannot name a stream, so it types streaming endpoints with the phantom
 * marker [StreamOf]. This file is where that marker is cashed in — for a
 * `Sequence<T>` here, as `pelican-pekko` cashes the same marker in for a
 * `Source<T, NotUsed>`. The compiler still checks the element type, and the
 * endpoint descriptions know about neither.
 *
 * A `Sequence` is the honest equivalent of a `Source` for a server-as-a-
 * function: http4k hands a handler a request and wants a response, on the
 * calling thread, so laziness rather than back-pressure signalling is what
 * keeps a stream from being assembled in memory. The sequence is pulled as the
 * response body is written, one element at a time — see `Responses.kt`.
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
infix fun <I, T : Any> Endpoint<I, T>.handledNow(f: Params.(I) -> T): ServerEndpoint =
    ServerEndpoint(this) { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) }

/**
 * Binds an endpoint whose output is a single value, computed elsewhere.
 *
 * http4k answers on the calling thread, so the interpreter waits for this
 * stage rather than handing it back to a server that would. It exists for a
 * handler that already speaks `CompletionStage` — a database driver's client,
 * say — not as a route to concurrency this backend does not have.
 */
infix fun <I, T : Any> Endpoint<I, T>.handledBy(f: Params.(I) -> CompletionStage<T>): ServerEndpoint =
    ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

/** Binds an endpoint that returns no body. */
infix fun <I> Endpoint<I, Unit>.handledWith(f: Params.(I) -> Unit): ServerEndpoint =
    ServerEndpoint(this) { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) }

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
    f: Params.(I) -> Outcome<E, T>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?)
}

/** As [handledOrFail], for a handler that answers through a [CompletionStage]. */
infix fun <I, E : Any, T : Any> Endpoint<I, Fallible<E, T>>.handledByOrFail(
    f: Params.(I) -> CompletionStage<Outcome<E, T>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

/** Binds a streaming endpoint that may fail before the first element. */
infix fun <I, E : Any, T> Endpoint<I, Fallible<E, StreamOf<T>>>.streamedOrFail(
    f: Params.(I) -> Outcome<E, Sequence<T>>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?)
}

/** As [streamedOrFail], for a handler that decides through a [CompletionStage]. */
infix fun <I, E : Any, T> Endpoint<I, Fallible<E, StreamOf<T>>>.streamedByOrFail(
    f: Params.(I) -> CompletionStage<Outcome<E, Sequence<T>>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

// ------------------------------------------------------------- streams

/**
 * Binds a streaming endpoint. Building a `Sequence` is cheap and synchronous —
 * the work happens as it is consumed — so this is the usual case.
 *
 * Return a lazy sequence and the elements reach the socket as they are
 * produced. Return `list.asSequence()` and you have described a stream of
 * something you already assembled, which is legal and sometimes what you want.
 */
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedNow(f: Params.(I) -> Sequence<T>): ServerEndpoint =
    ServerEndpoint(this) { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) }

/** Binds a streaming endpoint where deciding *what* to stream is itself a stage. */
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedBy(
    f: Params.(I) -> CompletionStage<Sequence<T>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

/** Binds an endpoint that streams opaque bytes. The stream is closed once written. */
infix fun <I> Endpoint<I, ByteStream>.bytesNow(f: Params.(I) -> InputStream): ServerEndpoint =
    ServerEndpoint(this) { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) }

// ------------------------------------------------------------- accessors

internal class Http4kByteStream(val body: Body) : ByteStreamHandle

/**
 * The request body as a stream. Nothing has been read from it yet, and nothing
 * will be until the handler reads it.
 */
fun ByteStreamHandle.toStream(): InputStream = (this as Http4kByteStream).body.stream

/** Escape hatch: the raw http4k request behind this call. */
val Params.request: Request
    get() = underlying as Request

internal fun Method.toHttp4k(): Http4kMethod = when (this) {
    Method.GET -> Http4kMethod.GET
    Method.POST -> Http4kMethod.POST
    Method.PUT -> Http4kMethod.PUT
    Method.PATCH -> Http4kMethod.PATCH
    Method.DELETE -> Http4kMethod.DELETE
    Method.HEAD -> Http4kMethod.HEAD
    Method.OPTIONS -> Http4kMethod.OPTIONS
}
