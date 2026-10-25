package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Method
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback

/** What the handlers that passed on one request said about it, merged as Pekko merges its routes' rejections. */
private data class Declines(val methods: List<String> = emptyList(), val methodTaken: Boolean = false)

private const val DECLINES = "io.github.matthewjones372.pelican.jetty.declines"

private fun Request.declines(): Declines = getAttribute(DECLINES) as? Declines ?: Declines()

/** Passed on by a handler answering only [methods]: one Pekko `MethodRejection` each. */
internal fun Request.declineMethods(methods: List<String>) {
    val seen = declines()
    setAttribute(DECLINES, seen.copy(methods = seen.methods + methods))
}

/** Passed on by a handler that takes this method but not this path. As Pekko's does, it makes the answer a 404. */
internal fun Request.declinePath() {
    setAttribute(DECLINES, declines().copy(methodTaken = true))
}

/**
 * This handler, asked only for [method], as Pekko's `method` directive asks its route. Any other method is passed
 * on as a refusal naming [method], so a 405 lists it; a request it passes on despite the method matching is a 404.
 */
fun Handler.onlyFor(method: Method): Handler = object : Handler.Wrapper(this) {
    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        if (request.method != method.name) {
            request.declineMethods(listOf(method.name))
            return false
        }
        return super.handle(request, response, callback).also { taken -> if (!taken) request.declinePath() }
    }
}

/**
 * Answers a request every handler before it passed on, with the 404 or 405 Pekko gives for its routes' merged
 * rejections. [start] installs one as the server's default handler; a server built by hand sets
 * `server.defaultHandler = Unanswered()`, or Jetty answers its own 404 and a refused method is never a 405.
 */
class Unanswered : Handler.Abstract.NonBlocking() {
    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        val seen = request.declines()
        val reply = if (seen.methodTaken || seen.methods.isEmpty()) NOT_FOUND else methodNotAllowed(seen.methods)
        reply.writeTo(response, callback.afterReadingRest(request))
        return true
    }
}
