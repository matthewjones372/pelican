package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.orFail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Two failures under one `503`, read back by the tag their bodies carry. The
 * bodies are written by hand here rather than served, because what is under
 * test is the client's reading of them — including the ones no Pelican server
 * would write, which is where reading the status alone used to go wrong
 * silently. See spec 0063.
 */
class TaggedFailuresReadBackTest {

    data class Pet(val id: Long)
    data class NotRecorded(val attempt: Int)
    data class RegistryDown(val retryIn: Int)

    private val notRecorded = errorJson<NotRecorded>(503, "The sale could not be recorded")
        .tagged("not_recorded")

    private val registryDown = errorJson<RegistryDown>(503, "The chip registry could not be reached")
        .tagged("registry_down")

    private val adoptPet = endpoint {
        post("adoption")
        json<Pet>().orFail(notRecorded, registryDown)
    }

    /** One plain failure under the same status, which is what every caller has today. */
    private val recordSale = endpoint {
        post("sales")
        json<Pet>() orFail errorJson<NotRecorded>(503, "The sale could not be recorded")
    }

    private fun answering(body: String) = ApiClient(
        transport = object : Transport {
            override fun send(request: RequestSpec) =
                ResponseSpec(503, listOf("Content-Type" to "application/json"), body)
        },
        codecs = JacksonCodecs,
    )

    @Test
    fun `the tag decides which failure arrived, and it decodes as that one's type`() {
        val refused = answering("""{"kind":"not_recorded","attempt":2}""").outcome(adoptPet, Unit)

        refused shouldBe Outcome.Err(notRecorded, NotRecorded(attempt = 2), emptyList())
    }

    @Test
    fun `the other tag under the same status reaches the other declaration`() {
        val refused = answering("""{"kind":"registry_down","retryIn":30}""").outcome(adoptPet, Unit)

        refused shouldBe Outcome.Err(registryDown, RegistryDown(retryIn = 30), emptyList())
    }

    /**
     * The whole point. Both declarations match on status, so a client reading
     * the status alone would hand the first one a body belonging to the second
     * — `retryIn` decoded into `attempt`, or a default where Jackson is lenient.
     */
    @Test
    fun `the first declaration is not handed the second one's body`() {
        val refused = answering("""{"kind":"registry_down","retryIn":30}""").outcome(adoptPet, Unit)

        (refused as Outcome.Err).error shouldBe RegistryDown(30)
    }

    @Test
    fun `a body under that status with no tag is a failed call`() {
        shouldThrow<ApiCallFailed> { answering("""{"attempt":2}""").outcome(adoptPet, Unit) }
            .response.status shouldBe 503
    }

    @Test
    fun `so is a tag no declaration claims`() {
        shouldThrow<ApiCallFailed> {
            answering("""{"kind":"shop_closed","attempt":2}""").outcome(adoptPet, Unit)
        }
    }

    /** A gateway's HTML, say: unreadable rather than untagged, and answered the same way. */
    @Test
    fun `a body that is not JSON at all is a failed call rather than a parse error`() {
        shouldThrow<ApiCallFailed> { answering("<html>503</html>").outcome(adoptPet, Unit) }
    }

    @Test
    fun `nor is a tag field carrying something that is not a string a tag`() {
        shouldThrow<ApiCallFailed> { answering("""{"kind":7,"attempt":2}""").outcome(adoptPet, Unit) }
    }

    /** Nothing changes where a status has one failure: there is no tag to read. */
    @Test
    fun `an untagged declaration is still matched on its status alone`() {
        val refused = answering("""{"attempt":2}""").outcome(recordSale, Unit)

        (refused as Outcome.Err).error shouldBe NotRecorded(attempt = 2)
    }
}
