package io.github.matthewjones372.pelican.test.jetty

import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.contentLocation
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QueryRoundTripTest {

    data class Filter(val status: String)

    data class Order(val id: Long, val status: String)

    private val orders = listOf(Order(1, "open"), Order(2, "shipped"), Order(3, "open"))

    private val search = endpoint(jsonBody<Filter>()) {
        query("orders" / "search")
        emits(contentLocation)
        json<List<Order>>()
    }

    private fun app() = api(
        listOf(
            search handledNow { filter ->
                setHeader(contentLocation, "/orders/search/${filter.status}")
                orders.filter { it.status == filter.status }
            },
        ),
        JacksonCodecs,
    ).inMemory()

    @Test
    fun `the typed client sends a QUERY and reads the typed answer back`() {
        app().use { client ->
            client.call(search, Filter("open")) shouldBe listOf(Order(1, "open"), Order(3, "open"))
        }
    }

    @Test
    fun `the request the client builds is a QUERY line carrying its body`() {
        app().use { client ->
            val request = client.request(search, Filter("open"))

            request.method shouldBe Method.QUERY
            request.toString() shouldBe "QUERY /orders/search"
            request.body shouldBe """{"status":"open"}"""
        }
    }

    @Test
    fun `a Content-Location the handler sets reaches the caller`() {
        app().use { client ->
            val res = client.response(search, Filter("open"))

            res shouldHaveStatus 200
            res.header("Content-Location") shouldBe "/orders/search/open"
        }
    }

    @Test
    fun `a QUERY without Content-Type is a 400 and one of another type is a 415`() {
        app().use { client ->
            val bare = RequestSpec(Method.QUERY, "/orders/search", emptyList(), emptyList(), null)

            client.transport.send(bare).status shouldBe 400
            client.transport.send(
                bare.withBody("x").withHeader("Content-Type", "text/plain"),
            ).status shouldBe 415
        }
    }

    @Test
    fun `a call repeated is answered the same way`() {
        app().use { client ->
            client.call(search, Filter("shipped")) shouldBe client.call(search, Filter("shipped"))
        }
    }
}
