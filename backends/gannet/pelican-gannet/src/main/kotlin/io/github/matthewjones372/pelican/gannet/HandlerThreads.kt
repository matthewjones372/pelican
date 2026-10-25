package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.pelican.Params
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Where a synchronous handler (`handledNow`, `handledWith`, `streamedNow`) runs, with its filters and authenticator,
 * chosen when the API is started. An asynchronous handler already chose, and is never moved.
 */
class Handlers private constructor(internal val executor: Executor?) {
    companion object {
        /** A virtual thread per request, so a handler may block without holding up the carrier that read it. */
        val onVirtualThreads: Handlers = Handlers(HANDLER_THREADS)

        /**
         * Inline, in the connection's fiber on the carrier: no hop at all, and a handler that blocks stalls every
         * connection on that core. Only for handlers that never block.
         */
        val onDispatcher: Handlers = Handlers(null)

        /** On [executor], for a service that bounds or instruments its own. */
        fun on(executor: Executor): Handlers = Handlers(executor)

        /** What every `handlers` parameter defaults to: [onVirtualThreads], which JDK 25 never pins (JEP 491). */
        val default: Handlers = onVirtualThreads
    }
}

/** A handler that answers on the thread it is called on, which the interpreter may move off the carrier. */
internal class Synchronous(private val handler: (Params) -> CompletionStage<Any?>) : (Params) -> CompletionStage<Any?> {
    override fun invoke(params: Params): CompletionStage<Any?> = handler(params)
}

private val HANDLER_THREADS: Executor =
    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pelican-handler-", 0).factory())

/** Where whatever may block goes when the handlers themselves stay on the carrier: stream reads and writes. */
internal val OFF_CARRIER: Executor =
    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pelican-stream-", 0).factory())
