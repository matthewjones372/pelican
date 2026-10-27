package example.backends

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import example.logging.accessLog
import example.logging.refusalLog
import example.telemetry.RecordedSpans
import example.telemetry.recordingTelemetry
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.InMemoryClientTransport
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.Unauthenticated
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.metrics.otel.openTelemetry
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * No credential reaches what Pelican writes: a bearer token, an API key and a session cookie go to every backend on
 * every kind of answer, and none of them comes back in a span, a log line, a response body or a response header.
 */
class RedactionAcrossBackendsTest {

    companion object {
        private val logged = ListAppender<ILoggingEvent>().also { it.start() }

        /** Pelican's own loggers and the example's access log, at the level that writes the most. */
        private val watched = listOf("io.github.matthewjones372", "example.logging.access")
            .map { LoggerFactory.getLogger(it) as LogbackLogger }

        private val levels = watched.associateWith { it.level }

        private val root = (LoggerFactory.getLogger(ROOT_LOGGER_NAME) as LogbackLogger).also { root ->
            root.addAppender(logged)
            watched.forEach { it.level = Level.DEBUG }
        }

        private val recorded: Map<String, RecordedSpans> = runnableBackends.associate { it.name to RecordedSpans() }

        private val running: Map<String, Running> = runnableBackends.associate { backend ->
            val sdk = recordingTelemetry(recorded.getValue(backend.name))
            backend.name to backend.start(
                port = 0,
                outerFilters = listOf(openTelemetry(sdk), accessLog()),
                onRefusal = refusalLog(),
            )
        }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        @JvmStatic
        fun backends(): List<Array<Any>> = runnableBackends.map { arrayOf(it.name, clients.getValue(it.name)) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
            root.detachAppender(logged)
            levels.forEach { (logger, level) -> logger.level = level }
        }

        private const val API_KEY = "apikey-5e1f0c7a9b"
        private const val SESSION = "session-8d2a6e4f1c"
        private const val FORGED = "forged-3b9c7d1e0a"

        /** Every credential a request below carries, including the ones that verify. */
        private val secrets = listOf(TELLER_TOKEN, GUEST_TOKEN, API_KEY, SESSION, FORGED)
    }

    private fun RequestSpec.carrying(token: String): RequestSpec = withHeader("Authorization", "Bearer $token")
        .withHeader("X-Api-Key", API_KEY)
        .withHeader("Cookie", "sid=$SESSION")

    private fun ApiClient.refund(token: String, body: String = """{"amount":5}"""): ResponseSpec =
        transport.send(request(refundTip, Tip(5)).withBody(body).carrying(token))

    /** One request for each way an answer is made: handled, refused by Pelican, unmatched and thrown. */
    private fun answers(client: ApiClient): List<ResponseSpec> = listOf(
        client.refund(TELLER_TOKEN).shouldHaveStatus(200),
        client.refund(GUEST_TOKEN).shouldHaveStatus(403),
        client.refund(FORGED).shouldHaveStatus(401),
        client.refund(TELLER_TOKEN, body = "not json").shouldHaveStatus(400),
        client.transport.send(client.request(greet, In2("ada", false)).carrying(FORGED)).shouldHaveStatus(200),
        client.transport.send(client.request(greet, In2("ada", false)).withPath("/nowhere").carrying(FORGED))
            .shouldHaveStatus(404),
        client.transport.send(client.request(echo, In2(null, Note(BOOM))).carrying(FORGED)).shouldHaveStatus(500),
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `no credential comes back in a body, a header, a span or a log line`(name: String, client: ApiClient) {
        val responses = answers(client)
        val spans = recorded.getValue(name).all()
        // What is searched is really there: the spans, the access and refusal logs, and the 500's stack trace.
        spans.map { it.name } shouldContain "POST /echo"
        val lines = logged.list.map { it.formattedMessage }
        lines.any { "refundTip -> 200" in it } shouldBe true
        lines.any { "refused DECODE on /tips/refunds" in it } shouldBe true
        logged.list.any { it.throwableProxy != null } shouldBe true

        val written = responses.flatMap { listOf(it.body) + it.headers.map { (k, v) -> "$k: $v" } } +
            spans.flatMap(::textOf) +
            logged.list.flatMap(::textOf)

        withClue(name) { leaks(written).shouldBeEmpty() }
    }

    @Test
    fun `a token an Authenticator puts in its refusal is found, so the check above can fail`() {
        val bearer = bearerAuth()
        val caller = authenticated(bearer) { it.subject }
        val me = endpoint {
            get("me")
            authenticatedBy(caller)
            text()
        }
        val careless = Authenticator { throw Unauthenticated("${it.bearerToken()} is not a token we issued") }
        val api = api(listOf(ServerEndpoint(me) { CompletableFuture.completedStage("me" as Any?) }), JacksonCodecs) {
            authenticate(bearer, careless)
        }

        val refused = InMemoryClientTransport(api)
            .send(ClientRequest(Method.GET, "http://test/me", listOf("Authorization" to "Bearer $FORGED")))
            .toCompletableFuture().join()

        refused.status shouldBe 401
        leaks(listOf(refused.text())) shouldBe listOf(FORGED)
    }

    private fun leaks(written: List<String>): List<String> = secrets.filter { secret -> written.any { secret in it } }

    private fun textOf(span: SpanData): List<String> =
        listOf(span.name, span.status.description) +
            span.attributes.asMap().values.map { it.toString() } +
            span.events.flatMap { event -> listOf(event.name) + event.attributes.asMap().values.map { it.toString() } }

    private fun textOf(event: ILoggingEvent): List<String> =
        listOfNotNull(event.formattedMessage, event.throwableProxy?.let(ThrowableProxyUtil::asString))
}
