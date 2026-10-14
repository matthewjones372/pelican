package io.github.matthewjones372.pelican.oidc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.Credentials
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.Unauthenticated
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.inMemory
import io.github.matthewjones372.pelican.test.signedInAs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class OidcTest {

    private val issuer = "https://id.home.arpa"
    private val jwksUri = "$issuer/.well-known/jwks.json"
    private val now = Instant.parse("2026-09-30T12:00:00Z")

    private class Moving(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = at
    }

    private val clock = Moving(now)

    private val signing: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()
    private val rotated: RSAKey = RSAKeyGenerator(2048).keyID("key-2").generate()

    /** What the provider publishes; a test swaps it to rotate. */
    private var published = JWKSet(signing.toPublicJWK())
    private val keyFetches = AtomicInteger()
    private var providerUp = true

    private val provider = Fetch { url ->
        check(providerUp) { "connection refused" }
        when (url) {
            "$issuer/.well-known/openid-configuration" -> """{"issuer":"$issuer","jwks_uri":"$jwksUri"}"""
            jwksUri -> published.toString().also { keyFetches.incrementAndGet() }
            else -> error("no such page $url")
        }
    }

    private val verifier = oidc(issuer, audience = "lark-bank", clock = clock, fetch = provider)

    private fun claims(
        audience: String = "lark-bank",
        expires: Instant = now.plusSeconds(300),
        from: String = issuer,
    ) = JWTClaimsSet.Builder()
        .issuer(from)
        .subject("ada")
        .audience(audience)
        .expirationTime(Date.from(expires))
        .claim("name", "Ada Lovelace")
        .claim("groups", listOf("customer", "risk"))
        .build()

    private fun signed(claims: JWTClaimsSet = claims(), key: RSAKey = signing): String =
        SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()

    private fun bearing(token: String) = object : Credentials {
        override fun header(name: String) = if (name.equals("Authorization", true)) "Bearer $token" else null
        override fun cookie(name: String): String? = null
    }

    private fun verify(token: String): Identity? = verifier.authenticate(bearing(token))

    private fun refused(token: String): String = shouldThrow<Unauthenticated> { verify(token) }.message!!

    @Test
    fun `a valid token is the identity it names, groups and all`() {
        val ada = verify(signed())!!

        ada.subject shouldBe "ada"
        ada.name shouldBe "Ada Lovelace"
        ada.groups shouldBe setOf("customer", "risk")
    }

    @Test
    fun `no token is nobody, not a refusal`() {
        val none = object : Credentials {
            override fun header(name: String): String? = null
            override fun cookie(name: String): String? = null
        }
        verifier.authenticate(none) shouldBe null
    }

    @Test
    fun `an expired token is refused, past the leeway`() {
        verify(signed(claims(expires = now.minusSeconds(10)))) // within 30 seconds
        refused(signed(claims(expires = now.minusSeconds(31)))) shouldContain "expired"
    }

    @Test
    fun `a token for another audience is refused`() {
        refused(signed(claims(audience = "bank-checks"))) shouldContain "not for lark-bank"
    }

    @Test
    fun `a token from another issuer is refused`() {
        refused(signed(claims(from = "https://evil.example"))) shouldContain "issued by"
    }

    @Test
    fun `an unknown key id fetches the keys again once, however often it is sent`() {
        verify(signed())
        val before = keyFetches.get()

        clock.at = now.plusSeconds(61) // past the minute, so one refetch is allowed
        refused(signed(key = rotated)) shouldContain "does not know"
        refused(signed(key = rotated)) shouldContain "does not know"

        keyFetches.get() shouldBe before + 1
    }

    @Test
    fun `a rotated key is picked up by that refetch`() {
        verify(signed())
        published = JWKSet(listOf(signing.toPublicJWK(), rotated.toPublicJWK()))
        clock.at = now.plusSeconds(61)

        verify(signed(claims(expires = clock.at.plusSeconds(300)), key = rotated))!!.subject shouldBe "ada"
    }

    @Test
    fun `an unsigned token is refused`() {
        val header = Base64URL.encode("""{"alg":"none"}""")
        val body = Base64URL.encode(claims().toString())

        refused("$header.$body.") shouldContain "not a signed JWT"
    }

    @Test
    fun `an HMAC token keyed with the provider's public key is refused`() {
        val forged = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key-1").build(), claims())
            .apply { sign(MACSigner(signing.toPublicKey().encoded)) }
            .serialize()

        refused(forged) shouldContain "HS256"
    }

    @Test
    fun `and HMAC cannot be allowed at all`() {
        shouldThrow<IllegalArgumentException> {
            oidc(issuer, "lark-bank", algorithms = setOf("RS256", "HS256"), fetch = provider)
        }.message shouldContain "HMAC"
    }

    @Test
    fun `a tampered token is refused`() {
        val token = signed()
        val (head, _, signature) = token.split('.')
        val richer = Base64URL.encode(claims().toString().replace("customer", "admins"))

        refused("$head.$richer.$signature") shouldContain "signature"
    }

    @Test
    fun `a provider that cannot be reached is a 503, not the caller's fault`() {
        providerUp = false
        shouldThrow<ApiException> { verify(signed()) }.status shouldBe 503
    }

    @Test
    fun `the scheme names the provider's discovery document`() {
        verifier.scheme.openIdConnectUrl shouldBe "$issuer/.well-known/openid-configuration"
    }

    @Test
    fun `leeway is configurable`() {
        val strict = oidc(issuer, "lark-bank", leeway = 0.seconds, clock = clock, fetch = provider)
        shouldThrow<Unauthenticated> {
            strict.authenticate(bearing(signed(claims(expires = now.minusSeconds(1)))))
        }
    }

    @Test
    fun `bound on an API it is who calls, verified, refused, or the provider's trouble`() {
        val caller = authenticated(verifier.scheme) { it.subject }
        val whoAmI = endpoint {
            get("me")
            authenticatedBy(caller)
            text()
        }
        val client = api(
            listOf(ServerEndpoint(whoAmI) { p -> CompletableFuture.completedStage(p[caller] as Any?) }),
            codecs = JacksonCodecs,
        ) { authenticate(verifier.scheme, verifier) }.inMemory()

        client.signedInAs(signed()).call(whoAmI, Unit) shouldBe "ada"
        client.signedInAs(signed(claims(audience = "elsewhere"))).response(whoAmI, Unit).status shouldBe 401
        providerUp = false
        clock.at = now.plusSeconds(61) // so the unknown key sends it to the provider
        val unknownKey = signed(claims(expires = clock.at.plusSeconds(300)), key = rotated)
        client.signedInAs(unknownKey).response(whoAmI, Unit).status shouldBe 503
    }

    private fun actedBy(actor: String) =
        JWTClaimsSet.Builder(claims()).claim("act", mapOf("sub" to actor)).build()

    @Test
    fun `a token with act names who is really there, apart from whom it is about`() {
        val acting = verify(signed(actedBy("bob")))!!

        acting.subject shouldBe "ada"
        acting.actor shouldBe "bob"
    }

    @Test
    fun `a token without one is the subject acting as themselves`() {
        verify(signed())!!.actor shouldBe null
    }

    @Test
    fun `and an act naming the subject is no one else`() {
        verify(signed(actedBy("ada")))!!.actor shouldBe null
    }
}
