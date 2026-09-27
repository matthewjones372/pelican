package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Params
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/**
 * Where a synchronous handler — `handledNow`, `handledOrFail`, `handledWith`, `handledOneOf` — runs, chosen when the
 * API is started (spec 0058). An asynchronous handler already chose, and is never moved.
 */
class Handlers private constructor(internal val executor: Executor?) {
    companion object {
        /** A virtual thread per request, so a handler may block without holding a dispatcher thread. */
        val onVirtualThreads: Handlers = Handlers { task -> Thread.ofVirtual().name("pelican-handler").start(task) }

        /** Where the route matched, as before 0058: only for handlers that never block. */
        val onDispatcher: Handlers = Handlers(null)

        /** On [executor], for a service that bounds or instruments its own. */
        fun on(executor: Executor): Handlers = Handlers(executor)
    }
}

/** A handler that answers on the thread it is called on, which the interpreter may move off the dispatcher. */
internal class Synchronous(private val handler: (Params) -> CompletionStage<Any?>) : (Params) -> CompletionStage<Any?> {
    override fun invoke(params: Params): CompletionStage<Any?> = handler(params)
}
