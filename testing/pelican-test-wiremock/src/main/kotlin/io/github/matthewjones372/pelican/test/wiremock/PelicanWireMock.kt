package io.github.matthewjones372.pelican.test.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.extension.Parameters
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.http.HttpHeader
import com.github.tomakehurst.wiremock.http.HttpHeaders
import com.github.tomakehurst.wiremock.http.Request
import com.github.tomakehurst.wiremock.http.ResponseDefinition
import com.github.tomakehurst.wiremock.matching.MatchResult
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.matching.ValueMatcher
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.InMemoryClientTransport
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

// File-level rather than in a companion: `const` in a private companion is still a public static field.
private const val RENDERER = "pelican-answer"
private const val STUB = "stub"
private val FRAMING = listOf("Content-Length", "Transfer-Encoding", "Connection")

/**
 * A WireMock server stubbed and verified in endpoint values: the request a stub matches and the
 * answer it gives both come from the endpoint, so neither can drift from the contract.
 *
 * Starts on a random port when constructed. [PelicanWireMockExtension] is the JUnit 5 form.
 */
open class PelicanWireMock(private val codecs: Codecs) : AutoCloseable {

    // A stub's answer is a function, and WireMock's mappings only carry strings, so each mapping
    // carries an id into this table. It lives exactly as long as the server.
    private val answers = ConcurrentHashMap<String, (ClientRequest) -> ClientResponse>()

    /** The server underneath, for what no endpoint describes: a health check, a token endpoint. */
    val wireMock: WireMockServer =
        WireMockServer(wireMockConfig().dynamicPort().extensions(Renderer(answers))).apply { start() }

    /** Where the stubs answer. */
    val baseUrl: String = wireMock.baseUrl()

    /** [endpoint] asked with exactly [input]; say what it answers with on the result. */
    fun <I, E : Any, T : Any> stub(endpoint: Endpoint<I, Outcome<E, T>>, input: I): Stubbing<I, E, T> =
        Stubbing(endpoint, WireMock.requestMatching(Asks(endpoint, codecs) { it == input }))

    /** Every call to [endpoint], each answered from its own decoded input. */
    fun <I, E : Any, T : Any> stub(endpoint: Endpoint<I, Outcome<E, T>>, answer: (I) -> Outcome<E, T>) {
        answering(WireMock.requestMatching(Asks(endpoint, codecs) { true }), endpoint, answer, Duration.ZERO)
    }

    /** Fails unless [endpoint] was asked with exactly [input], [times] times. */
    fun <I> verify(endpoint: Endpoint<I, *>, input: I, times: Int = 1) {
        wireMock.verify(times, RequestPatternBuilder.forCustomMatcher(Asks(endpoint, codecs) { it == input }))
    }

    /** How many calls reached [endpoint], whatever they asked. */
    fun <I> calls(endpoint: Endpoint<I, *>): Int =
        wireMock.countRequestsMatching(anyCallTo(endpoint).build()).count

    private fun <I> anyCallTo(endpoint: Endpoint<I, *>): RequestPatternBuilder =
        RequestPatternBuilder.forCustomMatcher(Asks(endpoint, codecs) { true })

    /** Forgets every stub and every request, keeping the port. */
    fun reset() {
        wireMock.resetAll()
        answers.clear()
    }

    override fun close() = wireMock.stop()

    /** One stubbed call, waiting to be told what it answers with. */
    inner class Stubbing<I, E : Any, T : Any> internal constructor(
        private val endpoint: Endpoint<I, Outcome<E, T>>,
        private val mapping: MappingBuilder,
    ) {
        /** `ok(value)` or a failure the endpoint declares; nothing else compiles. */
        infix fun answers(outcome: Outcome<E, T>) = answers(outcome, after = Duration.ZERO)

        /** The same answer, after keeping the caller waiting. */
        fun answers(outcome: Outcome<E, T>, after: Duration) = answering(mapping, endpoint, { outcome }, after)

        /** The connection itself goes wrong. */
        infix fun fails(fault: Fault) {
            wireMock.stubFor(mapping.willReturn(aResponse().withFault(fault)))
        }

        /** A status the endpoint never declared: the one answer a contract cannot describe. */
        infix fun breaksWith(status: Int) {
            wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withBody("stubbed $status")))
        }
    }

    private fun <I, E : Any, T : Any> answering(
        mapping: MappingBuilder,
        endpoint: Endpoint<I, Outcome<E, T>>,
        answer: (I) -> Outcome<E, T>,
        after: Duration,
    ) {
        // Rendered by the code a server answers with, so the status, content type and body are the
        // ones the contract's own service would send.
        val served = InMemoryClientTransport(
            api(
                listOf(
                    ServerEndpoint(endpoint) { p ->
                        CompletableFuture.completedFuture(answer(endpoint.inputs.extract(p)))
                    },
                ),
                codecs,
            ),
        )
        val id = UUID.randomUUID().toString()
        answers[id] = { request -> served.send(request).toCompletableFuture().get() }
        wireMock.stubFor(
            mapping.willReturn(
                aResponse()
                    .withTransformers(RENDERER)
                    .withTransformerParameters(Parameters.one(STUB, id))
                    .withFixedDelay(after.inWholeMilliseconds.toInt()),
            ),
        )
    }

    /**
     * A request matches when Pelican's own routing and decoding turn it into an input [wanted]
     * accepts. So a request means what it decodes to: query order, percent-encoding, a JSON field
     * left at its default and an undeclared header make no difference, and a declared header does.
     */
    private class Asks<I>(endpoint: Endpoint<I, *>, codecs: Codecs, wanted: (I) -> Boolean) : ValueMatcher<Request> {

        // The handler runs on the thread that sends, so the verdict comes back through this.
        private val verdict = ThreadLocal<Boolean>()

        private val decoder = InMemoryClientTransport(
            api(
                listOf(
                    ServerEndpoint(endpoint) { p ->
                        verdict.set(wanted(endpoint.inputs.extract(p)))
                        CompletableFuture.completedFuture(null)
                    },
                ),
                codecs,
            ),
        )

        override fun match(request: Request): MatchResult {
            verdict.remove()
            decoder.send(request.asPelican())
            return MatchResult.of(verdict.get() == true)
        }
    }

    private class Renderer(
        private val answers: Map<String, (ClientRequest) -> ClientResponse>,
    ) : ResponseDefinitionTransformerV2 {
        override fun getName(): String = RENDERER

        override fun applyGlobally(): Boolean = false

        override fun transform(event: ServeEvent): ResponseDefinition {
            val answer = answers.getValue(event.transformerParameters.getString(STUB))(event.request.asPelican())
            // Declared response headers travel — a 429's Retry-After is part of the answer. Framing
            // headers do not: WireMock frames the body it sends, not the one Pelican rendered.
            val headers = answer.headers
                .filterNot { (name, _) -> FRAMING.any { it.equals(name, ignoreCase = true) } }
                .map { (name, value) -> HttpHeader(name, value) }
            return ResponseDefinitionBuilder.like(event.responseDefinition)
                .withStatus(answer.status)
                .withHeaders(HttpHeaders(headers))
                .withBody(answer.text())
                .build()
        }
    }
}

private fun Request.asPelican(): ClientRequest = ClientRequest(
    Method.valueOf(method.value()),
    "http://stub$url",
    headers.all().flatMap { header -> header.values().map { header.key() to it } },
    bodyAsString.takeUnless { it.isNullOrEmpty() }?.let { ClientRequest.Body.Text(it) } ?: ClientRequest.Body.Empty,
)
