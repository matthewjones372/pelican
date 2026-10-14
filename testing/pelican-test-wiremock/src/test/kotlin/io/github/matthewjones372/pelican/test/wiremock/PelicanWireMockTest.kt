package io.github.matthewjones372.pelican.test.wiremock

import com.github.tomakehurst.wiremock.client.VerificationException
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.ok
import com.github.tomakehurst.wiremock.http.Fault
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeError
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

class PelicanWireMockTest {

    @JvmField
    @RegisterExtension
    val registry = PelicanWireMockExtension(JacksonCodecs)

    private val client by lazy { apiClient(registry.baseUrl, JacksonCodecs) }

    private val chip = Chip("981000000000001", keeper = "Petshop")

    private val http = HttpClient.newHttpClient()

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString())

    private fun fetch(target: String) = send(HttpRequest.newBuilder(URI.create(registry.baseUrl + target)))

    @Test
    fun `a stub answers the declared success for exactly its input`() {
        registry.stub(lookupChip, In3(1L, "eu", 2)) answers ok(chip)

        client.outcome(lookupChip, In3(1L, "eu", 2)).shouldBeOk() shouldBe chip
        client.response(lookupChip, In3(1L, "eu", 3)).status shouldBe 404
    }

    @Test
    fun `a stub answers a declared failure with the status the endpoint declares`() {
        registry.stub(lookupChip, In3(1L, "eu", 1)) answers noSuchChip(Problem("never chipped"))

        client.outcome(lookupChip, In3(1L, "eu", 1)).shouldBeError() shouldBe Problem("never chipped")
    }

    @Test
    fun `a client that is not Pelican's hits the stub with its query in another order`() {
        registry.stub(lookupChip, In3(1L, "eu west", 2)) answers ok(chip)

        val answer = fetch("/chips/1?page=2&region=eu+west")

        answer.statusCode() shouldBe 200
        answer.headers().firstValue("Content-Type").get() shouldContain "application/json"
        answer.body() shouldContain "981000000000001"
    }

    @Test
    fun `a body matches as JSON, whatever its spacing and field order`() {
        registry.stub(recordKeeper, In2(chip.number, NewKeeper("Ada"))) answers ok(chip.copy(keeper = "Ada"))

        val answer = send(
            HttpRequest.newBuilder(URI.create("${registry.baseUrl}/chips/${chip.number}/keeper"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{ "keeper" : "Ada" }""")),
        )

        answer.statusCode() shouldBe 200
        registry.verify(recordKeeper, In2(chip.number, NewKeeper("Ada")))
    }

    @Test
    fun `a handler stub answers every call from its decoded input`() {
        registry.stub(recordKeeper) { (number, keeper) -> ok(Chip(number, keeper.keeper)) }

        client.outcome(recordKeeper, In2("1", NewKeeper("Ada"))).shouldBeOk() shouldBe Chip("1", "Ada")
        client.outcome(recordKeeper, In2("2", NewKeeper("Bea"))).shouldBeOk() shouldBe Chip("2", "Bea")
        registry.calls(recordKeeper) shouldBe 2
    }

    @Test
    fun `a fault breaks the connection itself`() {
        registry.stub(lookupChip, In3(1L, "eu", 1)) fails Fault.CONNECTION_RESET_BY_PEER

        shouldThrow<IOException> { fetch("/chips/1?region=eu&page=1") }
    }

    @Test
    fun `breaksWith answers a status the endpoint never declared`() {
        registry.stub(lookupChip, In3(1L, "eu", 1)) breaksWith 503

        fetch("/chips/1?region=eu&page=1").statusCode() shouldBe 503
    }

    @Test
    fun `an answer can keep the caller waiting`() {
        registry.stub(lookupChip, In3(1L, "eu", 1)).answers(ok(chip), after = 300.milliseconds)

        measureTime { fetch("/chips/1?region=eu&page=1") } shouldBeGreaterThanOrEqualTo 300.milliseconds
    }

    @Test
    fun `verify fails for a call that was never made`() {
        shouldThrow<VerificationException> { registry.verify(recordKeeper, In2("1", NewKeeper("Ada"))) }
        registry.calls(lookupChip) shouldBe 0
    }

    @Test
    fun `the server underneath takes what no endpoint describes`() {
        registry.wireMock.stubFor(get("/health").willReturn(ok("up")))

        fetch("/health").body() shouldBe "up"
    }
}
