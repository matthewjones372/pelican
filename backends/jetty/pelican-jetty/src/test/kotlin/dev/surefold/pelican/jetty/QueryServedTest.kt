package dev.surefold.pelican.jetty

import dev.surefold.pelican.api
import dev.surefold.pelican.contentLocation
import dev.surefold.pelican.div
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.jsonBody
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

data class Filter(val status: String)

data class Found(val status: String, val count: Int)

/** RFC 10008 over a socket: a body-bearing read whose Content-Type is checked before the handler runs. */
class QueryServedTest {

    companion object {
        private val handled = AtomicInteger()

        private val search = endpoint(jsonBody<Filter>()) {
            query("orders" / "search")
            emits(contentLocation)
            json<Found>()
        }

        private val server = api(
            listOf(
                search handledNow { filter ->
                    handled.incrementAndGet()
                    Found(filter.status, filter.status.length)
                },
            ),
            JacksonCodecs,
        ).start(port = 0)

        private val asking = Asking(listOf("Content-Type", "Allow"))

        @JvmStatic
        @AfterAll
        fun stop() {
            asking.close()
            server.stop()
        }
    }

    private fun query(headers: Map<String, String>, body: String?) =
        asking.ask(server.baseUrl, "QUERY", "/orders/search", headers, body)

    @Test
    fun `a QUERY with a JSON Content-Type reaches the handler with its body`() {
        val answer = query(mapOf("Content-Type" to "application/json"), """{"status":"open"}""")

        answer.status shouldBe 200
        answer.body shouldContain "\"status\":\"open\""
    }

    @Test
    fun `a Content-Type parameter does not change the media type`() {
        query(mapOf("Content-Type" to "application/json; charset=utf-8"), """{"status":"open"}""").status shouldBe 200
    }

    @Test
    fun `a QUERY with content and no Content-Type is a 400 and the handler never runs`() {
        val before = handled.get()

        val answer = query(emptyMap(), """{"status":"open"}""")

        answer.status shouldBe 400
        answer.body shouldContain "Content-Type"
        handled.get() shouldBe before
    }

    @Test
    fun `a QUERY with a media type the endpoint does not read is a 415`() {
        val before = handled.get()

        query(mapOf("Content-Type" to "text/plain"), "status=open").status shouldBe 415
        handled.get() shouldBe before
    }

    @Test
    fun `a malformed body is a 400`() {
        query(mapOf("Content-Type" to "application/json"), "{not json").status shouldBe 400
    }

    @Test
    fun `repeating a QUERY repeats the answer, as a safe and idempotent method must`() {
        val body = """{"status":"open"}"""
        val first = query(mapOf("Content-Type" to "application/json"), body)
        val second = query(mapOf("Content-Type" to "application/json"), body)

        first.status shouldBe 200
        second shouldBe first
    }

    @Test
    fun `a GET at the QUERY path is a 405 that lists QUERY`() {
        val answer = asking.ask(server.baseUrl, "GET", "/orders/search")

        answer.status shouldBe 405
        answer.body shouldContain "QUERY"
    }
}
