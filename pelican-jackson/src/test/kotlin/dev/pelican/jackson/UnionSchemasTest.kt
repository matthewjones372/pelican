package dev.pelican.jackson

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import dev.pelican.JsonArr
import dev.pelican.JsonObj
import dev.pelican.JsonStr
import dev.pelican.JsonValue
import dev.pelican.SchemaRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * What a Jackson hierarchy publishes.
 *
 * The interesting half is not that a union comes out — swagger-core managed
 * that already — but that the *names* do. `@JsonSubTypes.Type(name = "card")`
 * on a class called `CardPayment` is a fact only the annotation holds, and the
 * document swagger-core writes on its own does not carry it: by OpenAPI's
 * implicit-mapping rule a reader of that document decodes `CardPayment` off the
 * wire and finds `card` there instead. Every assertion below is ultimately
 * about that one string surviving.
 */
class UnionSchemasTest {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(
        JsonSubTypes.Type(value = CardPayment::class, name = "card"),
        JsonSubTypes.Type(value = BankTransfer::class, name = "bank_transfer"),
    )
    sealed interface Payment {
        /** Declared by the hierarchy, so every branch has it and none declares it. */
        val currency: String
    }

    data class CardPayment(override val currency: String, val number: String, val holder: Holder) : Payment

    data class BankTransfer(override val currency: String, val iban: String) : Payment

    data class Holder(val name: String)

    /** The hierarchy nested inside something else, which is where component names get interesting. */
    data class Basket(val id: Long, val payment: Payment)

    @Test
    fun `the mapping says what each branch is called on the wire`() {
        val payment = schemas(typeOf<Basket>())["Payment"] as JsonObj

        payment.refs("oneOf") shouldBe listOf("CardPayment", "BankTransfer")
        val discriminator = payment["discriminator"] as JsonObj
        discriminator["propertyName"] shouldBe JsonStr("kind")
        withClue("the names in @JsonSubTypes, which is the fact the old document lost") {
            (discriminator["mapping"] as JsonObj).fields shouldBe mapOf(
                "card" to JsonStr("#/components/schemas/CardPayment"),
                "bank_transfer" to JsonStr("#/components/schemas/BankTransfer"),
            )
        }
    }

    @Test
    fun `a branch is the whole payload, without the property that selects it`() {
        val card = schemas(typeOf<Basket>())["CardPayment"] as JsonObj

        withClue("`currency` is the hierarchy's, and a oneOf branch has to carry it itself") {
            (card["properties"] as JsonObj).fields.keys shouldBe setOf("currency", "number", "holder")
        }
        card.strings("required").toSet() shouldBe setOf("currency", "number", "holder")
        withClue("the discriminator is what tells the branches apart, not a property of one") {
            (card["properties"] as JsonObj)["kind"] shouldBe null
        }
        withClue("nothing points back at the parent, which is now a oneOf of this very schema") {
            card["allOf"] shouldBe null
        }
    }

    /**
     * The same schema whichever way the document was reached. A body typed as
     * the branch never resolves the hierarchy at all — swagger-core writes the
     * branch flat — so this is the check that the rewrite lands on that same
     * shape rather than a second one.
     */
    @Test
    fun `a branch reached on its own is described exactly as a branch reached through its hierarchy`() {
        schemas(typeOf<CardPayment>())["CardPayment"] shouldBe schemas(typeOf<Basket>())["CardPayment"]
    }

    @Test
    fun `the types around the hierarchy are untouched`() {
        val described = schemas(typeOf<Basket>())

        described.fields.keys shouldBe setOf("BankTransfer", "Basket", "CardPayment", "Holder", "Payment")
        ((described["Basket"] as JsonObj)["properties"] as JsonObj)["payment"] shouldBe
            JsonObj(mapOf("\$ref" to JsonStr("#/components/schemas/Payment")))
        (described["Holder"] as JsonObj)["type"] shouldBe JsonStr("object")
    }

    // --------------------------------------------------------------- nesting

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Wallet::class, name = "wallet"))
    sealed interface Method {
        val currency: String
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "issuer")
    @JsonSubTypes(JsonSubTypes.Type(value = PhoneWallet::class, name = "phone"))
    sealed interface Wallet : Method

    data class PhoneWallet(override val currency: String, val device: String) : Wallet

    /**
     * A hierarchy whose branch is itself a hierarchy. The middle one keeps
     * nothing of its own, so what the outer one declared has to travel two
     * levels to reach the class that will actually be on the wire.
     */
    @Test
    fun `a hierarchy under a hierarchy pushes what it inherited all the way down`() {
        val described = schemas(typeOf<Method>())

        (described["Method"] as JsonObj).refs("oneOf") shouldBe listOf("Wallet")
        (described["Wallet"] as JsonObj).refs("oneOf") shouldBe listOf("PhoneWallet")
        val leaf = described["PhoneWallet"] as JsonObj
        (leaf["properties"] as JsonObj).fields.keys shouldBe setOf("currency", "device")
        withClue("both discriminators are the hierarchies', not the leaf's") {
            leaf.strings("required").toSet() shouldBe setOf("currency", "device")
        }
    }

    // -------------------------------------------------------------- the name

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Named::class), JsonSubTypes.Type(value = Unnamed::class))
    sealed interface Fallbacks

    @JsonTypeName("named-by-itself")
    data class Named(val a: String) : Fallbacks

    data class Unnamed(val b: String) : Fallbacks

    @Test
    fun `a branch the hierarchy did not name is read the way Jackson reads it`() {
        val mapping = (schemas(typeOf<Fallbacks>())["Fallbacks"] as JsonObj).mapping()

        withClue("@JsonTypeName is what Jackson falls back to, and then the simple name") {
            mapping.keys shouldBe setOf("named-by-itself", "Unnamed")
        }
    }

    // ------------------------------------------------------------- refusals

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    @JsonSubTypes(JsonSubTypes.Type(value = Wrapped::class, name = "wrapped"))
    sealed interface Envelope

    data class Wrapped(val a: String) : Envelope

    @JsonTypeInfo(use = JsonTypeInfo.Id.MINIMAL_CLASS, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = ByMinimalClass::class))
    sealed interface Minimal

    data class ByMinimalClass(val a: String) : Minimal

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(
        JsonSubTypes.Type(value = Twice::class, name = "same"),
        JsonSubTypes.Type(value = Again::class, name = "same"),
    )
    sealed interface Repeated

    data class Twice(val a: String) : Repeated

    data class Again(val b: String) : Repeated

    @Test
    fun `a type carried anywhere but on the payload is refused`() {
        val failure = shouldThrow<IllegalStateException> { schemas(typeOf<Envelope>()) }

        failure.message.orEmpty() shouldContain "Envelope"
        failure.message.orEmpty() shouldContain "As.WRAPPER_OBJECT"
        withClue("a refusal says what to do about it") {
            failure.message.orEmpty() shouldContain "As.PROPERTY"
        }
    }

    @Test
    fun `a type whose branches have no publishable name is refused`() {
        val failure = shouldThrow<IllegalStateException> { schemas(typeOf<Minimal>()) }

        failure.message.orEmpty() shouldContain "Id.MINIMAL_CLASS"
        failure.message.orEmpty() shouldContain "Id.NAME"
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    interface Open

    @Test
    fun `a hierarchy whose branches are registered somewhere else is refused`() {
        val failure = shouldThrow<IllegalStateException> { schemas(typeOf<Open>()) }

        withClue("a `kind` property with no values is a document that says nothing") {
            failure.message.orEmpty() shouldContain "@JsonSubTypes"
        }
    }

    @Test
    fun `two branches selected by one value are refused`() {
        val failure = shouldThrow<IllegalStateException> { schemas(typeOf<Repeated>()) }

        failure.message.orEmpty() shouldContain "`same`"
    }

    // ------------------------------------------------------------- fixtures

    /**
     * A fresh codecs each time. `JacksonCodecs` remembers every class it has
     * described, which is the point of it — and a test sharing that memory with
     * another test would be asserting on what ran before it.
     */
    private fun schemas(type: KType): JsonObj {
        val registry = SchemaRegistry()
        JacksonCodecs(defaultMapper()).schema(type, registry)
        return registry.all()
    }

    private fun JsonObj.mapping(): Map<String, JsonValue> =
        (((this["discriminator"] as JsonObj)["mapping"]) as JsonObj).fields

    private fun JsonObj.refs(key: String): List<String> =
        (this[key] as? JsonArr)?.items.orEmpty()
            .mapNotNull { ((it as? JsonObj)?.get("\$ref") as? JsonStr)?.value?.substringAfterLast('/') }

    private fun JsonObj.strings(key: String): List<String> =
        (this[key] as? JsonArr)?.items.orEmpty().mapNotNull { (it as? JsonStr)?.value }
}
