package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Params
import org.eclipse.jetty.server.internal.HttpConnection
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
         * A virtual thread per request, so a handler may block without holding one of Jetty's pool threads. One Jetty
         * already called on a virtual thread stays there; one called on its selector hops.
         */
        val onVirtualThreads: Handlers = Handlers(Executors.newVirtualThreadPerTaskExecutor(), virtual = true)

        /** On the Jetty thread that matched the request: only for handlers that never block. */
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

/** A handler that answers on the thread it is called on, which the interpreter may move off Jetty's pool. */
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

/** Connections Jetty asked to resume while this thread finished a request it hopped to. */
private val resumes = ThreadLocal<ArrayDeque<Runnable>?>()

/**
 * [work], then any connection Jetty asked to resume meanwhile, on this thread. A request answered off the thread Jetty
 * called makes its connection resume reading through a dispatch to the pool; here, where [work] has just finished, it
 * costs none, so a hopped synchronous handler costs one dispatch, as a request Jetty dispatched itself does.
 */
internal fun <T> resumingHere(work: () -> T): T {
    val own = ArrayDeque<Runnable>()
    resumes.set(own)
    try {
        return work()
    } finally {
        resumes.remove()
        while (own.isNotEmpty()) own.removeFirst().run()
    }
}

/** Whether [job], a connection resuming, was taken to run at the end of [resumingHere] on this thread. */
internal fun resumedHere(job: Runnable): Boolean {
    val own = resumes.get()
    if (own == null || job !is HttpConnection) return false
    own.addLast(job)
    return true
}
