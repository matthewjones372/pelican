package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Params
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Where a synchronous handler (`handledNow`, `handledWith`, `streamedNow`) runs, chosen when the API is started.
 * An asynchronous handler already chose, and is never moved.
 */
class Handlers private constructor(
    internal val executor: Executor?,
    /** Whether any virtual thread will do, so a handler Jetty already called on one stays there. */
    internal val virtual: Boolean = false,
) {
    companion object {
        /**
         * A virtual thread per request, so a handler may block without holding one of Jetty's pool threads. [start]
         * has Jetty call handlers on virtual threads already, so this costs no hop there.
         */
        val onVirtualThreads: Handlers = Handlers(Executors.newVirtualThreadPerTaskExecutor(), virtual = true)

        /** On the Jetty thread that matched the request: only for handlers that never block. */
        val onDispatcher: Handlers = Handlers(null)

        /** On [executor], for a service that bounds or instruments its own. */
        fun on(executor: Executor): Handlers = Handlers(executor)
    }
}

/** A handler that answers on the thread it is called on, which the interpreter may move off Jetty's pool. */
internal class Synchronous(private val handler: (Params) -> CompletionStage<Any?>) : (Params) -> CompletionStage<Any?> {
    override fun invoke(params: Params): CompletionStage<Any?> = handler(params)
}
