package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * What a set of descriptions has to agree about before a document is written
 * from it. `Api` refuses a route clash already; a documentation-only build
 * never builds an `Api`, and until now published one of the two and never
 * mentioned the other.
 */
class DescribedApiChecksTest {

    private fun spec(vararg endpoints: Endpoint<*, *>) = ApiSpec(endpoints.toList(), NoCodecs)

    @Test
    fun `two endpoints on one route are refused where they are described`() {
        val one = endpoint { get("clash"); json<String>() }
        val two = endpoint { get("clash"); json<Int>() }

        shouldThrow<IllegalArgumentException> { spec(one, two) }
            .message shouldContain "GET /clash"
    }

    @Test
    fun `the same route under two methods is not a clash`() {
        shouldNotThrowAny {
            spec(
                endpoint { get("orders"); json<String>() },
                endpoint { post("orders"); json<String>() },
            )
        }
    }

    @Test
    fun `two endpoints cannot share an operationId`() {
        val one = endpoint { get("a"); operationId = "same"; json<String>() }
        val two = endpoint { get("b"); operationId = "same"; json<Int>() }

        shouldThrow<IllegalArgumentException> { spec(one, two) }
            .message shouldContain "same"
    }

    @Test
    fun `a webhook cannot take an endpoint's operationId either`() {
        val ep = endpoint { post("ping"); operationId = "notify"; empty() }
        val hook = webhook("notify") { empty(204) }

        shouldThrow<IllegalArgumentException> {
            ApiSpec(listOf(ep), NoCodecs, webhooks = listOf(hook))
        }.message shouldContain "notify"
    }

    @Test
    fun `derived operation names stay distinct because the paths are`() {
        shouldNotThrowAny {
            spec(
                endpoint { get("orders"); json<String>() },
                endpoint { get("users"); json<String>() },
            )
        }
    }
}
