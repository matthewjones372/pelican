package io.github.matthewjones372.pelican.oidc

import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory
import com.nimbusds.jose.jwk.AsymmetricJWK
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.Credentials
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.OpenIdConnectScheme
import io.github.matthewjones372.pelican.Unauthenticated
import io.github.matthewjones372.pelican.openIdConnect
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.ParseException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** The provider answered, and the answer was no: a 4xx or 5xx rather than no answer at all. */
class ProviderRefused(val status: Int, message: String) : RuntimeException(message)

/** A URL's body: the network in production, a map in a test. */
fun interface Fetch {
    fun get(url: String): String

    /** A form posted, as a token endpoint takes one. Only signing people in to pages needs it. */
    fun post(url: String, form: Map<String, String>): String =
        throw UnsupportedOperationException("This Fetch only reads; signing in posts to $url")
}

/**
 * Verifies bearer tokens an OpenID Connect provider issued: the signature
 * against the provider's published keys, then the issuer, the audience, expiry
 * and the algorithm. Bind it on the API under [scheme].
 */
class Oidc internal constructor(
    val issuer: String,
    val audience: String,
    private val groupsClaim: String,
    private val nameClaim: String,
    private val algorithms: Set<String>,
    private val leeway: Duration,
    private val refetchAfter: Duration,
    private val clock: Clock,
    private val fetch: Fetch,
    schemeName: String,
) : Authenticator {

    /** What the document names: the provider's discovery URL. */
    val scheme: OpenIdConnectScheme = openIdConnect(discoveryUrl(issuer), name = schemeName)

    private class Keys(val set: JWKSet, val fetchedAt: Instant)

    private val keys = AtomicReference<Keys?>(null)
    private val refreshing = Any()

    override fun authenticate(credentials: Credentials): Identity? {
        val token = credentials.bearerToken() ?: return null
        val jwt = parse(token)
        val header = jwt.header

        if (header.algorithm.name !in algorithms) {
            throw Unauthenticated(
                "The token is signed with ${header.algorithm.name}, which this service does not accept",
            )
        }
        verifySignature(jwt, header)
        return identityFrom(jwt.jwtClaimsSet)
    }

    private fun parse(token: String): SignedJWT =
        try {
            SignedJWT.parse(token)
        } catch (e: ParseException) {
            // `alg: none` lands here too: an unsigned token is not a JWS at all.
            throw Unauthenticated("The token is not a signed JWT: ${e.message}")
        }

    private fun verifySignature(jwt: SignedJWT, header: JWSHeader) {
        val selector = JWKSelector(JWKMatcher.forJWSHeader(header))
        val candidates = selector.select(current().set).ifEmpty { selector.select(refetched().set) }
        val key = candidates.filterIsInstance<AsymmetricJWK>().firstOrNull()
            ?: throw Unauthenticated("The token was signed by a key this service does not know")

        val verifier = DefaultJWSVerifierFactory().createJWSVerifier(header, key.toPublicKey())
        if (!jwt.verify(verifier)) throw Unauthenticated("The token's signature does not verify")
    }

    private fun identityFrom(claims: JWTClaimsSet): Identity {
        val now = clock.instant()
        val skew = leeway.toJavaDuration()
        val expires = claims.expirationTime?.toInstant() ?: refuse("The token has no expiry")
        val notBefore = claims.notBeforeTime?.toInstant()
        val subject = claims.subject ?: refuse("The token names no subject")

        if (claims.issuer != issuer) refuse("The token was issued by ${claims.issuer}, not $issuer")
        if (audience !in claims.audience.orEmpty()) refuse("The token is not for $audience")
        if (!now.isBefore(expires.plus(skew))) refuse("The token expired at $expires")
        if (notBefore != null && now.isBefore(notBefore.minus(skew))) refuse("The token is not valid until $notBefore")

        return Identity(
            subject = subject,
            name = claims.getClaim(nameClaim) as? String,
            groups = groupsOf(claims.getClaim(groupsClaim)),
            actor = actorOf(claims.getClaim("act"))?.takeUnless { it == subject },
            claims = claims.claims,
        )
    }

    private fun refuse(reason: String): Nothing = throw Unauthenticated(reason)

    /**
     * RFC 8693's `act`: who is really there. Only the outermost is read; a
     * chain of earlier actors is in [Identity.claims] for whoever wants it.
     */
    private fun actorOf(claim: Any?): String? = (claim as? Map<*, *>)?.get("sub") as? String

    /** A list, as most providers send it, or one group as a bare string. */
    private fun groupsOf(claim: Any?): Set<String> = when (claim) {
        is Collection<*> -> claim.filterIsInstance<String>().toSet()
        is String -> setOf(claim)
        else -> emptySet()
    }

    private fun current(): Keys = keys.get() ?: synchronized(refreshing) { keys.get() ?: load() }

    /**
     * The provider's keys fetched again, at most once per [refetchAfter]: an
     * unknown key id is how a rotation shows itself, and also what a caller
     * sending garbage looks like, which must not become a request to the
     * provider each time.
     */
    private fun refetched(): Keys = synchronized(refreshing) {
        val held = keys.get()
        if (held != null && held.fetchedAt.plus(refetchAfter.toJavaDuration()).isAfter(clock.instant())) held
        else load()
    }

    private fun load(): Keys = Keys(JWKSet.parse(fetchOrUnavailable(jwksUri())), clock.instant()).also(keys::set)

    private fun jwksUri(): String = endpoint("jwks_uri")

    private val discovered = AtomicReference<Map<String, Any?>?>(null)

    /** A URL the discovery document names, fetched once and kept. */
    internal fun endpoint(name: String): String = optionalEndpoint(name)
        ?: error("The provider at $issuer publishes no $name")

    internal fun optionalEndpoint(name: String): String? {
        val discovery = discovered.get() ?: JSONObjectUtils.parse(fetchOrUnavailable(discoveryUrl(issuer))).also {
            val published = it["issuer"] as? String
            check(published == issuer) { "The provider at $issuer says it is $published" }
            discovered.set(it)
        }
        return discovery[name] as? String
    }

    /** The same provider verifying tokens issued to [other]: a page's ID token is for its client, not the API. */
    internal fun forAudience(other: String): Oidc = Oidc(
        issuer, other, groupsClaim, nameClaim, algorithms, leeway, refetchAfter, clock, fetch, scheme.name,
    )

    /** A form posted to the provider, answered as JSON; unreachable is 503, as a fetch is. */
    @Suppress("TooGenericExceptionCaught") // Whatever the post throws, the answer is the same 503.
    internal fun post(url: String, form: Map<String, String>): Map<String, Any?> {
        val body = try {
            fetch.post(url, form)
        } catch (e: ProviderRefused) {
            // A code used twice, expired, or never issued: the sign-in is over, and the person starts again.
            throw ApiException(400, "The identity provider refused this sign-in", cause = e)
        } catch (e: Exception) {
            throw ApiException(503, "The identity provider could not be reached", cause = e)
        }
        return JSONObjectUtils.parse(body)
    }

    internal val now: Instant get() = clock.instant()

    /** A provider that cannot be reached is this service's trouble, not the caller's: 503, not 401. */
    @Suppress("TooGenericExceptionCaught") // Whatever the fetch throws, the answer is the same 503.
    private fun fetchOrUnavailable(url: String): String =
        try {
            fetch.get(url)
        } catch (e: Exception) {
            throw ApiException(503, "The identity provider could not be reached", cause = e)
        }

    override fun toString() = "Oidc($issuer, audience $audience)"
}

/**
 * Verifies [issuer]'s tokens for [audience]. Its discovery document and keys
 * are fetched on first use and cached; an unknown key id fetches them again,
 * at most once per [refetchAfter]. `none` and HMAC are never accepted, whatever
 * [algorithms] says, since a provider's keys are public.
 */
fun oidc(
    issuer: String,
    audience: String,
    groupsClaim: String = "groups",
    nameClaim: String = "name",
    algorithms: Set<String> = setOf("RS256", "PS256", "ES256", "EdDSA"),
    leeway: Duration = 30.seconds,
    refetchAfter: Duration = 1.minutes,
    clock: Clock = Clock.systemUTC(),
    fetch: Fetch = HttpFetch(),
    schemeName: String = "openIdConnect",
): Oidc {
    val symmetric = algorithms.filter { it == "none" || it.startsWith("HS") }
    require(symmetric.isEmpty()) {
        "$symmetric cannot verify a provider's token: none checks nothing, and HMAC with a public key lets " +
            "anyone who has read the key sign one."
    }
    return Oidc(
        issuer.trimEnd('/'), audience, groupsClaim, nameClaim, algorithms, leeway, refetchAfter, clock, fetch,
        schemeName,
    )
}

private fun discoveryUrl(issuer: String) = "$issuer/.well-known/openid-configuration"

/** Over `java.net.http`, with a short timeout: a caller is waiting on the answer. */
class HttpFetch(private val timeout: Duration = 2.seconds) : Fetch {
    private val client = HttpClient.newBuilder().connectTimeout(timeout.toJavaDuration()).build()

    override fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout.toJavaDuration()).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in SUCCESS) { "$url answered ${response.statusCode()}" }
        return response.body()
    }

    override fun post(url: String, form: Map<String, String>): String {
        val encoded = form.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8)
        }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout.toJavaDuration())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encoded))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in
            SUCCESS
        ) throw ProviderRefused(response.statusCode(), "$url answered ${response.statusCode()}")
        return response.body()
    }
}

private val SUCCESS = 200..299
