package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KType

/** What a caller must have, declared on the endpoint, enforced before anything else is read. Spec 0093. */
class RequiresTest {

    /** Reads only a JSON object, so a body that is read at all shows up as a 400. */
    private object Objects : Codecs {
        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: KType): BodyCodec<T> = object : BodyCodec<String> {
            override fun encodeToString(value: String) = value
            override fun decodeFromString(text: String) = text.also { require(it.startsWith("{")) { "Not an object" } }
        } as BodyCodec<T>

        override fun schema(type: KType, components: SchemaComponents): JsonObj = jsonObj { "type" to "string" }
    }

    private val bearer = bearerAuth()
    private val caller = authenticated(bearer) { it.subject }

    private val people = Authenticator { credentials ->
        when (credentials.bearerToken()) {
            null -> null

            "support" ->
                Identity("sam", groups = setOf("support"), claims = mapOf("scope" to "orders:read orders:refund"))

            "reader" -> Identity("rae", groups = setOf("support"), claims = mapOf("scope" to "orders:read"))

            "listed" -> Identity("lee", groups = setOf("support"), claims = mapOf("scp" to listOf("orders:refund")))

            "both" -> Identity("bo", claims = mapOf("scope" to "orders:read", "scp" to listOf("orders:refund")))

            "admin" -> Identity("ada", groups = setOf("admin"))

            else -> throw Unauthenticated("Not a token anyone issued")
        }
    }

    private val ran = AtomicInteger()

    private val amount = jsonBody<String>()

    private val refund = endpoint(amount) {
        post("refunds")
        authenticatedBy(caller)
        requires(role("support"), permission("orders:refund"))
        text()
    }

    private val audit = endpoint {
        get("audit")
        authenticatedBy(caller.optional())
        requires(anyOf(role("admin"), permission("orders:refund")))
        text()
    }

    private fun answered(text: String) = CompletableFuture.completedStage(text as Any?).also { ran.incrementAndGet() }

    private fun send(method: Method, path: String, token: String?, body: String = """{"amount":5}"""): ClientResponse {
        val api = api(
            listOf(
                ServerEndpoint(refund) { answered("refunded") },
                ServerEndpoint(audit) { answered("audited") },
            ),
            codecs = Objects,
        ) { authenticate(bearer, people) }
        val headers =
            listOfNotNull(token?.let { "Authorization" to "Bearer $it" }, "Content-Type" to "application/json")
        val sent = if (method == Method.POST) ClientRequest.Body.Text(body) else ClientRequest.Body.Empty
        return InMemoryClientTransport(api)
            .send(ClientRequest(method, "http://test$path", headers, body = sent))
            .toCompletableFuture().join()
    }

    @Test
    fun `a caller with every requirement is let in`() {
        send(Method.POST, "/refunds", "support").status shouldBe 200
        ran.get() shouldBe 1
    }

    @Test
    fun `a caller missing one is refused with 403 naming what it needs, and the handler never runs`() {
        val refused = send(Method.POST, "/refunds", "reader")

        refused.status shouldBe 403
        refused.text() shouldContain "permission `orders:refund`"
        ran.get() shouldBe 0
    }

    @Test
    fun `the 403 comes before the body is read`() {
        send(Method.POST, "/refunds", "support", body = "not json").status shouldBe 400
        send(Method.POST, "/refunds", "reader", body = "not json").status shouldBe 403
    }

    @Test
    fun `permissions are read from scp where there is no scope claim, and only then`() {
        send(Method.POST, "/refunds", "listed").status shouldBe 200
        send(Method.GET, "/audit", "both").status shouldBe 403
    }

    @Test
    fun `anyOf is met by either`() {
        send(Method.GET, "/audit", "admin").status shouldBe 200
        send(Method.GET, "/audit", "support").status shouldBe 200
        send(Method.GET, "/audit", "reader").status shouldBe 403
    }

    @Test
    fun `an optional caller who is absent is a 401 where something is required, since there is nobody to forbid`() {
        val refused = send(Method.GET, "/audit", token = null)

        refused.status shouldBe 401
        refused.header("WWW-Authenticate") shouldBe "Bearer"
    }

    @Test
    fun `a credential that does not verify is still a 401`() {
        send(Method.POST, "/refunds", "mallory").status shouldBe 401
    }

    @Test
    fun `the 403 is declared, unless the endpoint declares one of its own`() {
        refund.errors.single { it.status == 403 } shouldBeSameInstanceAs refund.requires?.refused
        refund.errors.single { it.status == 403 }.description shouldBe
            "The caller does not have role `support` and permission `orders:refund`."

        val own = endpoint {
            get("own")
            authenticatedBy(caller)
            requires(role("support"))
            errorResponse(403, "Ask your manager")
            text()
        }
        own.errors.filter { it.status == 403 }.map { it.description } shouldContainExactly listOf("Ask your manager")
    }

    @Test
    fun `requiring something of a caller the endpoint never names is refused when it is built`() {
        shouldThrow<IllegalArgumentException> {
            endpoint {
                get("orphan")
                requires(role("support"))
                text()
            }
        }.message shouldContain "authenticatedBy"
    }

    @Test
    fun `a second requires is refused, so all of them are in one place`() {
        shouldThrow<IllegalArgumentException> {
            endpoint {
                get("twice")
                authenticatedBy(caller)
                requires(role("support"))
                requires(role("admin"))
                text()
            }
        }.message shouldContain "one requires(...)"
    }

    @Test
    fun `a permission is one word`() {
        shouldThrow<IllegalArgumentException> { permission("orders:read orders:write") }
    }
}
