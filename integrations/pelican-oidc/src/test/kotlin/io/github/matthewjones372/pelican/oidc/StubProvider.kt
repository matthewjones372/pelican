package io.github.matthewjones372.pelican.oidc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A provider small enough to read: discovery, keys, an authorize page with one
 * button, and a token endpoint that checks PKCE before it signs anything.
 */
class StubProvider(private val clientId: String) : AutoCloseable {

    private class Pending(val challenge: String, val nonce: String, val redirectUri: String)

    private val key: RSAKey = RSAKeyGenerator(2048).keyID("stub").generate()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val issuer: String get() = "http://127.0.0.1:${server.address.port}"

    /** Every token request's form, for asserting on what the service sent. */
    val tokenRequests: MutableList<Map<String, String>> = java.util.Collections.synchronizedList(mutableListOf())

    init {
        server.createContext("/.well-known/openid-configuration") { it.answer(200, "application/json", discovery()) }
        server.createContext("/jwks") { it.answer(200, "application/json", JWKSet(key.toPublicJWK()).toString()) }
        server.createContext("/authorize") { it.authorize() }
        server.createContext("/token") { it.token() }
        server.start()
    }

    private fun discovery() = """
        {"issuer":"$issuer","jwks_uri":"$issuer/jwks","authorization_endpoint":"$issuer/authorize",
         "token_endpoint":"$issuer/token"}
    """.trimIndent()

    private fun HttpExchange.authorize() {
        val q = form(requestURI.rawQuery)
        check(q["client_id"] == clientId && q["code_challenge_method"] == "S256")
        val code = UUID.randomUUID().toString()
        pending[code] = Pending(q.getValue("code_challenge"), q.getValue("nonce"), q.getValue("redirect_uri"))
        val back = "${q["redirect_uri"]}?code=$code&state=${q["state"]}"
        val page = """<!doctype html><title>Stub provider</title><a id="as-ada" href="$back">Sign in as Ada</a>"""
        answer(200, "text/html", page)
    }

    private fun HttpExchange.token() {
        val f = form(requestBody.readAllBytes().decodeToString())
        tokenRequests += f
        val waiting = pending.remove(f["code"])
        val proof = f["code_verifier"]?.let { verifier ->
            Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())).toString()
        }
        if (waiting == null || proof != waiting.challenge || f["redirect_uri"] != waiting.redirectUri) {
            return answer(400, "application/json", """{"error":"invalid_grant"}""")
        }
        answer(200, "application/json", """{"id_token":"${idToken(waiting.nonce)}","token_type":"Bearer"}""")
    }

    private fun idToken(nonce: String): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer).subject("ada").audience(clientId)
            .expirationTime(Date.from(Instant.now().plusSeconds(600)))
            .claim("name", "Ada Lovelace").claim("nonce", nonce).claim("groups", listOf("customer"))
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }.serialize()
    }

    private fun form(text: String?): Map<String, String> =
        text.orEmpty().split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }

    private fun HttpExchange.answer(status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}
