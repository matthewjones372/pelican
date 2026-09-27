package io.github.matthewjones372.pelican.pekko

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.cors
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pages
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.apache.pekko.actor.testkit.typed.annotations.JUnit5TestKit
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.javadsl.JUnit5TestKitBuilder
import org.apache.pekko.actor.testkit.typed.javadsl.TestKitJUnit5Extension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Spec 0059: a classpath directory served beside the endpoints, which always win. */
@ExtendWith(TestKitJUnit5Extension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PagesServedTest {

    @JUnit5TestKit
    @JvmField
    val testKit: ActorTestKit = JUnit5TestKitBuilder()
        .withName("pages-served-test")
        .withCustomConfig(ConfigFactory.empty())
        .build()

    private val about = endpoint {
        get("about")
        text()
    }

    private val opsStream = endpoint {
        get("ops" / "stream")
        text()
    }

    private fun served(block: (String) -> Unit) {
        val server = api(
            endpoints = listOf(about handledNow { "the endpoint" }, opsStream handledNow { "a stream" }),
            codecs = JacksonCodecs,
        ) {
            pages = pages("pages-test")
            cors = cors("https://bank.example")
        }.start(testKit.system(), port = 0)
        try {
            block(server.baseUrl)
        } finally {
            server.stop()
        }
    }

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private fun get(url: String, vararg headers: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).apply { if (headers.isNotEmpty()) headers(*headers) }.GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun HttpResponse<String>.type(): String = headers().firstValue("Content-Type").orElse("")

    @Test
    fun `the directory's pages, scripts and styles answer at their paths with their types`() = served { base ->
        get("$base/").let { home ->
            home.statusCode() shouldBe 200
            home.body() shouldContain "<title>Home</title>"
            home.type() shouldStartWith "text/html"
        }
        get("$base/ops").body() shouldContain "<title>Ops</title>"
        get("$base/app.js").type() shouldContain "javascript"
        get("$base/style.css").type() shouldStartWith "text/css"
        get("$base/logo.svg").type() shouldStartWith "image/svg+xml"
        get("$base/guide/").body() shouldContain "<title>Guide</title>"
    }

    @Test
    fun `an endpoint at a page's path answers as the endpoint, and one below a page's name still answers`() =
        served { base ->
            get("$base/about").body() shouldBe "the endpoint"
            get("$base/ops/stream").body() shouldBe "a stream"
        }

    @Test
    fun `a directory without its slash is sent to it`() = served { base ->
        get("$base/guide").let { sent ->
            sent.statusCode() shouldBe 301
            sent.headers().firstValue("Location").get() shouldBe "/guide/"
        }
    }

    @Test
    fun `a missing page is a 404, and no path leaves the directory`() = served { base ->
        get("$base/missing").statusCode() shouldBe 404
        get("$base/..%2Fpages-secret.txt").statusCode() shouldBe 404
        get("$base/guide/..%2F..%2Fpages-secret.txt").statusCode() shouldBe 404
    }

    @Test
    fun `a page carries the API's CORS headers for an allowed origin`() = served { base ->
        get("$base/app.js", "Origin", "https://bank.example")
            .headers().firstValue("Access-Control-Allow-Origin").orElse(null) shouldBe "https://bank.example"
    }
}
