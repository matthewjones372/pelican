package dev.pelican.kotlinx

import dev.pelican.ApiSpec
import dev.pelican.codegen.CodecAnnotations
import dev.pelican.codegen.kotlinClient
import dev.pelican.endpoint
import dev.pelican.jsonBody
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

/**
 * A generated *client* whose union kotlinx.serialization can actually read.
 *
 * Asserting on the text of a generated file says the generator wrote what the
 * generator was asked to write, and nothing about whether a codec can read it.
 * So the wire form is built out of the generated file — the discriminator and
 * the branch's name are parsed from the declarations, not typed in here — and
 * handed to the real `KotlinxCodecs`, against a compiled hierarchy annotated
 * the way that file annotates one. A generator that wrote the wrong property,
 * the wrong value, or Jackson's annotations instead fails this: there is
 * nothing left to construct the payload from.
 *
 * It lives in this module for the reason `UnionRoundTripTest` beside it does:
 * only this module's compiler plugin can produce `@Serializable`, so this is
 * the one place a generated declaration and a running serializer can be put
 * side by side.
 */
class GeneratedClientUnionTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("kind")
    sealed interface Payment

    @Serializable
    @SerialName("card")
    data class Card(val number: String) : Payment

    @Serializable
    @SerialName("bank")
    data class Bank(val iban: String) : Payment

    private val payments = ApiSpec(
        endpoints = listOf(
            endpoint(jsonBody<Payment>()) {
                post("payments")
                operationId = "pay"
                empty(status = 204)
            },
        ),
        schemas = KotlinxCodecs,
        title = "Payments",
        version = "1.0.0",
    )

    private val generated = payments.kotlinClient("app.generated", codec = CodecAnnotations.KOTLINX)

    @Test
    fun `the payload the generated declarations describe is one kotlinx decodes`() {
        val kind = generated.discriminator()
        val card = generated.serialName("Card")
        val codec = KotlinxCodecs.codec<Payment>(typeOf<Payment>())

        codec.decodeFromString("""{"$kind":"$card","number":"4111"}""") shouldBe Card("4111")

        // And the other direction, because a client both sends and receives:
        // what the serializer writes is what the generated file said it would.
        val written = Json.parseToJsonElement(codec.encodeToString(Card("4111"))).jsonObject
        written[kind]?.jsonPrimitive?.content shouldBe card
        written.keys shouldBe setOf(kind, "number")
    }

    /**
     * The discriminator is the hierarchy's and not the branch's — two places
     * holding one value is two places to disagree, and kotlinx refuses the pair
     * outright. So the branch's properties have to be exactly the serializer's
     * elements: one short and a payload will not decode, one long and it is the
     * discriminator having leaked into the class.
     */
    @Test
    fun `and the branch declares exactly the properties its serializer expects`() {
        generated.properties("Card") shouldBe serializer<Card>().descriptor.elementNames.toList()
        generated.properties("Bank") shouldBe serializer<Bank>().descriptor.elementNames.toList()
    }

    /**
     * The default, and the reason the setting had to reach the client
     * generator at all: annotated for Jackson, the same hierarchy carries
     * nothing kotlinx could construct the payload above from — no
     * `@Serializable`, and no discriminator written anywhere it reads.
     */
    @Test
    fun `annotated for the other library there is nothing here for kotlinx to read`() {
        val jackson = payments.kotlinClient("app.generated")

        jackson shouldContain "@JsonTypeInfo"
        withClue("a kotlinx service handed this client cannot decode its own payloads") {
            jackson shouldNotContain "@Serializable"
            jackson shouldNotContain "@JsonClassDiscriminator"
        }
    }

    // ------------------------------------------------------------- fixtures

    private fun String.discriminator(): String = capture("""@JsonClassDiscriminator\("([^"]+)"\)""")

    private fun String.serialName(branch: String): String =
        capture("""@SerialName\("([^"]+)"\)\s*data class $branch\(""")

    /** The properties one generated data class declares, in the order it declares them. */
    private fun String.properties(branch: String): List<String> =
        Regex("""val (\w+):""").findAll(capture("""data class $branch\(([^)]*)\)"""))
            .map { it.groupValues[1] }
            .toList()

    private fun String.capture(pattern: String): String =
        Regex(pattern).find(this)?.groupValues?.get(1)
            ?: error("The generated client has no `$pattern` in it:\n$this")
}
