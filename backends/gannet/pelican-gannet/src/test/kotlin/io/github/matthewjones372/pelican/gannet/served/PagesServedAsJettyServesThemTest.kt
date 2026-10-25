package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.PageGuard
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.cors
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pages
import io.github.matthewjones372.pelican.gannet.RequiresUring
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.start as jettyStart

/** Spec 0059 on both servers: a classpath directory beside the endpoints, which always win. */
@RequiresUring
class PagesServedAsJettyServesThemTest {

    /** One request to one of the [servers], named so a failing case says which. */
    data class Asked(
        val name: String,
        val path: String,
        val servers: String = "plain",
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
    ) {
        override fun toString() = name
    }

    companion object {
        private const val ORIGIN = "https://bank.example"

        private val about = endpoint {
            get("about")
            text()
        }

        private val opsStream = endpoint {
            get("ops" / "stream")
            text()
        }

        /** Admits a request carrying `Cookie: signed-in=yes`, and sends anyone else to sign in. */
        private val signedIn = PageGuard { path, credentials ->
            if (credentials.cookie("signed-in") == "yes") null else "/login?return=$path"
        }

        private val variants = mapOf(
            "plain" to pages("pages-test"),
            "guarded" to pages("pages-test").guardedBy(signedIn),
            "mounted" to pages("pages-test", at = "/ui"),
        )

        private fun served(routes: List<ServerEndpoint>, variant: String): Api = api(routes, JacksonCodecs) {
            pages = variants.getValue(variant)
            cors = cors(ORIGIN)
        }

        private val uring = variants.keys.associateWith { variant ->
            served(listOf(about handledNow { "the endpoint" }, opsStream handledNow { "a stream" }), variant)
                .start(port = 0)
        }

        private val jetty = variants.keys.associateWith { variant ->
            served(listOf(about jettyHandledNow { "the endpoint" }, opsStream jettyHandledNow { "a stream" }), variant)
                .jettyStart(port = 0)
        }

        private val asking = Asking(
            listOf(
                "Content-Type",
                "Location",
                "Cache-Control",
                "Access-Control-Allow-Origin",
                "Allow",
                "Last-Modified",
                "ETag",
            ),
        )

        /** Each of these, if anything answered it, would read a file outside the pages' directory. */
        private val escapes = listOf(
            "/..%2Fpages-secret.txt",
            "/guide/..%2F..%2Fpages-secret.txt",
            "/guide%2F..%2F..%2Fpages-secret.txt",
            "/guide/%2E%2E/pages-secret.txt",
            "/%2e%2e%2fpages-secret.txt",
            "/%5C..%5Cpages-secret.txt",
            "/..%5Cpages-secret.txt",
            "/guide/%5C..%5C..%5Cpages-secret.txt",
            "/%252E%252E%252Fpages-secret.txt",
            "/.%2Fpages-secret.txt",
            "/ui/..%2Fpages-secret.txt",
        )

        /** Escapes Jetty refuses with its own 400 before any handler sees them; uring answers 404, as Pekko does. */
        private val refusedByJetty = listOf(
            "/%2E%2E/pages-secret.txt",
            "/guide/%2E%2E/%2e%2e/pages-secret.txt",
            "//pages-secret.txt",
            "/../pages-secret.txt",
            "/guide/../../pages-secret.txt",
        )

        @JvmStatic
        fun cases(): List<Asked> = listOf(
            Asked("the index", "/"),
            Asked("a page by its name", "/ops"),
            Asked("a page by its file", "/ops.html"),
            Asked("a script", "/app.js"),
            Asked("a style sheet", "/style.css"),
            Asked("an image", "/logo.svg"),
            Asked("json", "/data.json"),
            Asked("a png", "/pixel.png"),
            Asked("an icon", "/favicon.ico"),
            Asked("a font", "/font.woff2"),
            Asked("plain text", "/notes.txt"),
            Asked("a directory's index", "/guide/"),
            Asked("a directory without its slash", "/guide"),
            Asked("a directory with a query, without its slash", "/guide?tab=1"),
            Asked("an escaped directory name", "/two%20words/"),
            Asked("an escaped directory without its slash", "/two%20words"),
            Asked("a missing page", "/missing"),
            Asked("a missing page with a slash", "/missing/"),
            Asked("an endpoint at a page's path", "/about"),
            Asked("an endpoint below a page's name", "/ops/stream"),
            Asked("a page asked with a query", "/ops?tab=ledger"),
            Asked("a page with an origin", "/app.js", headers = mapOf("Origin" to ORIGIN)),
            Asked("a page with another origin", "/app.js", headers = mapOf("Origin" to "https://elsewhere.example")),
            Asked(
                "a page modified since a date not yet reached",
                "/ops",
                headers = mapOf("If-Modified-Since" to "Fri, 01 Jan 2100 00:00:00 GMT"),
            ),
            Asked("a page matching any tag", "/ops", headers = mapOf("If-None-Match" to "*")),
            Asked("a page matching no tag", "/ops", headers = mapOf("If-None-Match" to "\"other\"")),
            Asked("a page required to match a tag it lacks", "/ops", headers = mapOf("If-Match" to "\"other\"")),
            Asked(
                "a page required unmodified since long ago",
                "/ops",
                headers = mapOf("If-Unmodified-Since" to "Thu, 01 Jan 1970 00:00:00 GMT"),
            ),
            Asked("an escaped letter", "/%6Fps"),
            Asked("an escaped dot", "/ops%2Ehtml"),
            Asked("a page posted to", "/ops", method = "POST"),
            Asked("a missing page posted to", "/missing", method = "POST"),
            Asked("a page asked with HEAD", "/ops", method = "HEAD"),
            Asked(
                "a page's preflight",
                "/app.js",
                method = "OPTIONS",
                headers = mapOf("Origin" to ORIGIN, "Access-Control-Request-Method" to "GET"),
            ),
            Asked("a dot segment inside the pages", "/guide/%2E%2E/ops"),
            Asked("a guarded page to a stranger", "/ops", "guarded"),
            Asked("a guarded page with its query", "/ops?tab=ledger", "guarded"),
            Asked("a guarded page signed in", "/ops", "guarded", headers = mapOf("Cookie" to "signed-in=yes")),
            Asked(
                "a guarded page among other cookies",
                "/ops",
                "guarded",
                headers = mapOf("Cookie" to "theme=dark; signed-in=yes"),
            ),
            Asked("a guarded directory without its slash", "/guide", "guarded"),
            Asked("a guarded missing page", "/missing", "guarded"),
            Asked("an endpoint beside guarded pages", "/about", "guarded"),
            Asked("pages mounted below a path, at it", "/ui"),
            Asked("pages mounted below a path, at its slash", "/ui/"),
            Asked("a mounted page", "/ui/ops"),
            Asked("a mounted directory without its slash", "/ui/guide"),
            Asked("a path that only starts like the mount", "/uiops"),
            Asked("the root, beside mounted pages", "/"),
        ).map { if (it.servers == "plain" && it.path.startsWith("/ui")) it.copy(servers = "mounted") else it } +
            escapes.flatMap { path ->
                listOf(Asked("an escape $path", path), Asked("an escape $path, mounted", path, "mounted"))
            }

        @JvmStatic
        fun refusals(): List<String> = refusedByJetty

        @JvmStatic
        @AfterAll
        fun stop() {
            asking.close()
            uring.values.forEach { it.stop() }
            jetty.values.forEach { it.stop() }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    fun `an escape jetty refuses itself serves nothing on either`(path: String) {
        val onUring = asking.ask(uring.getValue("plain").baseUrl, "GET", path)
        val onJetty = asking.ask(jetty.getValue("plain").baseUrl, "GET", path)
        onUring.status shouldBe 404
        onJetty.status shouldBe 400
        onUring.body shouldNotContain "not a page"
    }

    @Test
    fun `a page asked again with its own validators is not modified, as on jetty`() {
        val first = asking.ask(jetty.getValue("plain").baseUrl, "GET", "/ops")
        listOf("If-None-Match" to "ETag", "If-Modified-Since" to "Last-Modified").forEach { (condition, validator) ->
            val again = mapOf(condition to checkNotNull(first.headers[validator]))
            val onUring = asking.ask(uring.getValue("plain").baseUrl, "GET", "/ops", again)
            val onJetty = asking.ask(jetty.getValue("plain").baseUrl, "GET", "/ops", again)
            withClue("$condition: uring $onUring, jetty $onJetty") { onUring shouldBe onJetty }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `uring serves pages as jetty does`(asked: Asked) {
        val onUring = asking.ask(uring.getValue(asked.servers).baseUrl, asked.method, asked.path, asked.headers)
        val onJetty = asking.ask(jetty.getValue(asked.servers).baseUrl, asked.method, asked.path, asked.headers)
        withClue("${asked.name}: uring $onUring, jetty $onJetty") {
            onUring.body shouldNotContain "not a page"
            onUring shouldBe onJetty
        }
    }
}
