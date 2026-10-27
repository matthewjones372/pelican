package io.github.matthewjones372.pelican

/**
 * Whether a request to this endpoint is one a browser could be made to send by another site with the caller's
 * credential attached: a POST, PUT, PATCH or DELETE whose caller is carried in a cookie. A bearer token or an API key
 * in a header is not attached by the browser on its own, so it is never checked.
 */
internal fun crossSiteChecked(method: Method, caller: CallerInput<*>?): Boolean =
    method in STATE_CHANGING && (caller?.scheme as? ApiKeyScheme)?.location == "cookie"

/**
 * Refuses a cookie-authenticated request another site sent. Its `Origin` must be this host, or one [cors] lets send
 * credentials; with no `Origin`, its `Sec-Fetch-Site` must be `same-origin` or `none`; with neither, it is not from a
 * browser, which is the only thing that attaches a cookie unasked.
 */
internal fun refuseCrossSite(credentials: Credentials, cors: Cors?) {
    val origin = credentials.header("Origin")
    val fetchSite = credentials.header("Sec-Fetch-Site")
    val sameSite = when {
        origin != null -> isThisHost(origin, credentials.header("Host")) || trustedBy(cors, origin)
        fetchSite != null -> fetchSite.lowercase() in SAME_SITE_FETCHES
        else -> true
    }
    if (!sameSite) {
        throw ApiException(FORBIDDEN_STATUS, "A request from another site, carrying this site's cookie, is refused")
    }
}

private fun isThisHost(origin: String, host: String?): Boolean =
    host != null && origin.substringAfter("://", missingDelimiterValue = "").equals(host, ignoreCase = true)

/** An origin the API lets send credentials; one CORS merely allows to read cannot send a cookie on purpose. */
private fun trustedBy(cors: Cors?, origin: String): Boolean =
    cors != null && cors.allowCredentials && cors.origins.allows(origin)

/** The 403 [refuseCrossSite] answers with, declared on every endpoint it guards. */
internal val CROSS_SITE_REFUSED = ErrorSpec(
    FORBIDDEN_STATUS,
    "Sent from another site with this site's cookie, which a browser attaches on its own.",
    type = null,
)

private val STATE_CHANGING = setOf(Method.POST, Method.PUT, Method.PATCH, Method.DELETE)

private val SAME_SITE_FETCHES = setOf("same-origin", "none")

private const val FORBIDDEN_STATUS = 403
