package io.github.matthewjones372.pelican.test.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeError
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * A stub that answers from its input exports as one mapping, so the claim is that *several* inputs
 * are answered by the single file WireMock loads. Every assertion stands WireMock on the written
 * files and calls through a typed client; the one thing a template cannot be asserted from is its
 * own JSON.
 */
class TemplatedStubFileTest {

    private fun chipFor(petId: Long) = Chip("98100000000000$petId", keeper = "Petshop")

    private fun <T> serving(mappings: Path, use: (ApiClient) -> T): T {
        val server = WireMockServer(
            wireMockConfig().dynamicPort().usingFilesUnderDirectory(mappings.parent.toString()),
        )
        server.start()
        return try {
            use(apiClient(server.baseUrl(), JacksonCodecs))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `one templated mapping answers every pet with its own chip`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubFile(JacksonCodecs) {
            stub(lookupChip) { (petId, _, _) -> ok(chipFor(petId)) }
        }.writeTo(mappings)

        serving(mappings) { client ->
            client.outcome(lookupChip, In3(7L, "eu", 1)).shouldBeOk() shouldBe chipFor(7L)
            client.outcome(lookupChip, In3(11L, "eu", 1)).shouldBeOk() shouldBe chipFor(11L)
            client.outcome(lookupChip, In3(2026L, "us", 4)).shouldBeOk() shouldBe chipFor(2026L)
        }
    }

    @Test
    fun `a fixed input beats the template for the one pet it names`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubFile(JacksonCodecs) {
            stub(lookupChip) { (petId, _, _) -> ok(chipFor(petId)) }
            stub(lookupChip, In3(3L, "eu", 1)) answers noSuchChip(Problem("never chipped"))
        }.writeTo(mappings)

        serving(mappings) { client ->
            client.outcome(lookupChip, In3(3L, "eu", 1)).shouldBeError() shouldBe Problem("never chipped")
            client.outcome(lookupChip, In3(7L, "eu", 1)).shouldBeOk() shouldBe chipFor(7L)
        }
    }

    @Test
    fun `an answer that ignores its input is still one mapping`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubFile(JacksonCodecs) {
            stub(lookupChip) { _ -> ok(Chip("981", keeper = "Petshop")) }
        }.writeTo(mappings)

        serving(mappings) { client ->
            client.outcome(lookupChip, In3(7L, "eu", 1)).shouldBeOk() shouldBe Chip("981", "Petshop")
            client.outcome(lookupChip, In3(11L, "eu", 1)).shouldBeOk() shouldBe Chip("981", "Petshop")
        }
    }

    @Test
    fun `an answer that reads a query parameter is refused, naming the endpoint`(@TempDir tmp: Path) {
        val failure = shouldThrow<IllegalArgumentException> {
            stubFile(JacksonCodecs) {
                stub(lookupChip) { (petId, region, _) -> ok(Chip("$region-$petId", keeper = "Petshop")) }
            }
        }
        failure.message shouldContain lookupChip.pathSpec.template
        failure.message shouldContain "path parameter"
    }

    @Test
    fun `an endpoint with a body and no example is refused, saying to give one`(@TempDir tmp: Path) {
        val failure = shouldThrow<IllegalArgumentException> {
            stubFile(JacksonCodecs) {
                stub(recordKeeper) { (number, keeper) -> ok(Chip(number, keeper.keeper)) }
            }
        }
        failure.message shouldContain recordKeeper.pathSpec.template
        failure.message shouldContain "example = input"
    }

    @Test
    fun `an answer copied from the body is one mapping, filled from each request's own body`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubFile(JacksonCodecs) {
            stub(recordKeeper, example = In2("981000000000001", NewKeeper("Ada"))) { (number, keeper) ->
                ok(Chip(number, keeper.keeper))
            }
        }.writeTo(mappings)

        serving(mappings) { client ->
            client.outcome(recordKeeper, In2("981000000000007", NewKeeper("Grace"))).shouldBeOk() shouldBe
                Chip("981000000000007", keeper = "Grace")
            client.outcome(recordKeeper, In2("42", NewKeeper("Linus"))).shouldBeOk() shouldBe
                Chip("42", keeper = "Linus")
        }
        mappings.resolve("chips.json").readText() shouldContain "{{jsonPath request.body '\$.keeper'}}"
    }

    @Test
    fun `an answer computed from a body field is refused, naming that field`() {
        val failure = shouldThrow<IllegalArgumentException> {
            stubFile(JacksonCodecs) {
                stub(recordKeeper, example = In2("981", NewKeeper("Ada"))) { (number, keeper) ->
                    ok(Chip(number, keeper.keeper.uppercase()))
                }
            }
        }
        failure.message shouldContain "computed from the body field(s) $.keeper"
    }

    @Test
    fun `fields the endpoint will not take varied keep the example's value, and the rest are templated`(
        @TempDir tmp: Path,
    ) {
        val mappings = tmp.resolve("mappings")
        stubFile(JacksonCodecs) {
            stub(rescan, example = In2("981", Rescan(Scan.Tattoo, attempts = 2, note = "smudged"))) { (_, asked) ->
                if (asked.attempts > 3) tooManyAttempts(Problem("Three is enough")) else ok(asked)
            }
        }.writeTo(mappings)

        serving(mappings) { client ->
            // The enum's sentinel is refused by the codec and the count's by the handler, so both
            // answer as the example said; the note is copied from each request.
            client.outcome(rescan, In2("7", Rescan(Scan.Microchip, attempts = 1, note = "clear"))).shouldBeOk() shouldBe
                Rescan(Scan.Tattoo, attempts = 2, note = "clear")
        }
    }

    @Test
    fun `an answer that ignores the body exports without any body template`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubFile(JacksonCodecs) {
            stub(recordKeeper, example = In2("981", NewKeeper("Ada"))) { (number, _) -> ok(Chip(number, "Petshop")) }
        }.writeTo(mappings)

        serving(mappings) { client ->
            client.outcome(recordKeeper, In2("7", NewKeeper("Grace"))).shouldBeOk() shouldBe Chip("7", "Petshop")
        }
        mappings.resolve("chips.json").readText() shouldNotContain "jsonPath"
    }

    @Test
    fun `a templated mapping is golden like a fixed one`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        fun stubs(prefix: String) = stubFile(JacksonCodecs) {
            stub(lookupChip) { (petId, _, _) -> ok(Chip("$prefix$petId", keeper = "Petshop")) }
        }

        stubs("98100000000000").writeTo(mappings)
        val first = mappings.resolve("chips.json").readText()
        stubs("98100000000000").writeTo(mappings)
        mappings.resolve("chips.json").readText() shouldBe first

        val failure = shouldThrow<AssertionError> { stubs("00000000000000").writeTo(mappings) }
        failure.message shouldContain "chips.json"
    }
}
