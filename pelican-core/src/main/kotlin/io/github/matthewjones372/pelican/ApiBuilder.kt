package io.github.matthewjones372.pelican

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
    var strictBodyTimeoutMillis: Long = DEFAULT_STRICT_BODY_TIMEOUT_MILLIS
    var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES
    var exposeInternalErrors: Boolean = false
    var covers: List<Endpoint<*, *>> = emptyList()
    var webhooks: List<Webhook> = emptyList()

    private val filters = mutableListOf<Filter>()
    private var onServerError: ((reference: String, endpoint: Endpoint<*, *>?, error: Throwable) -> Unit)? = null
    private var refusals: RefusalRenderer = ApiErrorEnvelope
    private var onRefusal: RefusalObserver? = null

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
        filters = filters.toList(),
        exposeInternalErrors = exposeInternalErrors,
        onServerError = onServerError,
        covers = covers,
        webhooks = webhooks,
        refusals = refusals,
        onRefusal = onRefusal,
    )
}
