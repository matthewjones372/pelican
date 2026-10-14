package io.github.matthewjones372.pelican

/**
 * Security schemes as plain values, declared once and referenced by the
 * endpoints that need them. An endpoint holds the scheme itself, so a scope it
 * never declared fails at class-init rather than on the first request.
 *
 * Documented, never enforced: nothing here validates a token. `security(...)`
 * puts the padlock in Swagger UI; rejecting a caller is a handler's job.
 */
sealed interface SecurityScheme {
    /** The key this scheme appears under in `components.securitySchemes`. */
    val name: String
    val description: String?

    /** Scopes this scheme declares. Empty for everything but OAuth 2. */
    val declaredScopes: Set<String> get() = emptySet()
}

/** A credential carried in a header, query parameter or cookie. */
class ApiKeyScheme internal constructor(
    override val name: String,
    val location: String,
    val paramName: String,
    override val description: String?,
) : SecurityScheme

/** HTTP authentication: `basic`, `bearer`, or any other registered scheme. */
class HttpScheme internal constructor(
    override val name: String,
    val scheme: String,
    val bearerFormat: String?,
    override val description: String?,
) : SecurityScheme

class OpenIdConnectScheme internal constructor(
    override val name: String,
    val openIdConnectUrl: String,
    override val description: String?,
) : SecurityScheme

class OAuth2Scheme internal constructor(
    override val name: String,
    val flows: List<OAuthFlow>,
    override val description: String?,
) : SecurityScheme {
    override val declaredScopes: Set<String> = flows.flatMapTo(LinkedHashSet()) { it.scopes.keys }
}

/** One OAuth 2 flow. Scope name -> what the scope allows, as OpenAPI models it. */
sealed class OAuthFlow(val scopes: Map<String, String>) {
    class AuthorizationCode internal constructor(
        val authorizationUrl: String,
        val tokenUrl: String,
        val refreshUrl: String?,
        scopes: Map<String, String>,
    ) : OAuthFlow(scopes)

    class ClientCredentials internal constructor(
        val tokenUrl: String,
        val refreshUrl: String?,
        scopes: Map<String, String>,
    ) : OAuthFlow(scopes)

    class Password internal constructor(
        val tokenUrl: String,
        val refreshUrl: String?,
        scopes: Map<String, String>,
    ) : OAuthFlow(scopes)

    class Implicit internal constructor(
        val authorizationUrl: String,
        val refreshUrl: String?,
        scopes: Map<String, String>,
    ) : OAuthFlow(scopes)
}

// ------------------------------------------------------------------ builders

/**
 * The authorization code flow, the one a browser-based Swagger UI can complete.
 * Pair it with [DocsOAuth] to have the docs page run the flow itself.
 */
fun oauth2AuthorizationCode(
    authorizationUrl: String,
    tokenUrl: String,
    scopes: Map<String, String> = emptyMap(),
    refreshUrl: String? = null,
    name: String = "oauth2",
    description: String? = null,
): OAuth2Scheme = OAuth2Scheme(
    name,
    listOf(OAuthFlow.AuthorizationCode(authorizationUrl, tokenUrl, refreshUrl, scopes)),
    description,
)

fun oauth2ClientCredentials(
    tokenUrl: String,
    scopes: Map<String, String> = emptyMap(),
    refreshUrl: String? = null,
    name: String = "oauth2",
    description: String? = null,
): OAuth2Scheme =
    OAuth2Scheme(name, listOf(OAuthFlow.ClientCredentials(tokenUrl, refreshUrl, scopes)), description)

fun oauth2Password(
    tokenUrl: String,
    scopes: Map<String, String> = emptyMap(),
    refreshUrl: String? = null,
    name: String = "oauth2",
    description: String? = null,
): OAuth2Scheme =
    OAuth2Scheme(name, listOf(OAuthFlow.Password(tokenUrl, refreshUrl, scopes)), description)

fun oauth2Implicit(
    authorizationUrl: String,
    scopes: Map<String, String> = emptyMap(),
    refreshUrl: String? = null,
    name: String = "oauth2",
    description: String? = null,
): OAuth2Scheme =
    OAuth2Scheme(name, listOf(OAuthFlow.Implicit(authorizationUrl, refreshUrl, scopes)), description)

/** Several flows under one scheme name, when a client may pick between them. */
fun oauth2(
    flows: List<OAuthFlow>,
    name: String = "oauth2",
    description: String? = null,
): OAuth2Scheme = OAuth2Scheme(name, flows, description)

/**
 * The same builders with scopes as bare names, for when there is nothing to say
 * beyond the name. OpenAPI models them as name -> description, and Swagger UI
 * shows the description beside each checkbox.
 */
fun oauth2AuthorizationCode(
    authorizationUrl: String,
    tokenUrl: String,
    scopes: List<String>,
    refreshUrl: String? = null,
    name: String = "oauth2",
    description: String? = null,
): OAuth2Scheme =
    oauth2AuthorizationCode(authorizationUrl, tokenUrl, scopes.described(), refreshUrl, name, description)

fun oauth2ClientCredentials(
    tokenUrl: String,
    scopes: List<String>,
    refreshUrl: String? = null,
    name: String = "oauth2",
    description: String? = null,
): OAuth2Scheme = oauth2ClientCredentials(tokenUrl, scopes.described(), refreshUrl, name, description)

fun oauth2Password(
    tokenUrl: String,
    scopes: List<String>,
    refreshUrl: String? = null,
    name: String = "oauth2",
    description: String? = null,
): OAuth2Scheme = oauth2Password(tokenUrl, scopes.described(), refreshUrl, name, description)

fun oauth2Implicit(
    authorizationUrl: String,
    scopes: List<String>,
    refreshUrl: String? = null,
    name: String = "oauth2",
    description: String? = null,
): OAuth2Scheme = oauth2Implicit(authorizationUrl, scopes.described(), refreshUrl, name, description)

/** Declaration order is kept: Swagger UI lists checkboxes in document order. */
private fun List<String>.described(): Map<String, String> =
    associateWithTo(LinkedHashMap()) { "" }

fun bearerAuth(
    bearerFormat: String? = "JWT",
    name: String = "bearerAuth",
    description: String? = null,
): HttpScheme = HttpScheme(name, "bearer", bearerFormat, description)

fun basicAuth(
    name: String = "basicAuth",
    description: String? = null,
): HttpScheme = HttpScheme(name, "basic", null, description)

fun httpAuth(
    scheme: String,
    bearerFormat: String? = null,
    name: String = scheme,
    description: String? = null,
): HttpScheme = HttpScheme(name, scheme, bearerFormat, description)

fun apiKeyHeader(
    headerName: String,
    name: String = "apiKey",
    description: String? = null,
): ApiKeyScheme = ApiKeyScheme(name, "header", headerName, description)

fun apiKeyQuery(
    paramName: String,
    name: String = "apiKey",
    description: String? = null,
): ApiKeyScheme = ApiKeyScheme(name, "query", paramName, description)

fun apiKeyCookie(
    cookieName: String,
    name: String = "apiKey",
    description: String? = null,
): ApiKeyScheme = ApiKeyScheme(name, "cookie", cookieName, description)

fun openIdConnect(
    openIdConnectUrl: String,
    name: String = "openIdConnect",
    description: String? = null,
): OpenIdConnectScheme = OpenIdConnectScheme(name, openIdConnectUrl, description)

// -------------------------------------------------------------- requirements

/**
 * One scheme and the scopes a caller needs under it. Several on one endpoint
 * means any of them is enough, which is how OpenAPI reads a list.
 */
class SecurityRequirement internal constructor(
    val scheme: SecurityScheme,
    val scopes: List<String>,
) {
    init {
        val unknown = scopes - scheme.declaredScopes
        if (unknown.isNotEmpty()) {
            val known = scheme.declaredScopes.ifEmpty { setOf("<none>") }.joinToString(", ")
            error(
                "Security scheme '${scheme.name}' does not declare the scope(s) $unknown. " +
                    "It declares: $known",
            )
        }
    }
}

/** `oauth.requires("orders:write")` — the same thing `security(...)` records. */
fun SecurityScheme.requires(vararg scopes: String): SecurityRequirement =
    SecurityRequirement(this, scopes.toList())

/**
 * Collects the schemes [requirements] reference, failing on two different ones
 * under a single name — half the endpoints would point at the wrong thing.
 */
fun securitySchemesOf(requirements: List<SecurityRequirement>): List<SecurityScheme> {
    val byName = LinkedHashMap<String, SecurityScheme>()
    requirements.forEach { req ->
        val existing = byName.putIfAbsent(req.scheme.name, req.scheme)
        if (existing != null && existing !== req.scheme) {
            error("Two different security schemes are both named '${req.scheme.name}'")
        }
    }
    return byName.values.toList()
}
