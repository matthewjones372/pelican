package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class TestCallersTest {

    data class Caller(val subject: String, val groups: Set<String>)

    private val bearer = bearerAuth()
    private val caller = authenticated(bearer) { Caller(it.subject, it.groups) }

    private val whoAmI = endpoint {
        get("me")
        authenticatedBy(caller)
        json<Caller>()
    }

    private val client = api(
        listOf(ServerEndpoint(whoAmI) { p -> CompletableFuture.completedStage(p[caller] as Any?) }),
        codecs = JacksonCodecs,
    ) { authenticate(bearer, TestCallers.of("ada" to setOf("customer"), "bob" to setOf("support"))) }.inMemory()

    @Test
    fun `a test calls as anyone it named, with no provider`() {
        client.signedInAs("ada").call(whoAmI, Unit) shouldBe Caller("ada", setOf("customer"))
        client.signedInAs("bob").call(whoAmI, Unit) shouldBe Caller("bob", setOf("support"))
    }

    @Test
    fun `and as nobody, or someone it did not name, is refused`() {
        client.response(whoAmI, Unit).status shouldBe 401
        client.signedInAs("eve").response(whoAmI, Unit).status shouldBe 401
    }
}
