package io.github.matthewjones372.pelican.jetty

import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.thread.Invocable

/** How much of a body nobody read is read after the response, so the connection survives it. */
private const val UNREAD_DRAIN_BYTES: Long = 1024L * 1024L

/**
 * [callback], succeeded once whatever is left of [request]'s body has been read and thrown away.
 *
 * Jetty finds a body unread when the response completes and closes the connection: after a response that has
 * already gone out without `Connection: close`, and with the client still writing, which resets it (spec 0052).
 * The response is written first, so the caller is answered as early as before. Past [UNREAD_DRAIN_BYTES], or on
 * a failed read, the rest is left to Jetty's close.
 */
internal fun Callback.afterReadingRest(request: Request): Callback {
    val callback = this
    return object : Callback {
        override fun succeeded() = Drain(request, callback).run()

        override fun failed(x: Throwable) = callback.failed(x)

        override fun getInvocationType(): Invocable.InvocationType = callback.invocationType
    }
}

/** Reads on Jetty's demand callbacks, so a body that has not arrived yet holds no thread. */
private class Drain(private val request: Request, private val callback: Callback) : Runnable, Invocable {
    private var drained = 0L

    override fun run() {
        while (true) {
            val chunk = request.read()
            if (chunk == null) {
                request.demand(this)
                return
            }
            val failed = Content.Chunk.isFailure(chunk)
            drained += chunk.remaining()
            chunk.release()
            if (failed || chunk.isLast || drained > UNREAD_DRAIN_BYTES) {
                callback.succeeded()
                return
            }
        }
    }

    override fun getInvocationType(): Invocable.InvocationType = Invocable.InvocationType.NON_BLOCKING
}
