package io.github.matthewjones372.pelican

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** A browser attaches a cookie to a request another site makes it send; one changing state is refused. Spec 0094. */
class CrossSiteTest {

    private val sessions = sessionCookie("sid")
    private val visitor = authenticated(sessions.scheme) { it.subject }

    private val bearer = bearerAuth()
    private val holder = authenticated(bearer) { it.subject }
    private val tokens = Authenticator { it.bearerToken()?.let(::Identity) }

    private val ran = AtomicInteger()

    private val post = endpoint {
        post("notes")
        authenticatedBy(visitor)
        text()
    }

    private val read = endpoint {
        get("notes")
        authenticatedBy(visitor)
        text()
    }

    private val withToken = endpoint {
        post("tokens")
        authenticatedBy(holder)
        text()
    }

    private val observed = mutableListOf<String>()

    private fun api(csrf: Boolean = true, cors: Cors? = null) = api(
        listOf(post, read, withToken).map { ep ->
            ServerEndpoint(ep) { CompletableFuture.completedStage("done" as Any?).also { ran.incrementAndGet() } }
        },
    ) {
        authenticate(sessions.scheme, sessions)
        authenticate(bearer, tokens)
        this.csrf = csrf
        this.cors = cors
        onRefusal { reason, status, _ -> observed += "${reason.label} $status" }
    }

    private val cookie = sessions.start(Identity("ada")).let { "sid=${it.value}" }

    private fun send(
        method: Method,
        path: String,
        vararg headers: Pair<String, String>,
        api: Api = api(),
        withCookie: Boolean = true,
    ): Int {
        val sent =
            listOf("Host" to "notes.example") + headers + listOfNotNull(("Cookie" to cookie).takeIf { withCookie })
        val body = if (method == Method.GET) ClientRequest.Body.Empty else ClientRequest.Body.Text("")
        return InMemoryClientTransport(api)
            .send(ClientRequest(method, "http://notes.example$path", sent, body = body))
            .toCompletableFuture().join().status
    }

    @Test
    fun `a cookie POST from another site is refused with 403, and the handler never runs`() {
        send(Method.POST, "/notes", "Origin" to "https://evil.example") shouldBe 403
        ran.get() shouldBe 0
        observed shouldContainExactly listOf("forbidden 403")
    }

    @Test
    fun `the same POST from this site goes through`() {
        send(Method.POST, "/notes", "Origin" to "https://notes.example") shouldBe 200
    }

    @Test
    fun `without an Origin, Sec-Fetch-Site decides`() {
        send(Method.POST, "/notes", "Sec-Fetch-Site" to "cross-site") shouldBe 403
        send(Method.POST, "/notes", "Sec-Fetch-Site" to "same-site") shouldBe 403
        send(Method.POST, "/notes", "Sec-Fetch-Site" to "same-origin") shouldBe 200
        send(Method.POST, "/notes", "Sec-Fetch-Site" to "none") shouldBe 200
    }

    @Test
    fun `a request with neither header is not from a browser, and goes through`() {
        send(Method.POST, "/notes") shouldBe 200
    }

    @Test
    fun `an opaque origin is another site`() {
        send(Method.POST, "/notes", "Origin" to "null") shouldBe 403
    }

    @Test
    fun `a GET changes nothing, so it is not checked`() {
        send(Method.GET, "/notes", "Origin" to "https://evil.example") shouldBe 200
    }

    @Test
    fun `an origin CORS lets send credentials is let in, and one it merely allows is not`() {
        val trusted = cors("https://app.example", allowCredentials = true)
        send(Method.POST, "/notes", "Origin" to "https://app.example", api = api(cors = trusted)) shouldBe 200

        val anyone = cors(CorsOrigins.Any)
        send(Method.POST, "/notes", "Origin" to "https://app.example", api = api(cors = anyone)) shouldBe 403
    }

    @Test
    fun `a bearer token is not something a browser attaches by itself, so it is not checked`() {
        send(
            Method.POST,
            "/tokens",
            "Origin" to "https://evil.example",
            "Authorization" to "Bearer ada",
            withCookie = false,
        ) shouldBe 200
    }

    @Test
    fun `no cookie at all is a 401, since there is nobody to forge a request for`() {
        send(Method.POST, "/notes", "Origin" to "https://evil.example", withCookie = false) shouldBe 401
    }

    @Test
    fun `an Api can turn the check off`() {
        send(Method.POST, "/notes", "Origin" to "https://evil.example", api = api(csrf = false)) shouldBe 200
    }

    @Test
    fun `the 403 is declared on a cookie endpoint that changes state, and on no other`() {
        post.errors.single { it.status == 403 } shouldBeSameInstanceAs visitor.crossSiteRefused
        read.errors.none { it.status == 403 } shouldBe true
        withToken.errors.none { it.status == 403 } shouldBe true
    }
}
