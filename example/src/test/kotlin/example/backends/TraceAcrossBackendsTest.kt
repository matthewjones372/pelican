package example.backends

import example.telemetry.RecordedSpans
import example.telemetry.recordingTelemetry
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.metrics.otel.openTelemetry
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** `openTelemetry(sdk)` with no getter continues an inbound trace on every server. */
class TraceAcrossBackendsTest {

    companion object {
        private val recorded: Map<String, RecordedSpans> =
            allBackends.associate { it.name to RecordedSpans() }

        private val running: Map<String, Running> = allBackends.associate { backend ->
            val sdk = recordingTelemetry(recorded.getValue(backend.name))
            backend.name to backend.start(port = 0, outerFilters = listOf(openTelemetry(sdk)))
        }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        @JvmStatic
        fun backends(): List<Array<Any>> =
            allBackends.map { arrayOf(it.name, clients.getValue(it.name)) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
        }

        private const val TRACE = "0af7651916cd43dd8448eb211c80319c"
        private const val CALLER = "b7ad6b7169203331"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a traceparent with no getter configured makes the server span its child`(name: String, client: ApiClient) {
        val req = client.request(greet, In2("ada", false)).withHeader("traceparent", "00-$TRACE-$CALLER-01")
        client.transport.send(req).shouldHaveStatus(200)

        val server = recorded.getValue(name).all().single { it.name == "GET /hello/{name}" }
        withClue(name) {
            server.traceId shouldBe TRACE
            server.parentSpanId shouldBe CALLER
        }
    }
}
