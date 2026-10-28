package io.github.matthewjones372.pelican

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** What one tenant's caller, key or session may do in another: nothing. Spec 0095. */
class TenantScopeTest {

    private val directory = InMemoryTenantDirectory(setOf("acme", "globex"))
    private val tenant = tenantFrom(TenantResolver.header("X-Tenant"))

    private val bearer = bearerAuth()
    private val caller = authenticated(bearer) { it.subject }

    /** `Bearer ada@acme` is ada with the claim `tenant=acme`; `Bearer ada` carries no claim. */
    private val tokens = Authenticator { c ->
        c.bearerToken()?.let { token ->
            val (subject, home) = token.split('@').let { it[0] to it.getOrNull(1) }
            Identity(subject, claims = listOfNotNull(home?.let { "tenant" to it }).toMap())
        }
    }

    private val ran = AtomicInteger()
    private val observed = mutableListOf<String>()

    private val orders = endpoint {
        get("orders")
        authenticatedBy(caller)
        tenanted(tenant)
        text()
    }

    private val placeOrder = endpoint {
        post("orders")
        authenticatedBy(caller)
        tenanted(tenant)
        idempotencyKey()
        text()
    }

    private fun api(claim: String? = "tenant") = api(
        listOf(
            ServerEndpoint(orders) { p -> CompletableFuture.completedStage("${p[caller]} in ${p[tenant].id}" as Any?) },
            ServerEndpoint(placeOrder) { p ->
                CompletableFuture.completedStage("order ${ran.incrementAndGet()} for ${p[tenant].id}" as Any?)
            },
        ),
    ) {
        authenticate(bearer, tokens)
        tenants(tenant, directory, claim = claim)
        idempotency(InMemoryIdempotencyStore())
        onRefusal { reason, status, _ -> observed += "${reason.label} $status" }
    }

    private fun send(api: Api, method: Method, tenantId: String, token: String, key: String? = null): ClientResponse {
        val headers = listOfNotNull(
            "X-Tenant" to tenantId,
            "Authorization" to "Bearer $token",
            key?.let { "Idempotency-Key" to it },
        )
        val body = if (method == Method.GET) ClientRequest.Body.Empty else ClientRequest.Body.Text("")
        return InMemoryClientTransport(api)
            .send(ClientRequest(method, "http://api.example/orders", headers, body = body))
            .toCompletableFuture().join()
    }

    // ---------------------------------------------------------------- the caller's claim

    @Test
    fun `a caller whose claim names this tenant is let in`() {
        send(api(), Method.GET, "acme", "ada@acme").text() shouldBe "ada in acme"
    }

    @Test
    fun `a caller whose claim names another tenant is a 403, seen as a refusal`() {
        send(api(), Method.GET, "globex", "ada@acme").status shouldBe 403
        observed shouldContainExactly listOf("forbidden 403")
    }

    @Test
    fun `a caller with no claim at all is not checked`() {
        send(api(), Method.GET, "globex", "ada").text() shouldBe "ada in globex"
    }

    @Test
    fun `an Api that names no claim checks none`() {
        send(api(claim = null), Method.GET, "globex", "ada@acme").text() shouldBe "ada in globex"
    }

    @Test
    fun `the 403 is declared on a tenanted endpoint with a caller, and not on one without`() {
        orders.errors.single { it.status == 403 } shouldBeSameInstanceAs tenant.foreign

        val open = endpoint {
            get("open")
            tenanted(tenant)
            text()
        }
        open.errors.none { it.status == 403 } shouldBe true
    }

    // -------------------------------------------------------------------- idempotency

    @Test
    fun `a key is the tenant's own, so the same key in another tenant runs again`() {
        val api = api()

        send(api, Method.POST, "acme", "ada", key = "k-1").text() shouldBe "order 1 for acme"
        send(api, Method.POST, "acme", "ada", key = "k-1").text() shouldBe "order 1 for acme"
        send(api, Method.POST, "globex", "ada", key = "k-1").text() shouldBe "order 2 for globex"
        ran.get() shouldBe 2
    }

    // ----------------------------------------------------------------------- sessions

    private val sessions = sessionCookie("sid")
    private val visitor = authenticated(sessions.scheme) { it.subject }.optional()

    private val notes = endpoint {
        get("notes")
        authenticatedBy(visitor)
        tenanted(tenant)
        text()
    }

    private val home = endpoint {
        get("home")
        authenticatedBy(visitor)
        text()
    }

    private val visited = api(
        listOf(notes, home).map { ep ->
            ServerEndpoint(ep) { p -> CompletableFuture.completedStage((p[visitor] ?: "nobody") as Any?) }
        },
    ) {
        authenticate(sessions.scheme, sessions)
        tenants(tenant, directory)
    }

    private fun visit(path: String, cookie: ResponseCookie, tenantId: String? = null): String {
        val headers = listOfNotNull("Cookie" to "sid=${cookie.value}", tenantId?.let { "X-Tenant" to it })
        return InMemoryClientTransport(visited).send(ClientRequest(Method.GET, "http://api.example$path", headers))
            .toCompletableFuture().join().text()
    }

    @Test
    fun `a session started in one tenant is someone there and nobody in another`() {
        val cookie = sessions.start(Identity("ada"), Tenant("acme"))

        visit("/notes", cookie, "acme") shouldBe "ada"
        visit("/notes", cookie, "globex") shouldBe "nobody"
    }

    @Test
    fun `a session started in no tenant, as an untenanted sign-in does, is someone in every one`() {
        val cookie = sessions.start(Identity("ada"))

        visit("/notes", cookie, "acme") shouldBe "ada"
        visit("/notes", cookie, "globex") shouldBe "ada"
    }

    @Test
    fun `a tenant's session is someone on an endpoint that serves no one tenant`() {
        visit("/home", sessions.start(Identity("ada"), Tenant("acme"))) shouldBe "ada"
    }
}
