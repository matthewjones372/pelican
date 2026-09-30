package io.github.matthewjones372.pelican.oidc

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.DirectDecrypter
import com.nimbusds.jose.crypto.DirectEncrypter
import com.nimbusds.jose.util.Base64URL
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.Credentials
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.PageGuard
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.Unauthenticated
import io.github.matthewjones372.pelican.cookieParam
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.responseHeader
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.ParseException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * People signing in to a service's pages through the provider: the
 * authorization-code flow with PKCE, then a session held in a cookie the
 * service encrypts, which nothing on the server has to remember.
 *
 * Serve [endpoints] beside the API's own, guard its pages with [guard], and
 * bind [authenticator] under the provider's scheme: it takes a bearer token as
 * the API always did, or a signed-in session.
 */
class SignIn internal constructor(
    private val provider: Oidc,
    private val clientId: String,
    private val clientSecret: String?,
    private val callbackUrl: String,
    sessionKey: ByteArray,
    private val scopes: List<String>,
    private val afterSignOut: String,
) {
    private val idTokens = provider.forAudience(clientId)
    private val encrypter = DirectEncrypter(sessionKey)
    private val decrypter = DirectDecrypter(sessionKey)
    private val random = SecureRandom()

    // The token exchange waits on the provider, so it waits on a thread of its own.
    private val exchanges = Executors.newVirtualThreadPerTaskExecutor()

    private val returnTo = queryParam<String>("return").optional()
    private val code = queryParam<String>("code")
    private val state = queryParam<String>("state")
    private val started = cookieParam<String>(LOGIN_COOKIE).optional()
    private val location = responseHeader<String>("Location")
    private val setCookie = responseHeader<String>("Set-Cookie")

    private val login = endpoint(returnTo) {
        get("login")
        hidden = true
        emits(location, setCookie)
        empty(status = FOUND)
    }

    private val callback = endpoint(code, state, started) {
        get("callback")
        hidden = true
        emits(location, setCookie)
        empty(status = FOUND)
    }

    private val logout = endpoint {
        get("logout")
        hidden = true
        emits(location, setCookie)
        empty(status = FOUND)
    }

    /** `/login`, `/callback` and `/logout`, bound, for the API's endpoint list. */
    val endpoints: List<ServerEndpoint> = listOf(
        ServerEndpoint(login) { p ->
            val flow = Flow(token(), token(), token(), safeReturn(p[returnTo]))
            p.setHeader(setCookie, cookie(LOGIN_COOKIE, seal(flow.toString()), LOGIN_LIFETIME))
            p.setHeader(location, authorizeUrl(flow))
            done()
        },
        ServerEndpoint(callback) { p ->
            CompletableFuture.supplyAsync({
                val flow = p[started]?.let(::unseal)?.let(Flow::parse)
                    ?: throw ApiException(400, "This sign-in was not started here, or took too long")
                if (!MessageDigest.isEqual(flow.state.toByteArray(), p[state].toByteArray())) {
                    throw ApiException(400, "This sign-in's state does not match the one it was started with")
                }
                val (idToken, identity) = exchange(p[code], flow)
                val lifetime = Duration.between(provider.now, (identity.claims["exp"] as java.util.Date).toInstant())
                p.setHeader(setCookie, cookie(SESSION_COOKIE, seal(idToken), lifetime))
                p.setHeader(location, flow.returnTo)
                Unit as Any?
            }, exchanges)
        },
        ServerEndpoint(logout) { p ->
            p.setHeader(setCookie, cookie(SESSION_COOKIE, "", Duration.ZERO))
            p.setHeader(location, afterSignOut)
            done()
        },
    )

    /** A bearer token as [provider] verifies it, or else a signed-in session; nobody when there is neither. */
    val authenticator: Authenticator = Authenticator { credentials ->
        if (credentials.bearerToken() != null) provider.authenticate(credentials) else session(credentials)
    }

    /** Sends anyone without a session to `/login`, and back to the page afterwards. */
    val guard: PageGuard = PageGuard { path, credentials ->
        if (session(credentials) != null) null
        else "/login?return=" + URLEncoder.encode(path, Charsets.UTF_8)
    }

    /**
     * The session's identity, or null. A cookie that no longer opens or has
     * expired is nobody, not a refusal: an old cookie is what a browser that
     * was signed in last week still sends.
     */
    private fun session(credentials: Credentials): Identity? {
        val idToken = credentials.cookie(SESSION_COOKIE)?.let(::unseal) ?: return null
        return try {
            idTokens.authenticate(bearer(idToken))
        } catch (_: Unauthenticated) {
            null
        }
    }

    private fun exchange(code: String, flow: Flow): Pair<String, Identity> {
        val answer = provider.post(
            provider.endpoint("token_endpoint"),
            buildMap {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", callbackUrl)
                put("client_id", clientId)
                put("code_verifier", flow.verifier)
                clientSecret?.let { put("client_secret", it) }
            },
        )
        val idToken = answer["id_token"] as? String
            ?: throw ApiException(BAD_GATEWAY, "The identity provider answered without an ID token")
        return idToken to verified(idToken, flow.nonce)
    }

    /** The ID token's identity, if it verifies and was issued for this sign-in and no other. */
    private fun verified(idToken: String, nonce: String): Identity {
        val identity = try {
            idTokens.authenticate(bearer(idToken))
        } catch (e: Unauthenticated) {
            throw ApiException(UNAUTHORIZED, e.message ?: "The ID token did not verify", cause = e)
        }
        if (identity?.claims?.get("nonce") != nonce) {
            throw ApiException(UNAUTHORIZED, "The ID token is not for this sign-in")
        }
        return identity
    }

    private fun authorizeUrl(flow: Flow): String {
        val challenge = Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(flow.verifier.toByteArray()))
        val query = mapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to callbackUrl,
            "scope" to scopes.joinToString(" "),
            "state" to flow.state,
            "nonce" to flow.nonce,
            "code_challenge" to challenge.toString(),
            "code_challenge_method" to "S256",
        ).entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, Charsets.UTF_8) }
        return provider.endpoint("authorization_endpoint") + "?" + query
    }

    /** Encrypted and authenticated with the service's own key: the browser holds it and cannot read or change it. */
    private fun seal(text: String): String =
        JWEObject(JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256GCM), Payload(text))
            .apply { encrypt(encrypter) }
            .serialize()

    private fun unseal(sealed: String): String? =
        try {
            JWEObject.parse(sealed).apply { decrypt(decrypter) }.payload.toString()
        } catch (_: ParseException) {
            null
        } catch (_: JOSEException) {
            null
        }

    private fun token(): String = Base64URL.encode(ByteArray(TOKEN_BYTES).also(random::nextBytes)).toString()

    private fun done(): CompletableFuture<Any?> = CompletableFuture.completedFuture(Unit)

    /** What one sign-in carries from `/login` to `/callback`, inside the sealed cookie. */
    private class Flow(val state: String, val verifier: String, val nonce: String, val returnTo: String) {
        override fun toString() = "$state $verifier $nonce $returnTo"

        companion object {
            fun parse(text: String): Flow? = text.split(' ', limit = FLOW_FIELDS)
                .takeIf { it.size == FLOW_FIELDS }
                ?.let { (state, verifier, nonce, returnTo) -> Flow(state, verifier, nonce, returnTo) }
        }
    }
}

/**
 * Sign-in for pages, as the client [clientId] registered with the provider to
 * come back to [callbackUrl]. [sessionKey] is the service's own 32-byte key for
 * the session cookie; losing it signs everyone out, and leaking it lets anyone
 * write a session.
 */
fun Oidc.signIn(
    clientId: String,
    callbackUrl: String,
    sessionKey: ByteArray,
    clientSecret: String? = null,
    scopes: List<String> = listOf("openid", "profile", "email", "groups"),
    afterSignOut: String = "/",
): SignIn {
    require(sessionKey.size == SESSION_KEY_BYTES) {
        "The session key is ${sessionKey.size} bytes; AES-256-GCM takes exactly $SESSION_KEY_BYTES."
    }
    return SignIn(this, clientId, clientSecret, callbackUrl, sessionKey.copyOf(), scopes, afterSignOut)
}

private const val SESSION_KEY_BYTES = 32

private const val LOGIN_COOKIE = "pelican_sign_in"
private const val SESSION_COOKIE = "pelican_session"
private const val FOUND = 302
private const val UNAUTHORIZED = 401
private const val BAD_GATEWAY = 502
private const val TOKEN_BYTES = 32
private const val FLOW_FIELDS = 4
private val LOGIN_LIFETIME: Duration = Duration.ofMinutes(10)

/** Only a path on this site: `//evil.example` and `https://…` would make sign-in an open redirect. */
private fun safeReturn(asked: String?): String =
    asked?.takeIf { it.startsWith("/") && !it.startsWith("//") && '\\' !in it } ?: "/"

private fun cookie(name: String, value: String, lifetime: Duration): String =
    "$name=$value; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${lifetime.seconds.coerceAtLeast(0)}"

private fun bearer(token: String) = object : Credentials {
    override fun header(name: String): String? =
        if (name.equals("Authorization", ignoreCase = true)) "Bearer $token" else null

    override fun cookie(name: String): String? = null
}
