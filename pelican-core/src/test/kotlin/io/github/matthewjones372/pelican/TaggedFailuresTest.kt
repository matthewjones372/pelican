package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.taggedBody
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture.completedFuture
import kotlin.reflect.KType

/**
 * Two reasons for one status, told apart by a tag in the body rather than by reading English.
 * See spec 0063.
 */
class TaggedFailuresTest {

    data class NotRecorded(val attempt: Int)

    data class RegistryDown(val retryIn: Int)

    private val notRecorded = errorJson<NotRecorded>(503, "The sale could not be recorded")
        .tagged("not_recorded")

    private val registryDown = errorJson<RegistryDown>(503, "The chip registry could not be reached")
        .tagged("registry_down")

    private val petId = pathParam<Long>("petId")

    @Test
    fun `two failures may share a status when both are tagged`() {
        val ep = endpoint(petId) {
            post("pets" / petId / "adoption")
            json<String>().orFail(notRecorded, registryDown)
        }

        ep.errors.filter { it.status == 503 }.map { it.tag } shouldBe listOf("not_recorded", "registry_down")
    }

    @Test
    fun `two untagged failures under one status are still refused, and the message says to tag them`() {
        val message = shouldThrow<IllegalArgumentException> {
            endpoint(petId) {
                post("pets" / petId / "adoption")
                json<String>().orFail(
                    errorJson<NotRecorded>(503, "Not recorded"),
                    errorJson<RegistryDown>(503, "Registry down"),
                )
            }
        }.message.orEmpty()

        message shouldContain "503"
        message shouldContain "tagged"
    }

    @Test
    fun `one tagged and one untagged under a status is refused`() {
        shouldThrow<IllegalArgumentException> {
            endpoint(petId) {
                post("pets" / petId / "adoption")
                json<String>().orFail(notRecorded, errorJson<RegistryDown>(503, "Registry down"))
            }
        }.message.orEmpty() shouldContain "tagged"
    }

    @Test
    fun `two failures sharing a status and a tag are refused`() {
        shouldThrow<IllegalArgumentException> {
            endpoint(petId) {
                post("pets" / petId / "adoption")
                json<String>().orFail(
                    notRecorded,
                    errorJson<RegistryDown>(503, "Registry down").tagged("not_recorded"),
                )
            }
        }.message.orEmpty() shouldContain "not_recorded"
    }

    @Test
    fun `two failures sharing a status must agree on the field their tag is written under`() {
        val message = shouldThrow<IllegalArgumentException> {
            endpoint(petId) {
                post("pets" / petId / "adoption")
                json<String>().orFail(
                    notRecorded,
                    errorJson<RegistryDown>(503, "Registry down").tagged("registry_down", field = "reason"),
                )
            }
        }.message.orEmpty()

        message shouldContain "503"
        message shouldContain "kind"
        message shouldContain "reason"
    }

    @Test
    fun `a tag on a payload that is not an object is refused`() {
        shouldThrow<IllegalArgumentException> {
            errorJson<String>(503, "A bare message").tagged("not_recorded")
        }.message.orEmpty() shouldContain "object"
    }

    @Test
    fun `a tag is written into the body under kind`() {
        taggedBody(notRecorded, """{"attempt":2}""") shouldBe """{"kind":"not_recorded","attempt":2}"""
    }

    @Test
    fun `the discriminator field is settable per declaration`() {
        val reason = errorJson<NotRecorded>(503, "Not recorded").tagged("not_recorded", field = "reason")

        taggedBody(reason, """{"attempt":2}""") shouldBe """{"reason":"not_recorded","attempt":2}"""
    }

    @Test
    fun `an untagged failure's body is left exactly as its codec wrote it`() {
        val plain = errorJson<NotRecorded>(503, "Not recorded")

        taggedBody(plain, """{"attempt":2}""") shouldBe """{"attempt":2}"""
    }

    @Test
    fun `a tag is written once, not again on a body that already carries it`() {
        taggedBody(notRecorded, """{"kind":"not_recorded","attempt":2}""") shouldContain "\"attempt\":2"
        taggedBody(notRecorded, """{"kind":"not_recorded","attempt":2}""")
            .removePrefix("""{"kind":"not_recorded",""") shouldNotContain "kind"
    }

    /** One tagged failure answered through the in-memory interpreter, body and all. */
    @Test
    fun `the interpreter answers a tagged failure with the tag in its body`() {
        val ep = endpoint(petId) {
            post("pets" / petId / "adoption")
            json<String>().orFail(notRecorded, registryDown)
        }
        val answer = registryDown(RegistryDown(30))
        val served = api(listOf(ServerEndpoint(ep) { completedFuture(answer) }), FixedJson)
        val response = InMemoryClientTransport(served)
            .send(ClientRequest(Method.POST, "http://stub/pets/1/adoption"))
            .toCompletableFuture().get()

        response.status shouldBe 503
        response.text() shouldContain """"kind":"registry_down""""
    }

    /** Encodes whatever it is given as one flat object, so the splice is what is under test. */
    private object FixedJson : Codecs {
        override fun <T> codec(type: KType): BodyCodec<T> {
            @Suppress("UNCHECKED_CAST")
            return object : BodyCodec<Any?> {
                override fun encodeToString(value: Any?) = when (value) {
                    is RegistryDown -> """{"retryIn":${value.retryIn}}"""
                    is NotRecorded -> """{"attempt":${value.attempt}}"""
                    else -> "\"$value\""
                }

                override fun decodeFromString(text: String): Any? = text
            } as BodyCodec<T>
        }

        override fun schema(type: KType, components: SchemaComponents): JsonObj = jsonObj { "type" to "object" }
    }
}
