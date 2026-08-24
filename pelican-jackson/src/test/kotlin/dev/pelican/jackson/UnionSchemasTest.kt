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
import io.kotest.matchers.string.shouldNotContain
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
    @JsonSubTypes(
        JsonSubTypes.Type(value = Coins::class, name = "coins"),
        JsonSubTypes.Type(value = Wallet::class, name = "wallet"),
    )
    sealed interface Method {
        val currency: String
    }

    data class Coins(override val currency: String) : Method

    /** A level of the hierarchy and not a payload: `@JsonSubTypes`, no `@JsonTypeInfo`. */
    @JsonSubTypes(JsonSubTypes.Type(value = PhoneWallet::class, name = "phone"))
    sealed interface Wallet : Method {
        val issuer: String
    }

    data class PhoneWallet(
        override val currency: String,
        override val issuer: String,
        val device: String,
    ) : Wallet

    /**
     * A hierarchy whose branch is itself a hierarchy, published as the one
     * choice Jackson actually offers.
     *
     * Jackson follows `@JsonSubTypes` transitively and resolves the *declared*
     * type's type id and no other, so a `PhoneWallet` travels as
     * `kind: "phone"` and nothing on any wire is ever a `Wallet`. Listing
     * `Wallet` as a branch of `Method` — which is what this used to publish —
     * put a value in the document that the service never writes and never
     * accepts.
     */
    @Test
    fun `a hierarchy under a hierarchy is published flat, under the names Jackson selects`() {
        val described = schemas(typeOf<Method>())

        withClue("`wallet` names a level, and a level is not a payload") {
            (described["Method"] as JsonObj).mapping() shouldBe mapOf(
                "coins" to JsonStr("#/components/schemas/Coins"),
                "phone" to JsonStr("#/components/schemas/PhoneWallet"),
            )
        }
        val leaf = described["PhoneWallet"] as JsonObj
        withClue("what each level above declared travels all the way down to the class on the wire") {
            (leaf["properties"] as JsonObj).fields.keys shouldBe setOf("currency", "issuer", "device")
        }
        leaf["allOf"] shouldBe null
    }

    /**
     * The middle level, described as the payloads it can hold.
     *
     * It is not dropped, because a property or a body typed as it would leave a
     * `$ref` pointing at nothing. What it is *not* is a branch of `Method`: it
     * is a second reading of the same discriminator over a narrower set, which
     * is exactly what the Kotlin says.
     */
    @Test
    fun `the level between is described by the leaves it stands for`() {
        val wallet = schemas(typeOf<Method>())["Wallet"] as JsonObj

        wallet.refs("oneOf") shouldBe listOf("PhoneWallet")
        (wallet["discriminator"] as JsonObj)["propertyName"] shouldBe JsonStr("kind")
        wallet.mapping() shouldBe mapOf("phone" to JsonStr("#/components/schemas/PhoneWallet"))
    }

    /**
     * The document against the wire, which is the only comparison that settles
     * it.
     *
     * Everything above asserts on what gets published; this asserts that a
     * payload of the deepest class carries exactly the one type id the
     * published `mapping` names, and comes back as the class it started as.
     * Without it the flattening would be a claim about Jackson rather than a
     * fact about it.
     */
    @Test
    fun `a payload two levels down carries the one type id the document published`() {
        val codec = JacksonCodecs.codec<Method>(typeOf<Method>())
        val phone = PhoneWallet(currency = "GBP", issuer = "monzo", device = "pixel")

        val written = codec.encodeToString(phone)
        written shouldContain """"kind":"phone""""
        withClue("`wallet` is a Kotlin relation; nothing puts it on a payload") {
            written shouldNotContain "wallet"
        }
        codec.decodeFromString(written) shouldBe phone
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

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Layered::class, name = "layered"))
    sealed interface Doubled

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "issuer")
    @JsonSubTypes(JsonSubTypes.Type(value = DeepLeaf::class, name = "deep"))
    sealed interface Layered : Doubled

    data class DeepLeaf(val a: String) : Layered

    /**
     * The one nesting there is no document for, and the reason the flattening
     * above is the whole of what is supported.
     *
     * Jackson reads the declared type's type id and ignores every other, so
     * `Doubled`'s `kind` is what a read looks for and `Twice`'s `issuer` is
     * what a write puts there: `JacksonCodecs` cannot read back its own
     * payloads. There is no coherent document for that, and the refusal says
     * which annotation to delete.
     */
    @Test
    fun `a second JsonTypeInfo below the root is refused, and the message says which to remove`() {
        val failure = shouldThrow<IllegalStateException> { schemas(typeOf<Doubled>()) }.message.orEmpty()

        failure shouldContain "Layered"
        failure shouldContain "one type id per payload"
        withClue("the way out is the annotation Jackson already acts on") {
            failure shouldContain "@JsonSubTypes"
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Middle::class, name = "middle"))
    sealed interface Both

    @JsonSubTypes(JsonSubTypes.Type(value = Under::class, name = "under"))
    open class Middle(val a: String) : Both

    class Under(val b: String) : Middle("")

    /**
     * A class that is both a payload and a level. Published as it stands it
     * would be a `oneOf` branch that is itself a `oneOf`, which is the document
     * shape `pelican-import` refuses on the way back in — so it is refused on
     * the way out too, rather than written by one half of this repository and
     * rejected by the other.
     */
    @Test
    fun `a concrete class that is also a level of the hierarchy is refused`() {
        val failure = shouldThrow<IllegalStateException> { schemas(typeOf<Both>()) }.message.orEmpty()

        failure shouldContain "Middle"
        failure shouldContain "spread over two values"
        failure shouldContain "Make it abstract"
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Empty::class, name = "empty"))
    sealed interface Hollow

    sealed interface Empty : Hollow

    @Test
    fun `a level with nothing under it is refused, since no payload can be one`() {
        val failure = shouldThrow<IllegalStateException> { schemas(typeOf<Hollow>()) }.message.orEmpty()

        failure shouldContain "Empty"
        failure shouldContain "no payload can be one"
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
