package dev.pelican.kotlinx

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import dev.pelican.*
import dev.pelican.importer.Import
import dev.pelican.importer.ImportOptions
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.openapi.openApi
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.reflect.typeOf

/**
 * A discriminated union, all the way round.
 *
 * Generating a sealed hierarchy is the easy half. The half worth a test is
 * whether the thing generated is one a codec can actually read, and whether
 * the document a service publishes from it can be imported back into the same
 * hierarchy — because a union that only travels outwards is a union whose two
 * halves are free to drift.
 *
 * The same shape is declared twice, once for each codec, because they carry
 * the discriminator differently and neither can be inferred from the Kotlin.
 * These are hand-written copies of exactly what `KotlinTypes` emits, and
 * `UnionsTest` in `pelican-import` is what pins the generator to them.
 *
 * It lives in this module for the reason `CodecAgreementTest` does: the
 * kotlinx half has to be `@Serializable`, and only this module's compiler
 * plugin can produce that.
 */
class UnionRoundTripTest {

    // ------------------------------------------------------------- kotlinx

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

    // ------------------------------------------------------------- jackson

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(
        JsonSubTypes.Type(value = JsonCard::class, name = "JsonCard"),
        JsonSubTypes.Type(value = JsonBank::class, name = "JsonBank"),
    )
    sealed interface JsonPayment

    data class JsonCard(val number: String) : JsonPayment

    data class JsonBank(val iban: String) : JsonPayment

    // ------------------------------------------------------------ the wire

    @Test
    fun `kotlinx reads back what it wrote, as the branch it wrote`() {
        val codec = KotlinxCodecs.codec<Payment>(typeOf<Payment>())

        val written = codec.encodeToString(Card("4111"))
        written shouldContain """"kind":"card""""
        codec.decodeFromString(written) shouldBe Card("4111")
        codec.decodeFromString(codec.encodeToString(Bank("GB33"))) shouldBe Bank("GB33")
    }

    @Test
    fun `jackson reads back what it wrote, as the branch it wrote`() {
        val codec = JacksonCodecs.codec<JsonPayment>(typeOf<JsonPayment>())

        val written = codec.encodeToString(JsonCard("4111"))
        written shouldContain """"kind":"JsonCard""""
        codec.decodeFromString(written) shouldBe JsonCard("4111")
        codec.decodeFromString(codec.encodeToString(JsonBank("GB33"))) shouldBe JsonBank("GB33")
    }

    // -------------------------------------------------------- the document

    /**
     * The publishing direction, and the one place the two codecs genuinely
     * differ. kotlinx's descriptors carry the serial name of every branch, so
     * the document can say `oneOf` and name each one; swagger-core writes the
     * hierarchy the way 3.0 did, as a parent holding the `discriminator` and a
     * child per branch built with `allOf`. Both are hierarchies a document can
     * express and both are read back below — what neither of them is, is the
     * other one.
     */
    @Test
    fun `kotlinx publishes a oneOf with a discriminator naming every branch`() {
        val schemas = schemasOf(KotlinxCodecs, "Payment")

        val payment = schemas["Payment"] as JsonObj
        // kotlinx names a type by its serial name, so a branch is registered
        // under the value that selects it. The importer reads it back to
        // `Card`, which is what the class was called to begin with.
        payment.refs("oneOf").toSet() shouldBe setOf("card", "bank")
        val discriminator = payment["discriminator"] as JsonObj
        discriminator["propertyName"] shouldBe JsonStr("kind")
        (discriminator["mapping"] as JsonObj).fields.keys shouldBe setOf("card", "bank")
    }

    @Test
    fun `jackson publishes the hierarchy 3_0 spelled, and says which property tells them apart`() {
        val schemas = schemasOf(JacksonCodecs, "JsonPayment")

        val payment = schemas["JsonPayment"] as JsonObj
        withClue("swagger-core does not write a oneOf, and this is what it writes instead") {
            payment["oneOf"] shouldBe null
            (payment["discriminator"] as JsonObj)["propertyName"] shouldBe JsonStr("kind")
        }
        (schemas["JsonCard"] as JsonObj).refs("allOf") shouldBe listOf("JsonPayment")
    }

    // ------------------------------------------------------------ and back

    @Test
    fun `the document kotlinx published imports back as the same hierarchy`(@TempDir directory: File) {
        val generated = reimported(KotlinxCodecs, "Payment", directory)

        generated shouldContain "sealed interface Payment"
        generated shouldContain "data class Card("
        generated shouldContain "data class Bank("
        generated shouldContain """JsonSubTypes.Type(value = Card::class, name = "card")"""
        withClue("the hierarchy carries the discriminator, so no branch declares it") {
            generated shouldNotContain "val kind:"
        }
    }

    @Test
    fun `the document jackson published imports back as the same hierarchy`(@TempDir directory: File) {
        val generated = reimported(JacksonCodecs, "JsonPayment", directory)

        generated shouldContain "sealed interface JsonPayment"
        generated shouldContain "data class JsonCard("
        generated shouldContain """JsonSubTypes.Type(value = JsonCard::class, name = "JsonCard")"""
        generated shouldNotContain "val kind:"
    }

    // ------------------------------------------------------------- fixtures

    private fun specFor(source: SchemaSource, payload: String): ApiSpec {
        val described = if (payload == "Payment") {
            endpoint(jsonBody<Payment>()) {
                post("payments")
                operationId = "pay"
                empty(status = 204)
            }
        } else {
            endpoint(jsonBody<JsonPayment>()) {
                post("payments")
                operationId = "pay"
                empty(status = 204)
            }
        }
        return ApiSpec(
            endpoints = listOf(described),
            schemas = source,
            title = "Payments",
            version = "1.0.0",
        )
    }

    private fun schemasOf(source: SchemaSource, payload: String): JsonObj =
        ((specFor(source, payload).openApi()["components"] as JsonObj)["schemas"]) as JsonObj

    /** The document this spec publishes, read back as the endpoint descriptions it came from. */
    private fun reimported(source: SchemaSource, payload: String, directory: File): String {
        val document = File(directory, "openapi.json")
        document.writeText(specFor(source, payload).openApi().render())
        return Import.kotlin(document, ImportOptions("app", "payments")).values.single()
    }

    private fun JsonObj.refs(key: String): List<String> =
        (this[key] as? JsonArr)?.items.orEmpty()
            .mapNotNull { ((it as? JsonObj)?.get("\$ref") as? JsonStr)?.value?.substringAfterLast('/') }
}
