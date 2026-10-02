package example

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import example.adoption.adoptionApi
import example.generated.adoption.AdoptPetFailure
import example.generated.adoption.AdoptionClient
import example.generated.adoption.ApiCallFailed
import example.generated.adoption.Outcome
import example.generated.adoption.Pet
import io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.start
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMockExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import example.generated.adoption.NotRecorded as GeneratedNotRecorded
import example.generated.adoption.RegistryDown as GeneratedRegistryDown

/**
 * Spec 0063 entry two, end to end: the server writes the tag its declaration
 * carries, and the generated client reads it back to decide which of two `503`s
 * arrived. Against a bound server rather than a stub, because the claim is that
 * the two halves agree — a stub would only restate what this test typed.
 *
 * The last two cases cannot come from a Pelican server, which always writes the
 * tag, so they come from a bare stub standing in for a service that does not.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaggedFailuresClientTest {

    private lateinit var server: PelicanServer
    private lateinit var client: AdoptionClient

    @JvmField
    @RegisterExtension
    val untagged = PelicanWireMockExtension(JacksonCodecs)

    @BeforeAll
    fun setUp() {
        server = adoptionApi().start(port = 0, systemName = "adoption-tagged-failures")
        client = AdoptionClient(server.baseUrl, JacksonCodecs, PekkoHttpTransport())
    }

    @AfterAll
    fun tearDown() = server.stop()

    @Test
    fun `the success is still the success`() {
        client.adoptPet(1L) shouldBe Outcome.Ok(Pet(1L, "Mabel"))
    }

    @Test
    fun `one of two failures sharing a status comes back as the one the handler named`() {
        client.adoptPet(2L) shouldBe Outcome.Err(AdoptPetFailure.NotRecorded(GeneratedNotRecorded(attempt = 2)))
    }

    /** The other one, under the same status, told apart only by its tag. */
    @Test
    fun `and the other does too`() {
        client.adoptPet(3L) shouldBe Outcome.Err(AdoptPetFailure.RegistryDown(GeneratedRegistryDown(retryIn = 30)))
    }

    @Test
    fun `a 503 whose body carries no tag is a failed call, not a guess at which one it was`() {
        val stubbed = stubbing("""{"attempt":2}""")

        val failure = shouldThrow<ApiCallFailed> { stubbed.adoptPet(2L) }

        failure.status shouldBe 503
        failure.body shouldContain "attempt"
    }

    @Test
    fun `nor is a tag no declaration claims`() {
        val stubbed = stubbing("""{"kind":"shop_closed","attempt":2}""")

        shouldThrow<ApiCallFailed> { stubbed.adoptPet(2L) }.status shouldBe 503
    }

    /** A client pointed at a service that answers 503 with exactly [body]. */
    private fun stubbing(body: String): AdoptionClient {
        untagged.wireMock.stubFor(
            post(urlPathEqualTo("/pets/2/adoption")).willReturn(
                aResponse().withStatus(503).withHeader("Content-Type", "application/json").withBody(body),
            ),
        )
        return AdoptionClient(untagged.baseUrl, JacksonCodecs, PekkoHttpTransport())
    }
}
