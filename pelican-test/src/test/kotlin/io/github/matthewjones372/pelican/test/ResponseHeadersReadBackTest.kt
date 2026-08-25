package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.responseHeader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ResponseHeadersReadBackTest {

    data class Problem(val code: String)

    private val retryAfter = responseHeader<Long>("Retry-After", "Seconds to wait")
    private val throttled = errorJson<Problem>(429, "Too many requests", retryAfter)

    private val placeOrder = endpoint {
        post("orders")
        json<Problem>() orFail throttled
    }

    private fun answering(vararg headers: Pair<String, String>) = ApiClient(
        transport = object : Transport {
            override fun send(request: RequestSpec) =
                ResponseSpec(429, headers.toList(), """{"code":"slow-down"}""")
        },
        codecs = JacksonCodecs,
    )

    @Test
    fun `a declared header reads back as the type it was declared as`() {
        val refused = answering("Retry-After" to "30").outcome(placeOrder, Unit) as Outcome.Err

        refused[retryAfter] shouldBe 30L
    }

    @Test
    fun `one the server sent unreadably is null, and the failure it came with survives`() {
        val refused = answering("Retry-After" to "soon").outcome(placeOrder, Unit) as Outcome.Err

        refused[retryAfter].shouldBeNull()
        // The assertion the test was really making, still reachable.
        refused.error shouldBe Problem("slow-down")
    }
}
