package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.RouteIndex
import io.github.matthewjones372.pelican.ServerEndpoint
import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.routing.RouteMatcher
import org.http4k.routing.Router
import org.http4k.routing.RouterDescription
import org.http4k.routing.RoutingMatch

/**
 * The whole API as one thing http4k can ask, instead of a list it has to walk.
 *
 * `routes(...)` tries its entries in turn, because a handler is opaque and
 * there is nothing else it could do — which cost about 150µs a request at two
 * hundred endpoints, the same as those routes registered by hand. A
 * [RouteMatcher] is the seam that lets a description-shaped router take its
 * place: http4k asks one question and this answers it from the trie.
 *
 * The three answers are http4k's own, and keeping them is the point. A path
 * this API describes under this method is a match; a path it describes under
 * some other method is `NOT_MATCHED` carrying 405, which is what makes http4k
 * the backend that tells those apart; anything else is unmatched, so a Pelican
 * handler combined with routes of somebody else's still passes them on.
 */
internal class IndexedRouteMatcher(
    private val index: RouteIndex,
    private val invoke: (ServerEndpoint, Request, MutableMap<ParamKey<*>, Any?>) -> Response,
    private val filter: Filter = Filter { it },
    private val prefix: String = "",
) : RouteMatcher<Response, Filter> {

    override fun match(request: Request): RoutingMatch<Response> {
        val method = request.method.toPelican() ?: return unmatched()
        val path = request.uri.path.removePrefix(prefix)

        // Held for the handler below rather than decoded twice: this is the
        // walk, and http4k invokes the result a moment later.
        val values = LinkedHashMap<ParamKey<*>, Any?>()
        val matched = try {
            index.match(method, path, values)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // A capture that will not decode is this endpoint's 400 rather than
            // somebody else's route, so it counts as matched and answers.
            return RoutingMatch(MATCHED, description) { errorResponse(t, null) }
        }

        return when {
            matched != null -> RoutingMatch(MATCHED, description) { req ->
                filter.then { _: Request -> invoke(matched, req, values) }(req)
            }

            // Described, under a different verb: the distinction http4k draws
            // and the other two backends do not.
            index.describesPath(path) ->
                RoutingMatch(NOT_MATCHED, description) { Response(Status.METHOD_NOT_ALLOWED) }

            else -> unmatched()
        }
    }

    private fun unmatched(): RoutingMatch<Response> =
        RoutingMatch(UNMATCHED, description) { Response(Status.NOT_FOUND) }

    override fun withBasePath(prefix: String): RouteMatcher<Response, Filter> =
        IndexedRouteMatcher(index, invoke, filter, prefix + this.prefix)

    override fun withFilter(new: Filter): RouteMatcher<Response, Filter> =
        IndexedRouteMatcher(index, invoke, new.then(filter), prefix)

    /**
     * Narrowing by a predicate is not expressible here: the answer comes from
     * the descriptions, and a router that also had to agree with an arbitrary
     * predicate would be answering two questions. Nothing in Pelican calls it.
     */
    override fun withRouter(other: Router): RouteMatcher<Response, Filter> = this

    private val description get() = RouterDescription("pelican")

    private companion object {
        const val MATCHED = 0
        const val NOT_MATCHED = 1
        const val UNMATCHED = 2
    }
}

/** http4k's verbs that Pelican describes; anything else is not ours to answer. */
private fun org.http4k.core.Method.toPelican(): Method? = when (this) {
    org.http4k.core.Method.GET -> Method.GET
    org.http4k.core.Method.POST -> Method.POST
    org.http4k.core.Method.PUT -> Method.PUT
    org.http4k.core.Method.PATCH -> Method.PATCH
    org.http4k.core.Method.DELETE -> Method.DELETE
    org.http4k.core.Method.HEAD -> Method.HEAD
    org.http4k.core.Method.OPTIONS -> Method.OPTIONS
    else -> null
}
