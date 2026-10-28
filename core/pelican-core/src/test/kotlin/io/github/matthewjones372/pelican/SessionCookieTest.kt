package io.github.matthewjones372.pelican

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** A session cookie as an `Authenticator`: started, read back, slid, expired and ended. Spec 0094. */
class SessionCookieTest {

    private class SteppedClock(var now: Instant = Instant.parse("2026-10-27T12:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        fun advance(by: Duration) { now = now.plus(by.toJavaDuration()) }
    }

    /** The in-memory store, with every touch it is asked for written down. */
    private class Counting(clock: Clock) : SessionStore {
        val keys = mutableListOf<String>()
        var touches = 0

        private val inner = InMemorySessionStore(clock)

        override fun create(key: String, identity: Identity, expires: Instant, tenant: String?) {
            keys += key
            inner.create(key, identity, expires, tenant)
        }

        override fun lookup(key: String) = inner.lookup(key)

        override fun touch(key: String, expires: Instant) {
            touches++
            inner.touch(key, expires)
        }

        override fun revoke(key: String) = inner.revoke(key)

        override fun revokeAll(subject: String) = inner.revokeAll(subject)
    }

    private val clock = SteppedClock()
    private val store = Counting(clock)
    private val sessions = sessionCookie("sid", store, idle = 30.minutes, absolute = 12.hours, clock = clock)

    private val signedIn = authenticated(sessions.scheme) { it.subject }
    private val maybe = signedIn.optional()

    private val me = endpoint {
        get("me")
        authenticatedBy(signedIn)
        text()
    }

    private val hello = endpoint {
        get("hello")
        authenticatedBy(maybe)
        text()
    }

    private val api = api(
        listOf(
            ServerEndpoint(me) { p -> CompletableFuture.completedStage(p[signedIn] as Any?) },
            ServerEndpoint(hello) { p -> CompletableFuture.completedStage("hello ${p[maybe] ?: "stranger"}" as Any?) },
        ),
    ) { authenticate(sessions.scheme, sessions) }

    private fun get(path: String, cookie: ResponseCookie?): ClientResponse {
        val headers = listOfNotNull(cookie?.let { "Cookie" to "${it.name}=${it.value}" })
        return InMemoryClientTransport(api).send(ClientRequest(Method.GET, "http://test$path", headers))
            .toCompletableFuture().join()
    }

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    @Test
    fun `a started session is a safe cookie with 256 random bits, and the store keeps only their hash`() {
        val cookie = sessions.start(Identity("ada"))

        cookie.name shouldBe "sid"
        cookie.value shouldMatch Regex("[A-Za-z0-9_-]{43}")
        cookie.headerValue shouldBe "sid=${cookie.value}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=43200"
        store.keys shouldContainExactly listOf(sha256(cookie.value))
        sessions.start(Identity("ada")).value shouldNotBe cookie.value
    }

    @Test
    fun `the cookie is who the request is from`() {
        val response = get("/me", sessions.start(Identity("ada")))

        response.status shouldBe 200
        response.text() shouldBe "ada"
    }

    @Test
    fun `no cookie is nobody`() {
        get("/me", cookie = null).status shouldBe 401
        get("/hello", cookie = null).text() shouldBe "hello stranger"
    }

    @Test
    fun `a cookie for a session that is not there reads as nobody, since a browser keeps sending an ended one`() {
        val forged = ResponseCookie("sid", "x".repeat(43))

        get("/me", forged).status shouldBe 401
        get("/hello", forged).text() shouldBe "hello stranger"
    }

    @Test
    fun `an ended session is a 401, and end clears the cookie`() {
        val cookie = sessions.start(Identity("ada"))
        val credentials = object : Credentials {
            override fun header(name: String): String? = null
            override fun cookie(name: String): String? = if (name == "sid") cookie.value else null
        }

        sessions.end(credentials).headerValue shouldBe "sid=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0"
        get("/me", cookie).status shouldBe 401
    }

    @Test
    fun `ending every session of a subject ends this one`() {
        val cookie = sessions.start(Identity("ada"))

        sessions.endAll("ada")

        get("/me", cookie).status shouldBe 401
    }

    @Test
    fun `a session left idle expires`() {
        val cookie = sessions.start(Identity("ada"))

        clock.advance(30.minutes)

        get("/me", cookie).status shouldBe 401
    }

    @Test
    fun `use slides the idle expiry, at most once a minute`() {
        val cookie = sessions.start(Identity("ada"))

        repeat(3) {
            clock.advance(20.seconds)
            get("/me", cookie).status shouldBe 200
        }
        store.touches shouldBe 1

        clock.advance(29.minutes)
        get("/me", cookie).status shouldBe 200
        store.touches shouldBe 2
    }

    @Test
    fun `however busy, a session ends at its absolute lifetime`() {
        val cookie = sessions.start(Identity("ada"))

        repeat(12 * 4) {
            clock.advance(15.minutes)
            get("/me", cookie)
        }

        get("/me", cookie).status shouldBe 401
    }

    @Test
    fun `the scheme is the cookie, as an API key`() {
        val scheme = sessions.scheme
        scheme.location shouldBe "cookie"
        scheme.paramName shouldBe "sid"
    }
}
