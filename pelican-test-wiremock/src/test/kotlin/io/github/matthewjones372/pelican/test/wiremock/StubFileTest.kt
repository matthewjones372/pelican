package io.github.matthewjones372.pelican.test.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.parseJson
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
 * The claim is agreement between the written file and the contract, so the assertions go through
 * WireMock reading what was written and a typed client calling it. Asserting on the JSON would
 * only restate what the writer wrote.
 */
class StubFileTest {

    private val chip = Chip("981000000000007", keeper = "Petshop")

    private fun stubs() = stubFile(JacksonCodecs) {
        stub(lookupChip, In3(3L, "eu", 1)) answers noSuchChip(Problem("never chipped"))
        stub(lookupChip, In3(7L, "eu", 1)) answers ok(chip)
    }

    /** WireMock standing on the written files, exactly as a demo or a compose file would. */
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
    fun `a declared failure is written with the body its contract declares`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubs().writeTo(mappings)

        serving(mappings) { client ->
            val answer = client.response(lookupChip, In3(3L, "eu", 1))
            answer.status shouldBe 404
            answer.contentType shouldBe "application/json"
            parseJson(answer.body) shouldBe parseJson("""{"message":"never chipped"}""")
            client.outcome(lookupChip, In3(3L, "eu", 1)).shouldBeError() shouldBe Problem("never chipped")
        }
    }

    @Test
    fun `a success is written so the typed client decodes it`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubs().writeTo(mappings)

        serving(mappings) { client ->
            client.outcome(lookupChip, In3(7L, "eu", 1)).shouldBeOk() shouldBe chip
        }
    }

    @Test
    fun `a stub is matched by its own input and not by another`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubs().writeTo(mappings)

        serving(mappings) { client ->
            client.response(lookupChip, In3(7L, "eu", 1)).status shouldBe 200
            client.response(lookupChip, In3(3L, "eu", 1)).status shouldBe 404
            // Declared by no stub, so WireMock has nothing to serve it.
            client.response(lookupChip, In3(9L, "eu", 1)).status shouldBe 404
        }
    }

    @Test
    fun `stubs sharing a path root land in one file, named after it`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubFile(JacksonCodecs) {
            stub(lookupChip, In3(3L, "eu", 1)) answers noSuchChip(Problem("never chipped"))
            stub(recordKeeper, In2("981", NewKeeper("Ada"))) answers ok(Chip("981", "Ada"))
        }.writeTo(mappings)

        serving(mappings) { client ->
            client.outcome(recordKeeper, In2("981", NewKeeper("Ada"))).shouldBeOk() shouldBe Chip("981", "Ada")
        }
        mappings.resolve("chips.json").readText() shouldContain "urlPath"
    }

    @Test
    fun `a second write of an unchanged contract leaves the file alone`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubs().writeTo(mappings)
        val first = mappings.resolve("chips.json").readText()

        stubs().writeTo(mappings)

        mappings.resolve("chips.json").readText() shouldBe first
    }

    @Test
    fun `a contract that no longer agrees with the written file fails, naming the file`(@TempDir tmp: Path) {
        val mappings = tmp.resolve("mappings")
        stubs().writeTo(mappings)

        val moved = stubFile(JacksonCodecs) {
            stub(lookupChip, In3(3L, "eu", 1)) answers noSuchChip(Problem("no such chip"))
            stub(lookupChip, In3(7L, "eu", 1)) answers ok(chip)
        }

        val failure = shouldThrow<AssertionError> { moved.writeTo(mappings) }
        failure.message shouldContain "chips.json"
        failure.message shouldContain STUB_FILE_UPDATE_PROPERTY
    }
}
