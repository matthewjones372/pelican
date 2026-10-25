package io.github.matthewjones372.pelican.uring.docs

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.uring.handledNow
import io.github.matthewjones372.pelican.uring.start
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The one-argument spellings a service types first: every default in place. */
class DefaultsTest {

    private val motd = endpoint {
        get("motd")
        text()
    }

    private val served = api(listOf(motd handledNow { "hello" }), JacksonCodecs)

    private fun get(baseUrl: String, path: String): HttpResponse<String> =
        HttpClient.newHttpClient().use { client ->
            val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).build()
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }

    @Test
    fun `startWithDocs serves the endpoints and the document under the default paths`() {
        served.startWithDocs(port = 0).use { server ->
            get(server.baseUrl, "/motd").body() shouldBe "hello"
            get(server.baseUrl, "/openapi.json").body() shouldContain "\"/motd\""
        }
    }

    @Test
    fun `and so does a server composed by hand from handlerWithDocs`() {
        served.start(port = 0) { handlerWithDocs() }.use { server ->
            get(server.baseUrl, "/motd").body() shouldBe "hello"
            get(server.baseUrl, "/openapi.json").statusCode() shouldBe 200
        }
    }

    @Test
    fun `the docs alone pass on every endpoint`() {
        served.start(port = 0) { docsHandler() }.use { server ->
            get(server.baseUrl, "/motd").statusCode() shouldBe 404
            get(server.baseUrl, "/openapi.json").statusCode() shouldBe 200
        }
    }
}
