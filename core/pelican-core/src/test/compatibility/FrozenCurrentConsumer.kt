package compatibility

import io.github.matthewjones372.pelican.Api

class FrozenCurrentConsumer {
    fun full(a: Api): Api = Api(
        a.endpoints, a.codecs, a.title, a.version, a.description, a.servers, a.security, a.cors,
        a.strictBodyTimeoutMillis, a.maxBodyBytes, a.maxFrameBytes, a.filters, a.exposeInternalErrors,
        a.onServerError, a.covers, a.webhooks, a.refusals, a.onRefusal, a.pages, a.authenticators, a.idempotency,
        a.csrf, a.tenants, a.rateLimits, a.responseHeaders,
    )
}
