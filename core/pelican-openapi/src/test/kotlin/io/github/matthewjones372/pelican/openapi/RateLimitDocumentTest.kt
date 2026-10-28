package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.rateLimit
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** The 429 a rate limit answers with, written in the refusal envelope. Spec 0096. */
class RateLimitDocumentTest {

    private val caller = authenticated(bearerAuth()) { it.subject }

    private val orders = endpoint {
        get("orders")
        authenticatedBy(caller)
        rateLimited(rateLimit("orders", 100, per = 1.minutes))
        text()
    }

    @Test
    fun `the 429 is in the refusal envelope`() {
        val document = apiSpec(listOf(orders), SecurityTest.Schemas).openApi()
        val operation = (document / "paths" / "/orders" / "get") as JsonObj

        (operation / "responses" / "429" / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/ApiError"
    }
}
