package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class CallersTest {

    data class Caller(val subject: String, val groups: Set<String>)

    private val bearer = bearerAuth()
    private val caller = authenticated(bearer) { Caller(it.subject, it.groups) }
    private val maybe = caller.optional()

    private val people = Authenticator { credentials ->
        when (val token = credentials.bearerToken()) {
            null -> null
            "ada" -> Identity("ada", groups = setOf("customer"))
            else -> throw Unauthenticated("'$token' is not anyone")
        }
    }

    private val page = queryParam<Int>("page")
    private val ran = AtomicInteger()

    private val whoAmI = endpoint(page) {
        get("me")
        authenticatedBy(caller)
        text()
    }

    private val greeting = endpoint {
        get("hello")
        authenticatedBy(maybe)
        text()
    }

    private fun api(bind: Boolean = true) = api(
        listOf(
            ServerEndpoint(whoAmI) { p ->
                ran.incrementAndGet()
                CompletableFuture.completedStage("${p[caller].subject} ${p[caller].groups} page ${p[page]}" as Any?)
            },
            ServerEndpoint(greeting) { p ->
                CompletableFuture.completedStage("hello ${p[maybe]?.subject ?: "stranger"}" as Any?)
            },
        ),
    ) { if (bind) authenticate(bearer, people) }

    private fun get(path: String, vararg headers: Pair<String, String>): ClientResponse =
        InMemoryClientTransport(api())
            .send(ClientRequest(Method.GET, "http://test$path", headers.toList()))
            .toCompletableFuture().join()

    @Test
    fun `a request naming nobody is refused with 401 and the handler never runs`() {
        val refused = get("/me?page=1")

        refused.status shouldBe 401
        refused.header("WWW-Authenticate") shouldBe "Bearer"
        ran.get() shouldBe 0
    }

    @Test
    fun `and so is one whose credential does not verify, saying so`() {
        val refused = get("/me?page=1", "Authorization" to "Bearer mallory")

        refused.status shouldBe 401
        refused.header("WWW-Authenticate") shouldBe "Bearer error=\"invalid_token\""
        refused.text() shouldContain "'mallory' is not anyone"
        ran.get() shouldBe 0
    }

    @Test
    fun `the 401 comes before anything else about the request is judged`() {
        get("/me").status shouldBe 401
    }

    @Test
    fun `a verified caller is handed to the handler as the service's own type`() {
        val answer = get("/me?page=2", "Authorization" to "Bearer ada")

        answer.status shouldBe 200
        answer.text() shouldBe "ada [customer] page 2"
    }

    @Test
    fun `an optional caller is null for nobody, the caller for someone, and still refuses a bad credential`() {
        get("/hello").text() shouldBe "hello stranger"
        get("/hello", "Authorization" to "Bearer ada").text() shouldBe "hello ada"
        get("/hello", "Authorization" to "Bearer mallory").status shouldBe 401
    }

    @Test
    fun `the caller's scheme is the endpoint's requirement, and nobody else's`() {
        whoAmI.security!!.single().scheme shouldBe bearer
        endpoint { get("open"); text() }.security shouldBe null
    }

    @Test
    fun `a requirement stated beside the caller is refused, since it could only disagree`() {
        shouldThrow<IllegalArgumentException> {
            endpoint {
                get("both")
                authenticatedBy(caller)
                noSecurity()
                text()
            }
        }.message shouldContain "already its requirement"
    }

    @Test
    fun `a caller listed as an input is refused, since no client could send one`() {
        shouldThrow<IllegalArgumentException> {
            endpoint(caller) { get("me"); text() }
        }.message shouldContain "authenticatedBy"
    }

    @Test
    fun `an API whose callers nothing verifies fails when it is built, not on every request`() {
        shouldThrow<IllegalArgumentException> { api(bind = false) }.message shouldContain "bearerAuth"
    }
}
