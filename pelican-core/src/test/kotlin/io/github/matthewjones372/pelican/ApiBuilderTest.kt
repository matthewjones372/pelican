package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class ApiBuilderTest {

    private val userId = pathParam<Long>("userId")

    private val getUser = endpoint(userId) {
        get("users" / userId)
        operationId = "getUser"
        text()
    }

    private val listUsers = endpoint {
        get("users")
        operationId = "listUsers"
        text()
    }

    private val orderPlaced = webhook("orderPlaced") {
        body(jsonBody<String>())
        empty(status = 204)
    }

    private val stamp = before { }

    private fun bind(ep: Endpoint<*, *>) =
        ServerEndpoint(ep) { CompletableFuture.completedStage("ok" as Any?) }

    private val routes = listOf(bind(getUser))

    /**
     * The numbers written down here are the ones the constructor defaulted to
     * before there was a builder. A default that moves silently is a service
     * that behaves differently after an upgrade nobody read.
     */
    @Test
    fun `a block that says nothing leaves every setting where it has always been`() {
        val api = api(routes)

        api.codecs shouldBe NoCodecs
        api.title shouldBe "API"
        api.version shouldBe "1.0.0"
        api.description.shouldBeNull()
        api.servers.shouldBeEmpty()
        api.security.shouldBeEmpty()
        api.cors.shouldBeNull()
        api.strictBodyTimeoutMillis shouldBe 10_000L
        api.maxBodyBytes shouldBe 8L * 1024L * 1024L
        api.filters.shouldBeEmpty()
        api.exposeInternalErrors shouldBe false
        api.onServerError.shouldBeNull()
        api.covers.shouldBeEmpty()
        api.webhooks.shouldBeEmpty()
    }

    @Test
    fun `what the block sets is what the Api carries`() {
        val policy = cors("https://shop.example")
        val scheme = bearerAuth().requires()
        var reported: String? = null

        val api = api(routes, NoCodecs) {
            title = "Orders"
            version = "2.0.0"
            description = "Orders, described as values."
            servers = listOf("https://orders.example")
            security = listOf(scheme)
            cors = policy
            strictBodyTimeoutMillis = 500
            maxBodyBytes = 1024
            exposeInternalErrors = true
            covers = listOf(getUser)
            webhooks = listOf(orderPlaced)
            filter(stamp)
            onError { reference, _, _ -> reported = reference }
        }

        api.title shouldBe "Orders"
        api.version shouldBe "2.0.0"
        api.description shouldBe "Orders, described as values."
        api.servers shouldContainExactly listOf("https://orders.example")
        api.security shouldContainExactly listOf(scheme)
        api.cors shouldBe policy
        api.strictBodyTimeoutMillis shouldBe 500L
        api.maxBodyBytes shouldBe 1024L
        api.exposeInternalErrors shouldBe true
        api.covers shouldContainExactly listOf(getUser)
        api.webhooks shouldContainExactly listOf(orderPlaced)
        api.filters shouldContainExactly listOf(stamp)

        api.onServerError!!("ref-1", getUser, RuntimeException("boom"))
        reported shouldBe "ref-1"
    }

    /** Repeatable, because a filter chain is written one line at a time. */
    @Test
    fun `filters run in the order they were written`() {
        val second = before { }
        val api = api(routes) {
            filter(stamp)
            filter(second)
        }

        api.filters shouldContainExactly listOf(stamp, second)
    }

    /**
     * The builder is a live object for exactly as long as the block runs. A
     * reference kept past that is writing into a value somebody else is already
     * serving traffic from, so what it writes has to go nowhere.
     */
    @Test
    fun `a builder written into after the Api is built cannot change it`() {
        lateinit var escaped: ApiBuilder

        val api = api(routes) {
            escaped = this
            title = "Orders"
            filter(stamp)
        }

        escaped.title = "Something else"
        escaped.maxBodyBytes = 1
        escaped.covers = listOf(listUsers)
        escaped.filter(before { })
        escaped.onError { _, _, _ -> }

        api.title shouldBe "Orders"
        api.maxBodyBytes shouldBe 8L * 1024L * 1024L
        api.covers.shouldBeEmpty()
        api.filters shouldContainExactly listOf(stamp)
        api.onServerError.shouldBeNull()
    }

    /** The checks the constructor made are the factory's too. */
    @Test
    fun `the startup checks still run`() {
        val failure = shouldThrow<IllegalArgumentException> { api(listOf(bind(getUser), bind(getUser))) }
        failure.message shouldContain "can never be reached"
    }
}
