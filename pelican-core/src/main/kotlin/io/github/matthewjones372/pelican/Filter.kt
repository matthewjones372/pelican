package io.github.matthewjones372.pelican

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Runs around every handler: authentication, rate limiting, a request log.
 */
fun interface Filter {
    fun handle(params: Params, next: (Params) -> CompletionStage<Any?>): CompletionStage<Any?>
}

/**
 * Look at the request, throw to reject it, return to let it through. Nothing to
 * remember about calling `next`, which is easy to forget and invisible missing.
 */
fun before(check: (Params) -> Unit): Filter = Filter { params, next ->
    check(params)
    next(params)
}

/**
 * Runs [action] once the handler has answered, whatever it answered. For a
 * timer or an access log — [result] is null when the handler threw, and the
 * throwable is [error].
 */
fun after(action: (params: Params, result: Any?, error: Throwable?) -> Unit): Filter =
    Filter { params, next ->
        next(params).handle { result, error ->
            action(params, result, error?.unwrapCompletion())
            if (error != null) throw error
            result
        }
    }

/** Narrows a filter to the endpoints [predicate] accepts. */
fun Filter.onlyWhen(predicate: (Endpoint<*, *>) -> Boolean): Filter = Filter { params, next ->
    val ep = params.endpoint
    if (ep != null && !predicate(ep)) next(params) else handle(params, next)
}

/**
 * Folds the chain into one function. The first filter ends up outermost, so
 * the list reads in the order the request travels.
 */
fun List<Filter>.wrap(handler: (Params) -> CompletionStage<Any?>): (Params) -> CompletionStage<Any?> =
    foldRight(handler) { filter, next -> { params -> filter.handle(params, next) } }

/**
 * Somewhere to put what a filter worked out, so the handler reads it back
 * without going to the raw request again.
 */
class Attribute<T> internal constructor(val name: String) {
    override fun toString() = "attribute:$name"
}

fun <T> attribute(name: String): Attribute<T> = Attribute(name)

private fun Throwable.unwrapCompletion(): Throwable =
    if (this is java.util.concurrent.CompletionException) cause?.unwrapCompletion() ?: this else this

/**
 * `completedFuture`, not `completedStage`: the latter returns a *minimal*
 * stage, whose `toCompletableFuture()` allocates a second future — and every
 * synchronous handler goes through exactly that call on the way out.
 */
internal fun completed(value: Any?): CompletionStage<Any?> = CompletableFuture.completedFuture(value)
