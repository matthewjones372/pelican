package io.github.matthewjones372.pelican

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/** The 401 `authenticatedBy` answers with is declared by it, so the document and a client see it. Spec 0093. */
class CallerRefusalDeclarationTest {

    private val caller = authenticated(bearerAuth()) { it.subject }

    private fun unauthorizedOf(ep: Endpoint<*, *>): List<ErrorSpec> = ep.errors.filter { it.status == 401 }

    @Test
    fun `an endpoint taking a caller declares the 401 it is refused with, and the challenge that comes with it`() {
        val me = endpoint {
            get("me")
            authenticatedBy(caller)
            text()
        }

        val refused = unauthorizedOf(me).single()
        refused shouldBeSameInstanceAs caller.refused
        refused.type shouldBe null
        refused.headers.map { it.name to it.required } shouldContainExactly listOf("WWW-Authenticate" to true)
    }

    @Test
    fun `so does one whose caller is optional, since a credential that does not verify is refused there too`() {
        val greeting = endpoint {
            get("hello")
            authenticatedBy(caller.optional())
            text()
        }

        unauthorizedOf(greeting).single() shouldBeSameInstanceAs caller.refused
    }

    @Test
    fun `an endpoint that declares a 401 of its own keeps its own`() {
        val me = endpoint {
            get("me")
            authenticatedBy(caller)
            errorResponse(401, "Sign in at /login first")
            text()
        }

        unauthorizedOf(me).map { it.description } shouldContainExactly listOf("Sign in at /login first")
    }

    @Test
    fun `an endpoint without a caller declares no 401`() {
        unauthorizedOf(endpoint { get("health"); text() }).shouldBeEmpty()
    }
}
