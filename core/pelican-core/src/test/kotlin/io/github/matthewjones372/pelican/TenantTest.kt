package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** Which tenant a request is for, found once, checked against the directory, and handed over typed. Spec 0095. */
class TenantTest {

    private val directory = InMemoryTenantDirectory(setOf("acme", "globex"))
    private val ran = AtomicInteger()

    private fun served(input: TenantInput, ep: Endpoint<*, *>, caller: Pair<SecurityScheme, Authenticator>? = null) =
        api(
            listOf(
                ServerEndpoint(ep) { p ->
                    ran.incrementAndGet()
                    CompletableFuture.completedStage(p[input].id as Any?)
                },
            ),
        ) {
            tenants(input, directory)
            caller?.let { (scheme, by) -> authenticate(scheme, by) }
        }

    private fun get(api: Api, url: String, vararg headers: Pair<String, String>): ClientResponse =
        InMemoryClientTransport(api).send(ClientRequest(Method.GET, url, headers.toList())).toCompletableFuture().join()

    // --------------------------------------------------------------------------- a header

    private val byHeader = tenantFrom(TenantResolver.header("X-Tenant"))

    private val orders = endpoint {
        get("orders")
        tenanted(byHeader)
        text()
    }

    @Test
    fun `a header names the tenant, handed to the handler as a Tenant`() {
        val answered = get(served(byHeader, orders), "http://api.example/orders", "X-Tenant" to "acme")

        answered.status shouldBe 200
        answered.text() shouldBe "acme"
    }

    @Test
    fun `no tenant is a 400 and an unknown one a 404, and the handler runs for neither`() {
        val api = served(byHeader, orders)

        get(api, "http://api.example/orders").status shouldBe 400
        get(api, "http://api.example/orders", "X-Tenant" to "initech").status shouldBe 404
        ran.get() shouldBe 0
    }

    // ------------------------------------------------------------------------ a subdomain

    private val bySubdomain = tenantFrom(TenantResolver.subdomain(of = "example.com"))

    private val home = endpoint {
        get("home")
        tenanted(bySubdomain)
        text()
    }

    @Test
    fun `a subdomain names the tenant, and the bare domain or another one names none`() {
        val api = served(bySubdomain, home)

        get(api, "http://acme.example.com/home", "Host" to "acme.example.com:8443").text() shouldBe "acme"
        get(api, "http://example.com/home", "Host" to "example.com").status shouldBe 400
        get(api, "http://acme.evil.com/home", "Host" to "acme.evil.com").status shouldBe 400
        get(api, "http://a.b.example.com/home", "Host" to "a.b.example.com").status shouldBe 400
    }

    // ---------------------------------------------------------------------------- a claim

    private val bearer = bearerAuth()
    private val caller = authenticated(bearer) { it.subject }
    private val tokens = Authenticator { c ->
        c.bearerToken()?.let { Identity(it, claims = mapOf("tenant" to "globex")) }
    }
    private val byClaim = tenantFrom(TenantResolver.claim("tenant"))

    private val mine = endpoint {
        get("mine")
        authenticatedBy(caller)
        tenanted(byClaim)
        text()
    }

    @Test
    fun `a claim on the caller names the tenant`() {
        get(served(byClaim, mine, bearer to tokens), "http://api.example/mine", "Authorization" to "Bearer ada")
            .text() shouldBe "globex"
    }

    @Test
    fun `no caller is a 401 before the tenant is asked about`() {
        get(served(byClaim, mine, bearer to tokens), "http://api.example/mine").status shouldBe 401
    }

    @Test
    fun `a claim resolver on an endpoint with no caller is refused when it is built`() {
        shouldThrow<IllegalArgumentException> {
            endpoint {
                get("nobody")
                tenanted(byClaim)
                text()
            }
        }.message shouldContain "authenticatedBy"
    }

    // ------------------------------------------------------------------------ declaring it

    @Test
    fun `the 400 and 404 are declared, unless the endpoint declares its own`() {
        orders.errors.filter { it.status == 400 || it.status == 404 }.map { it.status } shouldContainExactly
            listOf(400, 404)

        val own = endpoint {
            get("own")
            tenanted(byHeader)
            errorResponse(404, "No such shop")
            text()
        }
        own.errors.filter { it.status == 404 }.map { it.description } shouldContainExactly listOf("No such shop")
    }

    @Test
    fun `a tenanted endpoint on an Api that resolves no tenants fails when the Api is built`() {
        shouldThrow<IllegalArgumentException> {
            api(listOf(ServerEndpoint(orders) { CompletableFuture.completedStage("x" as Any?) }))
        }.message shouldContain "tenants("
    }

    @Test
    fun `a second tenanted is refused, since a request is for one tenant`() {
        shouldThrow<IllegalArgumentException> {
            endpoint {
                get("twice")
                tenanted(byHeader)
                tenanted(byHeader)
                text()
            }
        }
    }
}
