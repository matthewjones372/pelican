package io.github.matthewjones372.pelican.uring

import io.github.matthewjones372.pelican.Method

/**
 * One step of serving: answers [HttpExchange] and returns true, or returns false having touched nothing, so the
 * handler after it in a [then] chain is asked. Runs on the carrier, so it must not block: see [HttpHandler].
 */
fun interface UringHandler {
    fun handle(exchange: HttpExchange): Boolean
}

/** This handler, then [next] for whatever it passes on: Jetty's `Handler.Sequence`, Pekko's `concat`. */
infix fun UringHandler.then(next: UringHandler): UringHandler =
    UringHandler { exchange -> handle(exchange) || next.handle(exchange) }

/**
 * This handler, asked only for [method], as Pekko's `method` directive asks its route. Any other method is passed
 * on as a refusal naming [method], so a 405 lists it; a request it passes on despite the method matching is a 404.
 */
fun UringHandler.onlyFor(method: Method): UringHandler = UringHandler { exchange ->
    if (exchange.request.method != method.name) {
        exchange.declineMethods(listOf(method.name))
        false
    } else {
        handle(exchange).also { taken -> if (!taken) exchange.declinePath() }
    }
}

/** What the handlers that passed on one request said about it, merged as Pekko merges its routes' rejections. */
private data class Declines(val methods: List<String> = emptyList(), val methodTaken: Boolean = false)

/** The exchange [start] hands its handler chain, carrying what each handler that passed on said. */
internal class Asked(private val exchange: HttpExchange) : HttpExchange by exchange {
    @Volatile
    private var declines = Declines()

    fun declineMethods(methods: List<String>) {
        declines = declines.copy(methods = declines.methods + methods)
    }

    fun declinePath() {
        declines = declines.copy(methodTaken = true)
    }

    /** The 404 or 405 Pekko gives for its routes' merged rejections. */
    fun unanswered(): Strict =
        if (declines.methodTaken || declines.methods.isEmpty()) NOT_FOUND else methodNotAllowed(declines.methods)
}

/** Passed on by a handler answering only [methods]: one Pekko `MethodRejection` each. */
internal fun HttpExchange.declineMethods(methods: List<String>) {
    (this as? Asked)?.declineMethods(methods)
}

/** Passed on by a handler that takes this method but not this path. As Pekko's does, it makes the answer a 404. */
internal fun HttpExchange.declinePath() {
    (this as? Asked)?.declinePath()
}

/** [handler] as the runtime's handler: whatever every step passes on is answered with the merged 404 or 405. */
internal fun answering(handler: UringHandler): HttpHandler = HttpHandler { exchange ->
    val asked = Asked(exchange)
    if (!handler.handle(asked)) asked.unanswered().writeTo(asked)
}
