package io.github.matthewjones372.pelican.test.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
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
    fun `an answer read from the request body is refused, naming the endpoint`(@TempDir tmp: Path) {
        val failure = shouldThrow<IllegalArgumentException> {
            stubFile(JacksonCodecs) {
                stub(recordKeeper) { (number, keeper) -> ok(Chip(number, keeper.keeper)) }
            }
        }
        failure.message shouldContain recordKeeper.pathSpec.template
        failure.message shouldContain "body"
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
