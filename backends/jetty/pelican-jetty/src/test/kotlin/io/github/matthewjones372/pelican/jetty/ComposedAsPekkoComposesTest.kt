package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.path
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.responseHeader
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.pekko.http.javadsl.server.Directives
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.ByteBuffer
import io.github.matthewjones372.pelican.pekko.handledNow as pekkoHandledNow
import io.github.matthewjones372.pelican.pekko.handledWith as pekkoHandledWith
import io.github.matthewjones372.pelican.pekko.start as pekkoStart
import io.github.matthewjones372.pelican.pekko.toRoute as pekkoToRoute

/** An API mounted beside a hand-written handler, before and after it, on both servers. */
class ComposedAsPekkoComposesTest {

    /** A handler the service wrote itself, knowing nothing of Pelican. */
    private class Health : Handler.Abstract.NonBlocking() {
        override fun handle(request: Request, response: Response, callback: Callback): Boolean {
            if (Request.getPathInContext(request) != "/health") return false
            response.headers.put(HttpHeader.CONTENT_TYPE, "text/plain; charset=UTF-8")
            response.write(true, ByteBuffer.wrap("ok".toByteArray()), callback)
            return true
        }
    }

    companion object {
        private val who = pathParam<String>("who")
        private val requestId = responseHeader<String>("X-Request-Id", "Correlates this answer")

        private val greet = endpoint(who) {
            get("hello" / who)
            text()
        }

        private val outbox = endpoint {
            post("outbox")
            empty(status = 202)
        }

        private val peek = endpoint {
            route(Method.HEAD, path("peek"))
            emits(requestId)
            empty(200)
        }

        private val withPeek = api(
            listOf(
                greet handledNow { name -> "Hello, $name!" },
                outbox handledWith { },
                peek handledWith { setHeader(requestId, "r-1") },
            ),
            JacksonCodecs,
        )
        private val pekkoWithPeek = api(
            listOf(
                greet pekkoHandledNow { name -> "Hello, $name!" },
                outbox pekkoHandledWith { },
                peek pekkoHandledWith { setHeader(requestId, "r-1") },
            ),
            JacksonCodecs,
        )
        private val plain: List<ServerEndpoint> = listOf(greet handledNow { name -> "Hello, $name!" })
        private val pekkoPlain: List<ServerEndpoint> = listOf(greet pekkoHandledNow { name -> "Hello, $name!" })

        private val handWritten = Directives.path("health") { Directives.get { Directives.complete("ok") } }

        private val jettyBefore = withPeek.start(port = 0) {
            Handler.Sequence(Health().onlyFor(Method.GET), toHandler())
        }
        private val pekkoBefore = pekkoWithPeek.pekkoStart(port = 0) { system ->
            Directives.concat(handWritten, pekkoToRoute(system))
        }
        private val jettyAfter = api(plain, JacksonCodecs).start(port = 0) {
            Handler.Sequence(toHandler(), Health().onlyFor(Method.GET))
        }
        private val pekkoAfter = api(pekkoPlain, JacksonCodecs).pekkoStart(port = 0) { system ->
            Directives.concat(pekkoToRoute(system), handWritten)
        }

        private val asking = Asking(listOf("Content-Type", "Allow", "X-Request-Id"))

        @JvmStatic
        @AfterAll
        fun stopAll() {
            asking.close()
            listOf(jettyBefore, jettyAfter).forEach { it.stop() }
            listOf(pekkoBefore, pekkoAfter).forEach { it.stop() }
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "GET, /hello/ada",
        "POST, /outbox",
        "HEAD, /peek",
        "HEAD, /hello/ada",
        "PUT, /hello/ada",
        "POST, /hello/ada",
        "GET, /nobody/described/this",
        "DELETE, /nobody/described/this",
        "GET, /health",
        "PUT, /health",
    )
    fun `mounted before the api, jetty answers as pekko does`(method: String, path: String) {
        withClue("$method $path") {
            asking.ask(jettyBefore.baseUrl, method, path) shouldBe asking.ask(pekkoBefore.baseUrl, method, path)
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "GET, /hello/ada",
        "HEAD, /hello/ada",
        "GET, /health",
        "PUT, /health",
        "POST, /health",
        "GET, /nobody/described/this",
    )
    fun `mounted after the api, jetty answers as pekko does`(method: String, path: String) {
        withClue("$method $path") {
            asking.ask(jettyAfter.baseUrl, method, path) shouldBe asking.ask(pekkoAfter.baseUrl, method, path)
        }
    }

    @Test
    fun `the api passes on what it does not describe to a handler after it`() {
        asking.ask(jettyAfter.baseUrl, "GET", "/health").body shouldBe "ok"
    }

    @Test
    fun `a refused verb names the methods of every handler that passed on it`() {
        val answer = asking.ask(jettyBefore.baseUrl, "PUT", "/health")

        answer.status shouldBe 405
        answer.headers["Allow"] shouldBe "GET, POST, HEAD"
    }

    @Test
    fun `a declared head answers with its headers and no body`() {
        val answer = asking.ask(jettyBefore.baseUrl, "HEAD", "/peek")

        answer.status shouldBe 200
        answer.headers["X-Request-Id"] shouldBe "r-1"
        answer.body shouldBe ""
    }

    @Test
    fun `a server built by hand answers what nobody took once it sets the default handler`() {
        val server = Server()
        server.addConnector(ServerConnector(server))
        server.handler = api(plain, JacksonCodecs).toHandler()
        server.defaultHandler = Unanswered()
        server.start()
        try {
            val port = (server.connectors.single() as ServerConnector).localPort
            asking.ask("http://127.0.0.1:$port", "PUT", "/hello/ada").headers["Allow"] shouldBe "GET"
        } finally {
            server.stop()
        }
    }
}
