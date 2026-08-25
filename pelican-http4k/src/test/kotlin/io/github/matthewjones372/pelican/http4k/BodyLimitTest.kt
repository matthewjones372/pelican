package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.junit.jupiter.api.Test

/**
 * `Api.maxBodyBytes` is a number of bytes. It was checked against
 * `String.length`, which counts UTF-16 code units — so the limit admitted about
 * three times what it promised for anything outside the Latin range, and it was
 * checked only once the whole body was already in memory.
 */
class BodyLimitTest {

    private val small: HttpHandler = Api(
        endpoints = testApi().endpoints,
        codecs = JacksonCodecs,
        maxBodyBytes = 64,
    ).toHttpHandler()

    private fun post(body: String) = small(
        Request(Method.POST, "/items").header("X-Api-Key", "let-me-in").body(body),
    )

    @Test
    fun `a body inside the limit is served`() {
        post("""{"name":"rope"}""").status.code shouldBe 201
    }

    @Test
    fun `a body over the limit in bytes is refused`() {
        post("""{"name":"${"r".repeat(200)}"}""").status.code shouldBe 413
    }

    @Test
    fun `characters are not bytes, and the limit counts bytes`() {
        // 30 characters, 90 bytes. Under a character-counted limit of 64 this
        // was served; the limit says bytes, so it is refused.
        val wide = "五".repeat(30)
        wide.length shouldBe 30
        val res = post("""{"name":"$wide"}""")
        res.status.code shouldBe 413
        res.bodyString() shouldContain "Payload too large"
    }
}
