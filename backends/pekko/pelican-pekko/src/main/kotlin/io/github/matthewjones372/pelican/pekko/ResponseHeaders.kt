package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ResponseHeaderPolicy
import org.apache.pekko.http.javadsl.model.AttributeKey
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.http.javadsl.model.headers.RawHeader
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route

/**
 * This route, with [api]'s `responseHeaders { }` on every response it completes. [isDocs] marks a route serving the
 * docs modules' document and pages. [toRoute] already does this for the endpoints; the 404 or 405 Pekko renders for a
 * rejection is [sealedWith]'s.
 */
fun Route.withResponseHeaders(api: Api, isDocs: Boolean = false): Route {
    val policy = api.responseHeaders ?: return this
    return answeredUnder(policy, isDocs) { null }
}

/**
 * This route sealed as `start` binds it: Pekko's own rejection and exception handling, with [api]'s
 * `responseHeaders { }` on the responses they render. One already given them is left alone.
 */
fun Route.sealedWith(api: Api): Route {
    val sealed = seal()
    val policy = api.responseHeaders ?: return sealed
    return Directives.extractRequest { request ->
        Directives.mapResponse({ res ->
            if (res.getAttribute(ANSWERED).isPresent) res else res.answered(policy, request, null, isDocs = false)
        }) { sealed }
    }
}

/** [withResponseHeaders], told which endpoint answered. */
internal fun Route.answeredUnder(
    policy: ResponseHeaderPolicy,
    isDocs: Boolean,
    endpointOf: (HttpRequest) -> Endpoint<*, *>?,
): Route {
    val inner = this
    return Directives.extractRequest { request ->
        Directives.mapResponse({ res -> res.answered(policy, request, endpointOf(request), isDocs) }) { inner }
    }
}

private fun HttpResponse.answered(
    policy: ResponseHeaderPolicy,
    request: HttpRequest,
    endpoint: Endpoint<*, *>?,
    isDocs: Boolean,
): HttpResponse {
    val headers = policy.headersFor(request.uri.getPathString(), endpoint, status().intValue(), isDocs) { name ->
        getHeader(name).isPresent
    }
    return addHeaders(headers.map { (name, value) -> RawHeader.create(name, value) }).addAttribute(ANSWERED, true)
}

/** Marks a response the policy has already seen, so sealing does not add its headers a second time. */
private val ANSWERED: AttributeKey<Boolean> =
    AttributeKey.create("pelican.responseHeaders", Boolean::class.javaObjectType)
