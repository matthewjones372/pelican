package dev.pelican.arrow

import arrow.core.raise.Raise
import dev.pelican.Endpoint
import dev.pelican.Fallible
import dev.pelican.Outcome
import dev.pelican.Params
import dev.pelican.ServerEndpoint
import dev.pelican.ok
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Binders for handlers written in Arrow's [Raise] style.
 *
 * The endpoint side is unchanged: a handler bound here fits exactly the same
 * `Endpoint<I, Fallible<E, T>>` that `handledOrFail` fits, produces the same
 * responses and documents the same failures. What changes is how the handler
 * says "no": `raise(UnknownUser(id))` from wherever it noticed, rather than a
 * value returned along every path in between.
 *
 * ```
 * import dev.pelican.arrow.*                      // raise/ensure live here, see Dsl.kt
 *
 * val getUser = endpoint(GET, "users" / userId)
 *     .out(json<User>() orFail noSuchUser)
 *
 * getUser.handledRaise(toResponse) { id ->
 *     val user = Users.find(id) ?: raise(UnknownUser(id))
 *     setHeader(cacheControl, "max-age=60")     // Params is still the receiver
 *     user
 * }
 * ```
 *
 * These are functions of two arguments rather than `infix`, because the error
 * mapping is not optional: [ErrorMapper] is what gives a raised value a status.
 *
 * Every backend can use these — binding a value-returning handler names no
 * server type. Streaming handlers do name one, so `streamedRaise` lives in
 * pelican-arrow-pekko, pelican-arrow-http4k and pelican-arrow-ktor, and the
 * Ktor module also has the `suspend` twins of everything here.
 *
 * Callers write a lambda with a context parameter in it, so a module using
 * these compiles with `-Xcontext-parameters`:
 *
 * ```
 * tasks.withType<KotlinCompile>().configureEach {
 *     compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
 * }
 * ```
 */

/** Binds an endpoint whose handler returns [T] or raises one of the declared failures. */
fun <I, E, F : Any, T : Any> Endpoint<I, Fallible<F, T>>.handledRaise(
    mapErr: ErrorMapper<E, F>,
    f: context(Raise<E>) Params.(I) -> T,
): ServerEndpoint = ServerEndpoint(this) { p ->
    CompletableFuture.completedStage(p.raising(inputs.extract(p), mapErr, f) as Any?)
}

/**
 * As [handledRaise], for a handler that answers through a [CompletionStage].
 *
 * The raise happens while the stage is being *started*, not when it completes:
 * `raise(...)` is a synchronous jump, and there is nowhere for it to land once
 * the handler has returned. So this fits "check what you can, then hand back
 * the work" — the shape most such handlers already have. A failure only a
 * completed stage can know about is a [Outcome] the stage itself yields, which
 * is what the backend's own `handledByOrFail` takes.
 */
fun <I, E, F : Any, T : Any> Endpoint<I, Fallible<F, T>>.handledByRaise(
    mapErr: ErrorMapper<E, F>,
    f: context(Raise<E>) Params.(I) -> CompletionStage<T>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    when (val started = p.raising(inputs.extract(p), mapErr, f)) {
        is Outcome.Err -> CompletableFuture.completedStage(started as Any?)
        is Outcome.Ok -> started.value.thenApply { ok(it) as Any? }
    }
}
