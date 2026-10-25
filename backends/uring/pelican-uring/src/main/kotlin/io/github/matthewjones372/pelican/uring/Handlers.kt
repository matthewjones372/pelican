package io.github.matthewjones372.pelican.uring

import io.github.matthewjones372.pelican.ByteStream
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.StreamOf
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Binds an endpoint whose output is a single value. */
infix fun <I, T : Any> Endpoint<I, T>.handledBy(f: Params.(I) -> CompletionStage<T>): ServerEndpoint =
    ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

/** Binds an endpoint whose output is a single value, computed synchronously: see [Handlers] for where. */
infix fun <I, T : Any> Endpoint<I, T>.handledNow(f: Params.(I) -> T): ServerEndpoint =
    ServerEndpoint(this, Synchronous { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) })

/** Binds an endpoint that returns no body. */
infix fun <I> Endpoint<I, Unit>.handledWith(f: Params.(I) -> Unit): ServerEndpoint =
    ServerEndpoint(this, Synchronous { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) })

/** Binds an endpoint that either succeeds with [T] or returns a declared failure. Synchronous: see [Handlers]. */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledOrFail(
    f: Params.(I) -> Outcome<E, T>,
): ServerEndpoint =
    ServerEndpoint(this, Synchronous { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) })

/** As [handledOrFail], for a handler that answers asynchronously. */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledByOrFail(
    f: Params.(I) -> CompletionStage<Outcome<E, T>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

/** [handledOrFail] under the name that reads right when the alternatives are successes, not failures. */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledOneOf(
    f: Params.(I) -> Outcome<E, T>,
): ServerEndpoint = handledOrFail(f)

/** As [handledOneOf], for a handler that answers asynchronously. */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledByOneOf(
    f: Params.(I) -> CompletionStage<Outcome<E, T>>,
): ServerEndpoint = handledByOrFail(f)

/** Binds a streaming endpoint, one frame per element. Synchronous: see [Handlers] for where. */
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedNow(f: Params.(I) -> Sequence<T>): ServerEndpoint =
    ServerEndpoint(this, Synchronous { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) })

/** Binds a streaming endpoint where deciding what to stream is itself asynchronous. */
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedBy(f: Params.(I) -> CompletionStage<Sequence<T>>): ServerEndpoint =
    ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

/** Binds a streaming endpoint that may fail before the first element. Synchronous: see [Handlers]. */
infix fun <I, E : Any, T> Endpoint<I, Outcome<E, StreamOf<T>>>.streamedOrFail(
    f: Params.(I) -> Outcome<E, Sequence<T>>,
): ServerEndpoint =
    ServerEndpoint(this, Synchronous { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) })

/** As [streamedOrFail], for a handler that decides asynchronously. */
infix fun <I, E : Any, T> Endpoint<I, Outcome<E, StreamOf<T>>>.streamedByOrFail(
    f: Params.(I) -> CompletionStage<Outcome<E, Sequence<T>>>,
): ServerEndpoint = ServerEndpoint(this) { p -> p.f(inputs.extract(p)).thenApply { it as Any? } }

/** Escape hatch: the io_uring runtime's request behind this call. */
val Params.request: HttpRequest
    get() = underlying as HttpRequest

/** Binds an endpoint that streams opaque bytes, one write per element. Synchronous: see [Handlers] for where. */
infix fun <I> Endpoint<I, ByteStream>.bytesNow(f: Params.(I) -> Sequence<ByteArray>): ServerEndpoint =
    ServerEndpoint(this, Synchronous { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) })

/** Binds an endpoint streaming opaque bytes that may fail before the first one. */
infix fun <I, E : Any> Endpoint<I, Outcome<E, ByteStream>>.bytesOrFail(
    f: Params.(I) -> Outcome<E, Sequence<ByteArray>>,
): ServerEndpoint =
    ServerEndpoint(this, Synchronous { p -> CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?) })
