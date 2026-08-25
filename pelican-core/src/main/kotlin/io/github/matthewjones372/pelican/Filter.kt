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

/**
 * As [after], but told the status rather than the value the handler returned.
 * For an access log or a request metric, which want the number that is going
 * out and have no business inferring it from the payload's type — see
 * [Endpoint.statusFor] for how it is arrived at, and for the one case it
 * cannot see.
 *
 * It differs from [after] in one way beyond the status, and the difference
 * matters for what this is for. A filter that rejects does so by throwing, and
 * a `before` throws where it stands rather than failing a stage — so the
 * throwable leaves the chain past anything built on `handle` alone, which is
 * why [after] never sees a refusal raised further in. A 401 or a 403 is exactly
 * the request an access log or a rate-of-refusals graph is there for, so this
 * one catches that case as well.
 *
 * [action] does not run when the request matched no description, which happens
 * only for a hand-built [Params]: the status is read off the endpoint, and
 * there is nothing to read it off. Anything logging or measuring per endpoint
 * has nothing to say about such a request either.
 */
fun afterStatus(action: (params: Params, status: Int, error: Throwable?) -> Unit): Filter =
    Filter { params, next ->
        val endpoint = params.endpoint
        if (endpoint == null) {
            next(params)
        } else {
            attempt(params, next).handle { result, error ->
                val failure = error?.unwrapCompletion()
                action(params, endpoint.statusFor(result, failure), failure)
                if (error != null) throw error
                result
            }
        }
    }

/**
 * The rest of the chain as a stage that *fails* rather than throws, so a single
 * `handle` covers both ways a request can end.
 *
 * A filter that must see every ending calls `next` through this rather than
 * directly. Rejecting is throwing, and a `before` throws where it stands rather
 * than failing a stage, so a refusal raised further in leaves the chain past
 * anything built on `handle` alone. `runCatching` rather than a catch clause
 * because what is being caught here is genuinely everything: a filter's
 * refusal, a decoding mistake made on the way in, and whatever else a handler
 * manages to raise before it returns a stage at all. Nothing is swallowed — the
 * throwable comes back out of the stage, which is where the interpreter is
 * already looking for it.
 */
fun attempt(params: Params, next: (Params) -> CompletionStage<Any?>): CompletionStage<Any?> =
    runCatching { next(params) }.getOrElse { CompletableFuture.failedFuture(it) }

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
