package io.github.matthewjones372.pelican.ktor

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

/**
 * `Api.maxBodyBytes` is a number of bytes. It was checked against
 * `String.length`, which counts UTF-16 code units, and only once the whole body
 * had already been read into a `String`.
 */
class BodyLimitTest {

    private fun served(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            pelican(
                api(endpoints = testApi().endpoints, codecs = JacksonCodecs) {
                    maxBodyBytes = 64
                },
            )
        }
        block(client)
    }

    private suspend fun io.ktor.client.HttpClient.send(body: String): HttpResponse =
        post("/items") {
            header("X-Api-Key", "let-me-in")
            setBody(body)
        }

    @Test
    fun `a body inside the limit is served`() = served { client ->
        client.send("""{"name":"rope"}""").status.value shouldBe 201
    }

    @Test
    fun `a body over the limit in bytes is refused`() = served { client ->
        client.send("""{"name":"${"r".repeat(200)}"}""").status.value shouldBe 413
    }

    @Test
    fun `characters are not bytes, and the limit counts bytes`() = served { client ->
        // 30 characters, 90 bytes. A character-counted limit of 64 served this.
        val wide = "五".repeat(30)
        wide.length shouldBe 30
        client.send("""{"name":"$wide"}""").status.value shouldBe 413
    }
}
