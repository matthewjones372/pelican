package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/** `responseHeaders { }` on every answer the in-memory transport gives, told what each answer is. */
class ResponseHeadersPolicyTest {

    private val cacheControl = responseHeader<String>("Cache-Control")

    private val greet = endpoint {
        get("hello")
        operationId = "greet"
        emits(cacheControl)
        text()
    }

    private val seen = mutableListOf<String>()

    private fun served(handle: Params.() -> Unit = {}, rule: AnswerHeaders.(Answer) -> Unit): InMemoryClientTransport =
        InMemoryClientTransport(
            api(
                listOf(
                    ServerEndpoint(greet) { p ->
                        p.handle()
                        CompletableFuture.completedStage("hi" as Any?)
                    },
                ),
            ) { responseHeaders(rule) },
        )

    private fun InMemoryClientTransport.get(path: String): ClientResponse =
        send(ClientRequest(Method.GET, "http://test$path", emptyList())).toCompletableFuture().join()

    @Test
    fun `the rule is told the path, the endpoint and the status`() {
        val transport = served { answer ->
            seen += "${answer.path} ${answer.endpoint?.operationName} ${answer.status} ${answer.isDocs}"
            add("X-Content-Type-Options", "nosniff")
        }

        transport.get("/hello").header("X-Content-Type-Options") shouldBe "nosniff"
        transport.get("/nope").header("X-Content-Type-Options") shouldBe "nosniff"

        seen shouldContainExactly listOf("/hello greet 200 false", "/nope null 404 false")
    }

    @Test
    fun `a header the handler set wins over the policy's`() {
        val transport = served(handle = { setHeader(cacheControl, "max-age=60") }) { add("Cache-Control", "no-store") }

        transport.get("/hello").headers.filter { it.first == "Cache-Control" }.map { it.second } shouldContainExactly
            listOf("max-age=60")
    }

    @Test
    fun `a name added twice is sent twice`() {
        val transport = served {
            add("Link", "</a>; rel=preload")
            add("Link", "</b>; rel=preload")
        }

        transport.get("/hello").headers.filter { it.first == "Link" }.map { it.second } shouldContainExactly
            listOf("</a>; rel=preload", "</b>; rel=preload")
    }

    @Test
    fun `a header the server writes itself cannot be added`() {
        shouldThrow<IllegalArgumentException> { AnswerHeaders().add("Content-Type", "text/html") }
            .message shouldContain "Content-Type"
    }
}
