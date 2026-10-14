package io.github.matthewjones372.pelican

/** What a verified credential says about who sent it. */
class Identity(
    val subject: String,
    val name: String? = null,
    val groups: Set<String> = emptySet(),
    /** Who is really there when [subject] is someone they are acting as; null when they are the same. */
    val actor: String? = null,
    /** Everything else the credential carried, for what the fields above leave out. */
    val claims: Map<String, Any?> = emptyMap(),
) {
    override fun toString() = if (actor == null) "Identity($subject)" else "Identity($subject, acted by $actor)"
}

/** As much of a request as an [Authenticator] may read. */
interface Credentials {
    /** The first line of [name], matched without regard to case. */
    fun header(name: String): String?

    fun cookie(name: String): String?

    /** The token of an `Authorization: Bearer ...` header, or null. */
    fun bearerToken(): String? =
        header("Authorization")
            ?.takeIf { it.length > BEARER.length && it.regionMatches(0, BEARER, 0, BEARER.length, ignoreCase = true) }
            ?.substring(BEARER.length)
            ?.trim()
            ?.ifEmpty { null }
}

private const val BEARER = "Bearer "

/**
 * Verifies a request's credential. Bound on the [Api] against the scheme an
 * endpoint declares, so the description stays a plain value and a test binds a
 * different one.
 */
fun interface Authenticator {
    /** Null when the request carries no credential; throws [Unauthenticated] when it carries one that fails. */
    fun authenticate(credentials: Credentials): Identity?
}

/** A credential was presented and did not verify. The message goes to the caller, so name no key or secret. */
class Unauthenticated(message: String) : RuntimeException(message)

/**
 * Who is calling, as the service's own type [T], built from a verified
 * [Identity]. Declared on an endpoint with `authenticatedBy(...)` and read in
 * the handler as `this[caller]`.
 */
class CallerInput<T> @PublishedApi internal constructor(
    val scheme: SecurityScheme,
    val required: Boolean,
    internal val build: (Identity) -> T,
) : ParamKey<T> {
    /** The value a handler reads, or the 401 that stops it running. */
    fun decode(authenticator: Authenticator, credentials: Credentials): T? {
        val identity = try {
            authenticator.authenticate(credentials)
        } catch (e: Unauthenticated) {
            // A bad credential is refused even where anyone may call: the
            // caller meant to be someone, and answering as no one would hide it.
            throw unauthorized(e.message ?: "The credential did not verify", error = "invalid_token", cause = e)
        }
        return when {
            identity != null -> build(identity)
            required -> throw unauthorized("This endpoint needs a caller, and the request named none", error = null)
            else -> null
        }
    }

    private fun unauthorized(message: String, error: String?, cause: Throwable? = null): ApiException = ApiException(
        401,
        message,
        headers = listOf("WWW-Authenticate" to challenge(error)),
        cause = cause,
    )

    /** RFC 6750's challenge for a bearer scheme, and the scheme's own name otherwise. */
    private fun challenge(error: String?): String {
        val scheme = when (val s = scheme) {
            is HttpScheme -> s.scheme.replaceFirstChar { it.uppercase() }
            is ApiKeyScheme -> "ApiKey"
            is OpenIdConnectScheme, is OAuth2Scheme -> "Bearer"
        }
        return if (error == null) scheme else "$scheme error=\"$error\""
    }

    override fun toString() = "caller:${scheme.name}"
}

/**
 * A caller authenticated under [scheme], handed to the handler as [build]
 * makes it. The scheme is what the document names; what verifies it is bound on
 * the [Api] with `authenticate(scheme, by)`.
 */
fun <T : Any> authenticated(scheme: SecurityScheme, build: (Identity) -> T): CallerInput<T> =
    CallerInput(scheme, required = true, build)

/** Anyone may call; a caller who says who they are is handed over, and one who does not reads as null. */
fun <T : Any> CallerInput<T>.optional(): CallerInput<T?> = CallerInput(scheme, required = false, build)
