package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.*
import org.apache.pekko.NotUsed
import org.apache.pekko.http.javadsl.model.HttpMethod
import org.apache.pekko.http.javadsl.model.HttpMethods
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.util.ByteString
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The typed bridge between a backend-agnostic [Endpoint] and Pekko. Core cannot
 * name `Source`, so streaming endpoints carry the phantom marker [StreamOf];
 * this is where it is cashed in, with the element type still checked.
 */

// ------------------------------------------------------------- value outputs
//
// Every binder takes `Params.(I) -> ...` — a typed tuple with endpoint(a, b),
// Params in the lens style. The receiver lets a typed handler still reach
// `setHeader`, an attribute, or the backend's request. A lambda that ignores
// it is unchanged.

/** Binds an endpoint whose output is a single value. */
infix fun <I, T : Any> Endpoint<I, T>.handledBy(f: Params.(I) -> CompletionStage<T>): ServerEndpoint =
    ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

/** Binds an endpoint whose output is a single value, computed synchronously. */
infix fun <I, T : Any> Endpoint<I, T>.handledNow(f: Params.(I) -> T): ServerEndpoint =
    ServerEndpoint(this) { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) }

/** Binds an endpoint that returns no body. */
infix fun <I> Endpoint<I, Unit>.handledWith(f: Params.(I) -> Unit): ServerEndpoint =
    ServerEndpoint(this) { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) }

// ------------------------------------------------------------- declared failures
//
// `orFail` makes an `Endpoint<I, Outcome<E, T>>`, and these are the only
// binders that fit it: the handler returns an `Outcome`, so an undeclared
// error is a compile error.
//
// Named apart rather than overloaded, because a lambda's return type is
// inferred after overload resolution.

/** Binds an endpoint that either succeeds with [T] or returns a declared failure. */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledOrFail(
    f: Params.(I) -> Outcome<E, T>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?)
}

/** As [handledOrFail], for a handler that answers asynchronously. */
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

/** As [handledOneOf], for a handler that answers asynchronously. */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledByOneOf(
    f: Params.(I) -> CompletionStage<Outcome<E, T>>,
): ServerEndpoint = handledByOrFail(f)

/** Binds a streaming endpoint that may fail before the first element. */
infix fun <I, E : Any, T> Endpoint<I, Outcome<E, StreamOf<T>>>.streamedOrFail(
    f: Params.(I) -> Outcome<E, Source<T, NotUsed>>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?)
}

/** As [streamedOrFail], for a handler that decides asynchronously. */
infix fun <I, E : Any, T> Endpoint<I, Outcome<E, StreamOf<T>>>.streamedByOrFail(
    f: Params.(I) -> CompletionStage<Outcome<E, Source<T, NotUsed>>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

// ------------------------------------------------------------- streams

/**
 * Binds a streaming endpoint. Building a `Source` is cheap and synchronous —
 * the work happens as it is consumed — so this is the usual case.
 */
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedNow(f: Params.(I) -> Source<T, NotUsed>): ServerEndpoint =
    ServerEndpoint(this) { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) }

/** Binds a streaming endpoint where deciding *what* to stream is itself async. */
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedBy(
    f: Params.(I) -> CompletionStage<Source<T, NotUsed>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

/** Binds an endpoint that streams opaque bytes. */
infix fun <I> Endpoint<I, ByteStream>.bytesNow(f: Params.(I) -> Source<ByteString, NotUsed>): ServerEndpoint =
    ServerEndpoint(this) { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) }

// ------------------------------------------------------------- accessors

internal class PekkoByteStream(val source: Source<ByteString, Any>) : ByteStreamHandle

/** The request body as a Pekko source. Nothing has been read from it yet. */
fun ByteStreamHandle.toSource(): Source<ByteString, Any> =
    (this as PekkoByteStream).source

/** Escape hatch: the raw Pekko request behind this call. */
val Params.request: HttpRequest
    get() = underlying as HttpRequest

internal fun Method.toPekko(): HttpMethod = when (this) {
    Method.GET -> HttpMethods.GET
    Method.POST -> HttpMethods.POST
    Method.PUT -> HttpMethods.PUT
    Method.PATCH -> HttpMethods.PATCH
    Method.DELETE -> HttpMethods.DELETE
    Method.HEAD -> HttpMethods.HEAD
    Method.OPTIONS -> HttpMethods.OPTIONS
}
