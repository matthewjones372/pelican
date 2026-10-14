package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.responseHeader
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KType

/**
 * `Params.responseHeaders()` is put on whatever response came back, errors
 * included — deliberately, because a header set before a failure was set on
 * purpose. The document said otherwise: `emits(...)` reached every success and
 * no failure, so a header the service sends on every 429 appeared on none of
 * the 429s anybody could read about.
 */
class EmittedHeadersOnFailuresTest {

    /** Enough to publish a schema; what is in it is not the subject here. */
    private object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj =
            jsonObj { "type" to "object" }
    }

    private val requestId = responseHeader<String>("X-Request-Id", "Correlates this answer with the log")
    private val cursor = responseHeader<String>("X-Cursor").optional()
    private val retryAfter = responseHeader<Long>("Retry-After")

    private val throttled = errorJson<ApiError>(429, "Slow down", retryAfter)

    private val ep = endpoint {
        get("widgets")
        emits(requestId, cursor)
        json<String>() orFail throttled
    }

    private val doc = apiSpec(listOf(ep), Schemas).openApi()
    private val responses = doc / "paths" / "/widgets" / "get" / "responses"

    @Test
    fun `the endpoint's own headers reach the failure as well as the success`() {
        (responses / "200" / "headers").keys() shouldBe setOf("X-Request-Id", "X-Cursor")
        (responses / "429" / "headers").keys() shouldBe setOf("X-Request-Id", "X-Cursor", "Retry-After")
    }

    @Test
    fun `they are not promised on a failure, because a filter may refuse before the handler runs`() {
        (responses / "429" / "headers" / "X-Request-Id" / "required").bool() shouldBe false
        // Required where it was declared, all the same.
        (responses / "200" / "headers" / "X-Request-Id" / "required").bool() shouldBe true
    }

    @Test
    fun `a header the failure declared itself keeps the requirement it was given`() {
        (responses / "429" / "headers" / "Retry-After" / "required").bool() shouldBe true
    }

    @Test
    fun `one already optional stays optional wherever it appears`() {
        (responses / "200" / "headers" / "X-Cursor" / "required").bool() shouldBe false
        (responses / "429" / "headers" / "X-Cursor" / "required").bool() shouldBe false
    }
}
