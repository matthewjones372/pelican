package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.ByteStream
import io.github.matthewjones372.pelican.ByteStreamHandle
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.StreamOf
import org.http4k.core.Body
import org.http4k.core.Request
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.http4k.core.Method as Http4kMethod

/**
 * The typed bridge between a backend-agnostic [Endpoint] and http4k. Core
 * cannot name a stream, so streaming endpoints carry the phantom marker
 * [StreamOf]; this cashes it in for a `Sequence<T>`.
 *
 * A `Sequence` is the honest equivalent of a `Source` here: http4k answers on
 * the calling thread, so laziness rather than back-pressure signalling is what
 * keeps a stream out of memory. It is pulled as the body is written.
 */

// ------------------------------------------------------------- value outputs
//
// Every binder takes `Params.(I) -> ...` — a typed tuple with endpoint(a, b),
// Params in the lens style. The receiver lets a typed handler still reach
// `setHeader`, an attribute, or the backend's request. A lambda that ignores
// it is unchanged.

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
// `Endpoint<I, Outcome<E, T>>`, and these are the binders written for it.
// The handler returns an `Outcome`, so producing an error the endpoint never
// declared is a compile error rather than a 500 nobody documented.
//
// They are named apart from the total binders rather than overloading them:
// a lambda's return type is inferred after overload resolution, so `(I) -> T`
// and `(I) -> Outcome<E, T>` cannot be told apart at the call site.

/** Binds an endpoint that either succeeds with [T] or returns a declared failure. */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledOrFail(
    f: Params.(I) -> Outcome<E, T>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?)
}

/** As [handledOrFail], for a handler that answers through a [CompletionStage]. */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledByOrFail(
    f: Params.(I) -> CompletionStage<Outcome<E, T>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

// ------------------------------------------------------------- several successes
//
// The same binder under the name that reads right when the alternatives are
// not failures — `Outcome<Nothing, T>` is the shape above with an empty
// failure side. Two names because `handledOrFail` on an endpoint declaring no
// failure reads as a mistake.

/** Binds an endpoint that answers with one of several declared responses. */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledOneOf(
    f: Params.(I) -> Outcome<E, T>,
): ServerEndpoint = handledOrFail(f)

/** As [handledOneOf], for a handler that answers through a [CompletionStage]. */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledByOneOf(
    f: Params.(I) -> CompletionStage<Outcome<E, T>>,
): ServerEndpoint = handledByOrFail(f)

/** Binds a streaming endpoint that may fail before the first element. */
infix fun <I, E : Any, T> Endpoint<I, Outcome<E, StreamOf<T>>>.streamedOrFail(
    f: Params.(I) -> Outcome<E, Sequence<T>>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?)
}

/** As [streamedOrFail], for a handler that decides through a [CompletionStage]. */
infix fun <I, E : Any, T> Endpoint<I, Outcome<E, StreamOf<T>>>.streamedByOrFail(
    f: Params.(I) -> CompletionStage<Outcome<E, Sequence<T>>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

// ------------------------------------------------------------- streams

/**
 * Binds a streaming endpoint. A lazy `Sequence` does its work as it is
 * consumed, so elements reach the socket as they are produced.
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

/** The request body as a stream, unread until the handler reads it. */
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
