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

    // -------------------------------------------------------- two levels up

    /**
     * A hierarchy whose branch is itself a hierarchy, in both codecs, and the
     * flat twin of each.
     *
     * The nesting is a Kotlin relation and nothing else. Neither library puts
     * two type ids on a payload: kotlinx.serialization flattens a nested sealed
     * hierarchy to one discriminator naming the leaf — and makes a second
     * `@JsonClassDiscriminator` under one hierarchy a compile error — while
     * Jackson resolves the declared type's id only, following `@JsonSubTypes`
     * transitively to find what it selects. So the middle level names no value,
     * and the flat twins below are what a reader of the published document gets
     * back.
     *
     * The twins are the point of the fixture. Asserting that the nested
     * hierarchy writes `kind: "card"` proves what one codec does; decoding that
     * same string into the *flat* class proves the document published from the
     * nesting describes the payload the nesting produces.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("kind")
    sealed interface Wallet

    @Serializable
    @SerialName("coins")
    data class Coins(val amount: Long) : Wallet

    @Serializable
    sealed interface Electronic : Wallet

    @Serializable
    @SerialName("card")
    data class WalletCard(val number: String) : Electronic

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("kind")
    sealed interface FlatWallet

    @Serializable
    @SerialName("card")
    data class FlatCard(val number: String) : FlatWallet

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(
        JsonSubTypes.Type(value = JsonCoins::class, name = "json_coins"),
        JsonSubTypes.Type(value = JsonElectronic::class, name = "json_electronic"),
    )
    sealed interface JsonWallet

    data class JsonCoins(val amount: Long) : JsonWallet

    @JsonSubTypes(JsonSubTypes.Type(value = JsonWalletCard::class, name = "json_wallet_card"))
    sealed interface JsonElectronic : JsonWallet

    data class JsonWalletCard(val number: String) : JsonElectronic

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = JsonFlatCard::class, name = "json_wallet_card"))
    sealed interface JsonFlatWallet

    data class JsonFlatCard(val number: String) : JsonFlatWallet

    @Test
    fun `kotlinx writes a leaf two levels down under the outermost discriminator`() {
        val nested = KotlinxCodecs.codec<Wallet>(typeOf<Wallet>())
        val flat = KotlinxCodecs.codec<FlatWallet>(typeOf<FlatWallet>())

        val written = nested.encodeToString(WalletCard("4111"))
        written shouldContain """"kind":"card""""
        withClue("`Electronic` is a Kotlin relation; nothing selects it on the wire") {
            written shouldNotContain "Electronic"
        }
        nested.decodeFromString(written) shouldBe WalletCard("4111")
        withClue("the flat hierarchy the document describes reads the nested one's payload") {
            flat.decodeFromString(written) shouldBe FlatCard("4111")
        }
    }

    @Test
    fun `jackson writes a leaf two levels down under the outermost discriminator`() {
        val nested = JacksonCodecs.codec<JsonWallet>(typeOf<JsonWallet>())
        val flat = JacksonCodecs.codec<JsonFlatWallet>(typeOf<JsonFlatWallet>())

        val written = nested.encodeToString(JsonWalletCard("4111"))
        written shouldContain """"kind":"json_wallet_card""""
        written shouldNotContain "json_electronic"
        nested.decodeFromString(written) shouldBe JsonWalletCard("4111")
        flat.decodeFromString(written) shouldBe JsonFlatCard("4111")
    }

    @Test
    fun `both codecs publish a nested hierarchy as the one flat choice it is`() {
        val kotlinx = schemasOf(KotlinxCodecs, "Wallet")["Wallet"] as JsonObj
        val jackson = schemasOf(JacksonCodecs, "JsonWallet")["JsonWallet"] as JsonObj

        (kotlinx["discriminator"] as JsonObj).mapping().keys shouldBe setOf("coins", "card")
        (jackson["discriminator"] as JsonObj).mapping().keys shouldBe
            setOf("json_coins", "json_wallet_card")
        withClue("no branch of either is a choice of its own; two type ids is what nothing reads") {
            kotlinx.refs("oneOf").forEach { branch ->
                (schemasOf(KotlinxCodecs, "Wallet")[branch] as JsonObj)["oneOf"] shouldBe null
            }
            jackson.refs("oneOf").forEach { branch ->
                (schemasOf(JacksonCodecs, "JsonWallet")[branch] as JsonObj)["oneOf"] shouldBe null
            }
        }
    }

    @Test
    fun `the document a nested hierarchy published imports back as one flat hierarchy`(@TempDir directory: File) {
        val generated = reimported(JacksonCodecs, "JsonWallet", directory)

        generated shouldContain "sealed interface JsonWallet"
        generated shouldContain """JsonSubTypes.Type(value = JsonWalletCard::class, name = "json_wallet_card")"""
        withClue("the level between held nothing on the wire, so nothing comes back for it") {
            generated shouldNotContain "JsonElectronic"
        }
    }

    // ------------------------------------------------------------- fixtures

    private fun specFor(source: SchemaSource, payload: String): ApiSpec {
        val described = when (payload) {
            "Payment" -> endpoint(jsonBody<Payment>()) {
                post("payments")
                operationId = "pay"
                empty(status = 204)
            }

            "Wallet" -> endpoint(jsonBody<Wallet>()) {
                post("payments")
                operationId = "pay"
                empty(status = 204)
            }

            "JsonWallet" -> endpoint(jsonBody<JsonWallet>()) {
                post("payments")
                operationId = "pay"
                empty(status = 204)
            }

            else -> endpoint(jsonBody<JsonPayment>()) {
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

    private fun JsonObj.mapping(): Map<String, JsonValue> = (this["mapping"] as JsonObj).fields

    private fun JsonObj.refs(key: String): List<String> =
        (this[key] as? JsonArr)?.items.orEmpty()
            .mapNotNull { ((it as? JsonObj)?.get("\$ref") as? JsonStr)?.value?.substringAfterLast('/') }
}
