package io.github.matthewjones372.pelican

import java.util.concurrent.CompletionException

/**
 * A handler produced a response its endpoint never declared.
 *
 * Not something a request can provoke: it needs a handler returning an
 * `ErrorOutput` or an `Output` belonging to another endpoint, which the type
 * system permits whenever `E` has widened to a sealed supertype. So it is a bug
 * in the service rather than in a request, and it is deliberately *not* in the
 * table below: naming the endpoint and the responses it declared tells a caller
 * about the inside of a service, and `ClientContractTest` is explicit that this
 * is what a caller must not be handed. So it takes the ordinary unexpected
 * path — a reference in the body, the whole message to `onServerError`, and the
 * detail back only under `exposeInternalErrors`.
 *
 * The type is its own so that a handler's bookkeeping mistake can be told apart
 * from an arbitrary throwable by whatever is watching the hook.
 */
class UndeclaredResponse(message: String) : RuntimeException(message)

internal fun unwrapCompletion(t: Throwable): Throwable =
    if (t is CompletionException) t.cause?.let(::unwrapCompletion) ?: t else t
