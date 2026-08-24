package dev.pelican.test

import dev.pelican.Outcome
import dev.pelican.endpoint
import dev.pelican.errorJson
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.noInputs
import dev.pelican.orFail
import dev.pelican.responseHeader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Reading a declared failure's headers back off a server this repository did
 * not write.
 *
 * The server under test is the thing being asserted about, so everything this
 * client is handed has to survive being wrong. A header is one fact about a
 * response among several, and the ones beside it are usually the ones the test
 * came for.
 */
class ResponseHeadersReadBackTest {

    data class Problem(val code: String)

    private val retryAfter = responseHeader<Long>("Retry-After", "Seconds to wait")
    private val throttled = errorJson<Problem>(429, "Too many requests", retryAfter)

    private val placeOrder = endpoint(noInputs) {
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
