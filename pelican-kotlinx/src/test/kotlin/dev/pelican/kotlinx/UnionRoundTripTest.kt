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
        JsonSubTypes.Type(value = JsonCard::class, name = "json_card"),
        JsonSubTypes.Type(value = JsonBank::class, name = "json_bank"),
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
        written shouldContain """"kind":"json_card""""
        codec.decodeFromString(written) shouldBe JsonCard("4111")
        codec.decodeFromString(codec.encodeToString(JsonBank("GB33"))) shouldBe JsonBank("GB33")
    }

    // -------------------------------------------------------- the document

    /**
     * The publishing direction, and what the two codecs have to agree on.
     *
     * Each reads a different set of annotations off a different set of classes,
     * and neither can see what the other saw — but both know which value
     * selects which branch, and both write it down the same way. That was not
     * true for a long time: swagger-core describes a Jackson hierarchy the way
     * 3.0 had to, a parent holding the `discriminator` and a child per branch
     * built with `allOf`, and it writes no `mapping` because the names in
     * `@JsonSubTypes` never reach its schema model. `pelican-jackson` rewrites
     * that from the annotations now, which is what these two tests pin: one
     * spelling, and the names in it.
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
    fun `jackson publishes the same spelling, under the names its annotations gave the branches`() {
        val schemas = schemasOf(JacksonCodecs, "JsonPayment")

        val payment = schemas["JsonPayment"] as JsonObj
        payment.refs("oneOf") shouldBe listOf("JsonCard", "JsonBank")
        val discriminator = payment["discriminator"] as JsonObj
        discriminator["propertyName"] shouldBe JsonStr("kind")
        withClue("`json_card` is what the code puts on the wire, and only the annotation knows it") {
            (discriminator["mapping"] as JsonObj).fields shouldBe mapOf(
                "json_card" to JsonStr("#/components/schemas/JsonCard"),
                "json_bank" to JsonStr("#/components/schemas/JsonBank"),
            )
        }
        withClue("a branch of a oneOf is the whole payload; pointing back at the parent would be a cycle") {
            (schemas["JsonCard"] as JsonObj)["allOf"] shouldBe null
        }
    }

    /**
     * The same document shape from two codecs that share no code. Compared
     * structurally rather than field by field, because the two hierarchies here
     * are different Kotlin classes with different names — what has to match is
     * the shape a reader of either document is holding.
     */
    @Test
    fun `both codecs publish a hierarchy the same way`() {
        val kotlinx = schemasOf(KotlinxCodecs, "Payment")["Payment"] as JsonObj
        val jackson = schemasOf(JacksonCodecs, "JsonPayment")["JsonPayment"] as JsonObj

        jackson.fields.keys shouldBe kotlinx.fields.keys
        (jackson["discriminator"] as JsonObj).fields.keys shouldBe (kotlinx["discriminator"] as JsonObj).fields.keys
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
        generated shouldContain """JsonSubTypes.Type(value = JsonCard::class, name = "json_card")"""
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
