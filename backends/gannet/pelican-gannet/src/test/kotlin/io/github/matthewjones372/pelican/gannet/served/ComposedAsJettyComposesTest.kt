package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.path
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.responseHeader
import io.github.matthewjones372.pelican.gannet.HttpExchange
import io.github.matthewjones372.pelican.gannet.RequiresUring
import io.github.matthewjones372.pelican.gannet.UringHandler
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.handledWith
import io.github.matthewjones372.pelican.gannet.onlyFor
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.gannet.then
import io.github.matthewjones372.pelican.gannet.toHandler
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.ByteBuffer
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.handledWith as jettyHandledWith
import io.github.matthewjones372.pelican.jetty.onlyFor as jettyOnlyFor
import io.github.matthewjones372.pelican.jetty.start as jettyStart
import io.github.matthewjones372.pelican.jetty.toHandler as jettyToHandler

/** An API mounted beside a hand-written handler, before and after it, on both servers. */
@RequiresUring
class ComposedAsJettyComposesTest {

    /** A handler the service wrote itself, knowing nothing of Pelican. */
    private class Health : UringHandler {
        override fun handle(exchange: HttpExchange): Boolean {
            if (exchange.request.path != "/health") return false
            exchange.respond(200, listOf("Content-Type" to "text/plain; charset=UTF-8"), "ok".toByteArray())
            return true
        }
    }

    /** The same, on Jetty. */
    private class JettyHealth : Handler.Abstract.NonBlocking() {
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
        private val jettyWithPeek = api(
            listOf(
                greet jettyHandledNow { name -> "Hello, $name!" },
                outbox jettyHandledWith { },
                peek jettyHandledWith { setHeader(requestId, "r-1") },
            ),
            JacksonCodecs,
        )
        private val plain: List<ServerEndpoint> = listOf(greet handledNow { name -> "Hello, $name!" })
        private val jettyPlain: List<ServerEndpoint> = listOf(greet jettyHandledNow { name -> "Hello, $name!" })

        private val uringBefore = withPeek.start(port = 0) {
            Health().onlyFor(Method.GET) then toHandler()
        }
        private val jettyBefore = jettyWithPeek.jettyStart(port = 0) {
            Handler.Sequence(JettyHealth().jettyOnlyFor(Method.GET), jettyToHandler())
        }
        private val uringAfter = api(plain, JacksonCodecs).start(port = 0) {
            toHandler() then Health().onlyFor(Method.GET)
        }
        private val jettyAfter = api(jettyPlain, JacksonCodecs).jettyStart(port = 0) {
            Handler.Sequence(jettyToHandler(), JettyHealth().jettyOnlyFor(Method.GET))
        }

        private val asking = Asking(listOf("Content-Type", "Allow", "X-Request-Id"))

        @JvmStatic
        @AfterAll
        fun stopAll() {
            asking.close()
            listOf(uringBefore, uringAfter).forEach { it.stop() }
            listOf(jettyBefore, jettyAfter).forEach { it.stop() }
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
    fun `mounted before the api, uring answers as jetty does`(method: String, path: String) {
        withClue("$method $path") {
            asking.ask(uringBefore.baseUrl, method, path) shouldBe asking.ask(jettyBefore.baseUrl, method, path)
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
    fun `mounted after the api, uring answers as jetty does`(method: String, path: String) {
        withClue("$method $path") {
            asking.ask(uringAfter.baseUrl, method, path) shouldBe asking.ask(jettyAfter.baseUrl, method, path)
        }
    }

    @Test
    fun `the api passes on what it does not describe to a handler after it`() {
        asking.ask(uringAfter.baseUrl, "GET", "/health").body shouldBe "ok"
    }

    @Test
    fun `a refused verb names the methods of every handler that passed on it`() {
        val answer = asking.ask(uringBefore.baseUrl, "PUT", "/health")

        answer.status shouldBe 405
        answer.headers["Allow"] shouldBe "GET, POST, HEAD"
    }

    @Test
    fun `a declared head answers with its headers and no body`() {
        val answer = asking.ask(uringBefore.baseUrl, "HEAD", "/peek")

        answer.status shouldBe 200
        answer.headers["X-Request-Id"] shouldBe "r-1"
        answer.body shouldBe ""
    }
}
