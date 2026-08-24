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
 * The typed bridge between a backend-agnostic [Endpoint] and Pekko.
 *
 * Core cannot name `Source`, so it types streaming endpoints with the phantom
 * marker [StreamOf]. This file is where that marker is cashed in for a real
 * Pekko type — and the compiler still checks the element type. `pelican-ktor`
 * defines the same functions returning `Flow<T>`, and nothing in core or in the
 * endpoint descriptions changed to let it.
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

/** As [handledOrFail], for a handler that answers asynchronously. */
infix fun <I, E : Any, T : Any> Endpoint<I, Fallible<E, T>>.handledByOrFail(
    f: Params.(I) -> CompletionStage<Outcome<E, T>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

// ------------------------------------------------------------- several successes
//
// The same binder under the name that reads right when the alternatives are
// not failures. An endpoint declaring `200 Order` beside `202 Accepted` is an
// `Endpoint<I, Fallible<Nothing, Any>>` — the shape above with an empty failure
// side — and a handler for it names the response it is producing by invoking
// the declaration, exactly as it names a failure.
//
// Two names for one signature rather than one name for both, because
// `handledOrFail` on an endpoint that declares no failure at all reads as a
// mistake, and the call site is where the name is read.

/** Binds an endpoint that answers with one of several declared responses. */
infix fun <I, E : Any, T : Any> Endpoint<I, Fallible<E, T>>.handledOneOf(
    f: Params.(I) -> Outcome<E, T>,
): ServerEndpoint = handledOrFail(f)

/** As [handledOneOf], for a handler that answers asynchronously. */
infix fun <I, E : Any, T : Any> Endpoint<I, Fallible<E, T>>.handledByOneOf(
    f: Params.(I) -> CompletionStage<Outcome<E, T>>,
): ServerEndpoint = handledByOrFail(f)

/** Binds a streaming endpoint that may fail before the first element. */
infix fun <I, E : Any, T> Endpoint<I, Fallible<E, StreamOf<T>>>.streamedOrFail(
    f: Params.(I) -> Outcome<E, Source<T, NotUsed>>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?)
}

/** As [streamedOrFail], for a handler that decides asynchronously. */
infix fun <I, E : Any, T> Endpoint<I, Fallible<E, StreamOf<T>>>.streamedByOrFail(
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
