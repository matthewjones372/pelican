package compatibility

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiBuilder
import io.github.matthewjones372.pelican.api
import kotlin.time.toKotlinDuration

class FrozenConsumer {
    fun full(a: Api): Api = Api(
        a.endpoints, a.codecs, a.title, a.version, a.description, a.servers, a.security, a.cors,
        a.strictBodyTimeoutMillis, a.maxBodyBytes, a.maxFrameBytes, a.filters, a.exposeInternalErrors,
        a.onServerError, a.covers, a.webhooks, a.refusals, a.onRefusal, a.pages, a.authenticators, a.idempotency,
    )
    fun named(a: Api): Api = Api(
        a.endpoints, title = a.title, maxBodyBytes = a.maxBodyBytes, idempotency = a.idempotency,
    )
    fun defaults(a: Api): Api = Api(a.endpoints)
    fun factory(a: Api): Api = api(a.endpoints, a.codecs) { copySettings(a) }
    fun factoryDefaults(a: Api): Api = api(a.endpoints)
    fun builder(a: Api): Api = ApiBuilder().apply { copySettings(a) }.build(a.endpoints, a.codecs)

    private fun ApiBuilder.copySettings(a: Api) {
        title = a.title
        version = a.version
        description = a.description
        servers = a.servers
        security = a.security
        cors = a.cors
        strictBodyTimeoutMillis = a.strictBodyTimeoutMillis
        maxBodyBytes = a.maxBodyBytes
        maxFrameBytes = a.maxFrameBytes
        exposeInternalErrors = a.exposeInternalErrors
        covers = a.covers
        webhooks = a.webhooks
        pages = a.pages
        a.filters.forEach(::filter)
        a.onServerError?.let(::onError)
        refusals(a.refusals)
        a.onRefusal?.let(::onRefusal)
        a.authenticators.forEach { (scheme, by) -> authenticate(scheme, by) }
        a.idempotency?.let { idempotency(it.store, it.ttl.toKotlinDuration(), it.lease.toKotlinDuration()) }
    }
}
