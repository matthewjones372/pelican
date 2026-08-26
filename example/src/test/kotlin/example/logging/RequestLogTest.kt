package example.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.start
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * The three claims the logging example makes, over a socket.
 *
 * The log lines are read back through a logback appender rather than trusted:
 * an access log that quietly stopped recording refusals, or started recording
 * the request's path where it promised the template, would still look right in
 * the source.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestLogTest {

    private lateinit var server: PelicanServer

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val recorded = ListAppender<ILoggingEvent>()

    @BeforeAll
    fun boot() {
        val logger = LoggerFactory.getLogger("example.logging.access") as LogbackLogger
        logger.level = Level.DEBUG
        recorded.start()
        logger.addAppender(recorded)

        server = loggedApi().start(port = 0, systemName = "widgets-logging-test")
    }

    @AfterAll
    fun shutdown() = server.stop()

    @BeforeEach
    fun forget() = recorded.list.clear()

    private fun get(path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("${server.baseUrl}$path")).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun lines() = recorded.list.map { "${it.level} ${it.formattedMessage}" }

    // ------------------------------------- the filter reads the description

    @Test
    fun `the health check is served without a token, because it declares none`() {
        get("/health").statusCode() shouldBe 200
    }

    @Test
    fun `an endpoint that declares a scheme is refused without one`() {
        get("/widgets/1").statusCode() shouldBe 401
    }

    @Test
    fun `and served with one`() {
        val answered = get("/widgets/1", "Authorization" to "Bearer let-me-in")

        answered.statusCode() shouldBe 200
        answered.body() shouldContain "an anvil"
    }

    // ------------------------------------------------------- what is logged

    @Test
    fun `a served request is logged at debug, by template rather than by path`() {
        get("/widgets/1", "Authorization" to "Bearer let-me-in")

        lines() shouldContain "DEBUG GET /widgets/{widgetId} getWidget -> 200"
        withClue("the id belongs in the request, not in a log line one series per widget wide") {
            lines().none { it.contains("/widgets/1 ") } shouldBe true
        }
    }

    @Test
    fun `a declared failure is logged at info`() {
        get("/widgets/99", "Authorization" to "Bearer let-me-in")

        lines() shouldContain "INFO GET /widgets/{widgetId} getWidget -> 404"
    }

    /**
     * The line `after` would miss: the token filter throws where it stands, so
     * a log built on the handler's return value never sees a 401 at all.
     */
    @Test
    fun `a request a filter refused is logged, which is why afterStatus exists`() {
        get("/widgets/1")

        lines() shouldContain "INFO GET /widgets/{widgetId} getWidget -> 401"
    }

    @Test
    fun `an undeclared throw is logged at error, with the reference the caller was given`() {
        val answered = get("/widgets/-1", "Authorization" to "Bearer let-me-in")

        answered.statusCode() shouldBe 500
        val reference = Regex("[0-9a-f]{12}").find(answered.body())?.value
        withClue("the caller is given a reference to quote: ${answered.body()}") {
            reference shouldBe reference
        }
        lines().any { it.startsWith("ERROR GET /widgets/{widgetId}") } shouldBe true
        lines().any { it.startsWith("ERROR unhandled on /widgets/{widgetId}") } shouldBe true
    }

    /**
     * A refusal answered before the chain was entered, so no filter ran and the
     * access log above structurally cannot have seen it. `onRefusal` is the
     * only thing that does.
     *
     * The template is the route's, not `_unmatched`: a path parameter is
     * decoded inside `RouteIndex.match`, which throws to a caller that has no
     * endpoint in hand, so the route is looked up again to name the refusal.
     * A query, header or cookie is decoded after the route is in hand and
     * never had the problem.
     */
    @Test
    fun `a parameter that will not decode reaches the refusal observer alone`() {
        get("/widgets/not-a-number", "Authorization" to "Bearer let-me-in").statusCode() shouldBe 400

        lines() shouldContain "INFO refused DECODE on /widgets/{widgetId} -> 400"
        withClue("nothing reached the filter chain, so the access log has nothing to say") {
            lines().none { it.contains("getWidget -> 400") } shouldBe true
        }
    }
}
