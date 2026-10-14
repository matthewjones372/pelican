package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.ServerEndpoint
import org.eclipse.jetty.server.Request
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

/** Escape hatch: the raw Jetty request behind this call. */
val Params.request: Request
    get() = underlying as Request
