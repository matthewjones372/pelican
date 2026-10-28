package io.github.matthewjones372.pelican

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * The endpoints this service serves, the codecs that carry their bodies, and
 * the settings shared between them.
 *
 * A factory over a builder rather than a constructor with a parameter per
 * setting. Adding a parameter to a constructor with defaults changes both its
 * descriptor and the synthetic one Kotlin emits to carry the defaults, so every
 * new server setting would break every caller compiled against the last
 * release. Adding a `var` to [ApiBuilder] breaks nobody.
 */
fun api(
    endpoints: List<ServerEndpoint>,
    codecs: Codecs = NoCodecs,
    configure: ApiBuilder.() -> Unit = {},
): Api = ApiBuilder().apply(configure).build(endpoints, codecs)

/**
 * What [api]'s block writes into. Each setting is documented on the [Api]
 * property it becomes.
 *
 * It is live for exactly as long as that block runs: [build] copies what it was
 * given, so a reference kept past the block writes into a value nobody reads.
 */
class ApiBuilder internal constructor() {

    var title: String = DEFAULT_TITLE
    var version: String = DEFAULT_VERSION
    var description: String? = null
    var servers: List<String> = emptyList()
    var security: List<SecurityRequirement> = emptyList()
    var cors: Cors? = null

    /**
     * Refuses a POST, PUT, PATCH or DELETE from another site whose caller is a cookie, which a browser attaches to
     * a request another site makes it send. On unless turned off; an origin [cors] lets send credentials is let in.
     */
    var csrf: Boolean = true
    var strictBodyTimeoutMillis: Long = DEFAULT_STRICT_BODY_TIMEOUT_MILLIS
    var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES

    /** Null derives it from [maxBodyBytes]; see [Api.maxFrameBytes]. */
    var maxFrameBytes: Long? = null
    var exposeInternalErrors: Boolean = false
    var covers: List<Endpoint<*, *>> = emptyList()
    var webhooks: List<Webhook> = emptyList()

    /** [Api.pages]: `pages = pages("ui")`. */
    var pages: Pages? = null

    private val filters = mutableListOf<Filter>()
    private var onServerError: ((reference: String, endpoint: Endpoint<*, *>?, error: Throwable) -> Unit)? = null
    private var refusals: RefusalRenderer = ApiErrorEnvelope
    private var onRefusal: RefusalObserver? = null
    private val authenticators = LinkedHashMap<SecurityScheme, Authenticator>()
    private var idempotency: Idempotency? = null

    /** Outermost first: a chain is written a line at a time, in the order it runs. */
    fun filter(filter: Filter) {
        filters += filter
    }

    /** [Api.onServerError]. */
    fun onError(handler: (reference: String, endpoint: Endpoint<*, *>?, error: Throwable) -> Unit) {
        onServerError = handler
    }

    /** [Api.refusals] — `refusals(ProblemDetails)` for RFC 9457. */
    fun refusals(renderer: RefusalRenderer) {
        refusals = renderer
    }

    /** [Api.authenticators]: what verifies a caller declared under [scheme]. */
    fun authenticate(scheme: SecurityScheme, by: Authenticator) {
        authenticators[scheme] = by
    }

    /**
     * [Api.idempotency]: keeps what keyed endpoints answered in [store] for [ttl]. A request still running after
     * [lease] is taken to have died, and a repeat of it runs in its place.
     */
    fun idempotency(
        store: IdempotencyStore,
        ttl: kotlin.time.Duration = 24.hours,
        lease: kotlin.time.Duration = 1.minutes,
    ) {
        idempotency = Idempotency(store, ttl.toJavaDuration(), lease.toJavaDuration())
    }

    /**
     * How tenanted endpoints find their tenant, and what says it exists. One per `Api`. A caller whose [claim] names
     * another tenant is a 403; null checks no claim.
     */
    fun tenants(input: TenantInput, directory: TenantDirectory, claim: String? = "tenant") {
        tenants = Tenants(input, directory, claim)
    }

    private var tenants: Tenants? = null

    /** [Api.rateLimits]: where the budgets rate-limited endpoints spend are counted. */
    fun rateLimits(store: RateLimitStore) {
        rateLimits = store
    }

    private var rateLimits: RateLimitStore? = null

    /**
     * [Api.responseHeaders]: [rule] is called for each response as it goes out, and what it [AnswerHeaders.add]s is
     * sent unless the response already carries that name.
     */
    fun responseHeaders(rule: AnswerHeaders.(Answer) -> Unit) {
        responseHeaders = ResponseHeaderPolicy(rule)
    }

    private var responseHeaders: ResponseHeaderPolicy? = null

    /**
     * [Api.callerAddressHeader]: [Params.callerAddress] is the first entry of [trustHeader] where a request carries
     * it, and the socket peer where it does not. Name only the header your own proxy writes.
     */
    fun callerAddress(trustHeader: String) {
        require(trustHeader.isNotBlank()) { "Name the header your proxy writes the client's address in" }
        callerAddressHeader = trustHeader
    }

    private var callerAddressHeader: String? = null

    /** [Api.onRefusal] — `onRefusal(refusalCounter(registry))` for the meter. */
    fun onRefusal(observer: RefusalObserver) {
        onRefusal = observer
    }

    internal fun build(endpoints: List<ServerEndpoint>, codecs: Codecs): Api = Api(
        endpoints = endpoints,
        codecs = codecs,
        title = title,
        version = version,
        description = description,
        servers = servers,
        security = security,
        cors = cors,
        strictBodyTimeoutMillis = strictBodyTimeoutMillis,
        maxBodyBytes = maxBodyBytes,
        maxFrameBytes = maxFrameBytes,
        filters = filters.toList(),
        exposeInternalErrors = exposeInternalErrors,
        onServerError = onServerError,
        covers = covers,
        webhooks = webhooks,
        refusals = refusals,
        onRefusal = onRefusal,
        pages = pages,
        authenticators = authenticators.toMap(),
        idempotency = idempotency,
        csrf = csrf,
        tenants = tenants,
        rateLimits = rateLimits,
        responseHeaders = responseHeaders,
        callerAddressHeader = callerAddressHeader,
    )
}

/**
 * The endpoints a document describes, the schema source that describes their
 * payloads, and the document-level settings — [api]'s shape, for the build
 * that never starts a server.
 *
 * A factory over a builder for [api]'s reason: a document will want more
 * settings after 1.0 — a contact, a license — and a constructor with defaults
 * freezes twice over.
 */
fun apiSpec(
    endpoints: List<Endpoint<*, *>>,
    schemas: SchemaSource,
    configure: ApiSpecBuilder.() -> Unit = {},
): ApiSpec = ApiSpecBuilder().apply(configure).build(endpoints, schemas)

/** What [apiSpec]'s block writes into. Each setting is documented on the [ApiSpec] property it becomes. */
class ApiSpecBuilder internal constructor() {

    var title: String = DEFAULT_TITLE
    var version: String = DEFAULT_VERSION
    var description: String? = null
    var servers: List<String> = emptyList()
    var security: List<SecurityRequirement> = emptyList()
    var webhooks: List<Webhook> = emptyList()

    private var refusals: RefusalRenderer = ApiErrorEnvelope

    /** [ApiSpec.refusals] — the same spelling [api]'s block uses. */
    fun refusals(renderer: RefusalRenderer) {
        refusals = renderer
    }

    internal fun build(endpoints: List<Endpoint<*, *>>, schemas: SchemaSource): ApiSpec = ApiSpec(
        endpoints = endpoints,
        schemas = schemas,
        title = title,
        version = version,
        description = description,
        servers = servers,
        security = security,
        webhooks = webhooks,
        refusals = refusals,
    )
}
