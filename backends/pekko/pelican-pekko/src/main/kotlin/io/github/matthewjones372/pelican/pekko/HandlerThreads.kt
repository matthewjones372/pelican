package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Params
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Where a synchronous handler — `handledNow`, `handledOrFail`, `handledWith`, `handledOneOf` — runs, chosen when the
 * API is started (spec 0058). An asynchronous handler already chose, and is never moved.
 */
class Handlers private constructor(internal val executor: Executor?) {
    companion object {
        /**
         * A virtual thread per request, so a handler may block without holding a dispatcher thread. The JDK's own
         * thread-per-task executor, which the OTel agent already carries a context across.
         */
        val onVirtualThreads: Handlers = Handlers(Executors.newVirtualThreadPerTaskExecutor())

        /** Where the route matched, as before 0058: only for handlers that never block. */
        val onDispatcher: Handlers = Handlers(null)

        /** On [executor], for a service that bounds or instruments its own. */
        fun on(executor: Executor): Handlers = Handlers(executor)

        /**
         * What every `handlers` parameter defaults to: [onVirtualThreads] from JDK 24, where a virtual thread blocked
         * in `synchronized` no longer pins its carrier (JEP 491), and a pool of platform threads below it, where a
         * handler blocking under a monitor would hold one of the few carriers every virtual thread shares.
         */
        val default: Handlers by lazy {
            if (Runtime.version().feature() >= UNPINNED_FROM) onVirtualThreads else Handlers(platformThreads())
        }
    }
}

/** A handler that answers on the thread it is called on, which the interpreter may move off the dispatcher. */
internal class Synchronous(private val handler: (Params) -> CompletionStage<Any?>) : (Params) -> CompletionStage<Any?> {
    override fun invoke(params: Params): CompletionStage<Any?> = handler(params)
}

/** The first JDK whose virtual threads do not pin their carrier inside `synchronized`. */
private const val UNPINNED_FROM = 24

/** Jetty's default pool size: the most handlers below [UNPINNED_FROM] that run at once before the rest queue. */
private const val PLATFORM_THREADS = 200
private const val IDLE_SECONDS = 60L

/** Daemon threads, so a service that never stops its server still exits; idle ones end after a minute. */
private fun platformThreads(): Executor =
    ThreadPoolExecutor(
        PLATFORM_THREADS,
        PLATFORM_THREADS,
        IDLE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        Thread.ofPlatform().name("pelican-handler-", 0).daemon().factory(),
    ).apply { allowCoreThreadTimeOut(true) }
