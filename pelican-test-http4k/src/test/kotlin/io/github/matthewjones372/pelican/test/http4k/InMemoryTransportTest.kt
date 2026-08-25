package io.github.matthewjones372.pelican.test.http4k

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.http4k.handledNow
import io.github.matthewjones372.pelican.http4k.handledOrFail
import io.github.matthewjones372.pelican.http4k.handledWith
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.responseHeader
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.shouldBeFailure
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The transport this module exists to provide, which nothing ran.
 *
 * It is small — an `HttpHandler` is already a function from request to response,
 * so this is that function with `RequestSpec` translated in and `ResponseSpec`
 * out — and small is exactly how a translation gains a bug nobody notices: a
 * header dropped on the way in, a status read off the wrong field on the way
 * back. `pelican-test-pekko` has had a suite since it was written; this had
 * none.
 */
class InMemoryTransportTest {

    private data class Widget(val id: Long, val name: String)

    private val widgetId = pathParam<Long>("widgetId")
    private val body = jsonBody<Widget>()
    private val etag = responseHeader<String>("ETag")
    private val missing = errorJson<ApiError>(404, "No widget by that id")

    private val getWidget = endpoint(widgetId) {
        get("widgets" / widgetId)
        emits(etag)
        json<Widget>() orFail missing
    }

    private val putWidget = endpoint(body) {
        put("widgets")
        json<Widget>(201)
    }

    private val deleteWidget = endpoint(widgetId) {
        delete("widgets" / widgetId)
        empty(204)
    }

    private val app = Api(
        endpoints = listOf(
            getWidget handledOrFail { id ->
                if (id == 1L) {
                    setHeader(etag, "\"v1\"")
                    ok(Widget(1, "kettle"))
                } else {
                    missing(ApiError(404, "No widget $id"))
                }
            },
            putWidget handledNow { sent -> sent.copy(id = 9) },
            deleteWidget handledWith { },
        ),
        codecs = JacksonCodecs,
    ).inMemoryHttp4k()

    @Test
    fun `a typed call goes through the interpreted handler and comes back decoded`() {
        app.call(getWidget, 1L) shouldBe Widget(1, "kettle")
    }

    @Test
    fun `a declared failure arrives as the failure rather than as an exception`() {
        val carried = app.outcome(getWidget, 2L).shouldBeFailure(missing)
        carried.status shouldBe 404
        carried.error shouldContain "No widget 2"
    }

    @Test
    fun `response headers survive the translation back`() {
        app.response(getWidget, 1L).header("ETag") shouldBe "\"v1\""
    }

    @Test
    fun `a body is sent with a content type even though the description does not say one`() {
        // The translation supplies `application/json` when the caller named
        // nothing, which is what stops a JSON body arriving as a 415.
        app.call(putWidget, Widget(0, "teapot")) shouldBe Widget(9, "teapot")
    }

    @Test
    fun `a 204 comes back with the status and no body`() {
        app.response(deleteWidget, 1L).let {
            it shouldHaveStatus 204
            it.body shouldBe ""
        }
    }

    @Test
    fun `every method a description can declare survives the translation`() {
        // A `when` with no else on the Pelican side, so a new method would fail
        // to compile there — but nothing checked the mapping was right.
        Method.entries.forEach { method ->
            val res = app.transport.send(RequestSpec(method, "/nothing-here", emptyList(), emptyList(), null))
            res.status shouldBe 404
        }
    }

    @Test
    fun `a path nobody described is a 404 rather than a thrown translation`() {
        app.transport.send(RequestSpec(Method.GET, "/absent", emptyList(), emptyList(), null))
            .status shouldBe 404
    }
}
