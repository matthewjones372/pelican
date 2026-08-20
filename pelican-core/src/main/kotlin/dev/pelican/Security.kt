package dev.pelican

/**
 * Security schemes, described the same way everything else here is: as plain
 * values, declared once and referenced by the endpoints that need them.
 *
 * The annotation-driven frameworks put the scheme in one place and a scope
 * string in another, and nothing checks that the two agree. Here a scheme is a
 * value, an endpoint holds a reference to it, and asking for a scope the scheme
 * never declared fails when the endpoint value is constructed — at class-init
 * time, not on the first request.
 *
 * These descriptions document a requirement; they do not enforce one. Nothing
 * in Pelican validates a token — see the README. Declaring `security(...)` puts
 * the padlock in Swagger UI and the requirement in the spec; rejecting a caller
 * who has no token is the handler's job, or a filter in front of it.
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
 * The authorization code flow, which is the one a browser-based Swagger UI can
 * actually complete. Pair it with [DocsOAuth] on the `Api` to have the docs page
 * run the flow itself.
 *
 * ```
 * val oauth = oauth2AuthorizationCode(
 *     authorizationUrl = "https://id.example.com/oauth2/authorize",
 *     tokenUrl = "https://id.example.com/oauth2/token",
 *     scopes = mapOf(
 *         "orders:read"  to "Read orders",
 *         "orders:write" to "Place and cancel orders",
 *     ),
 * )
 * ```
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
 * The same builders, given scopes as bare names.
 *
 * OpenAPI models a scheme's scopes as name -> description, and Swagger UI puts
 * the description beside the checkbox in the Authorize dialog. When there is
 * nothing useful to say beyond the name, this form says only the name — the
 * dialog still lists a checkbox per scope, and the endpoint side is unchanged
 * either way, because a requirement only ever names scopes.
 *
 * ```
 * oauth2AuthorizationCode(
 *     authorizationUrl = "https://id.example.com/oauth2/authorize",
 *     tokenUrl = "https://id.example.com/oauth2/token",
 *     scopes = listOf("orders:read", "orders:write"),
 * )
 * ```
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

/**
 * Declaration order is kept: Swagger UI lists the checkboxes in the order the
 * document gives them, so the order written here is the order a reader sees.
 */
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
 * One scheme, and the scopes a caller needs under it. Several of these on one
 * endpoint means *any* of them is enough, which is how OpenAPI reads a list.
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
 * Collects the schemes referenced by [requirements], failing on two different
 * schemes registered under one name — which would otherwise produce a document
 * where half the endpoints point at the wrong thing.
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
