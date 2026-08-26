package io.github.matthewjones372.pelican.jsoniter

import com.jsoniter.output.EncodingMode
import com.jsoniter.spi.Config
import com.jsoniter.spi.DecodingMode
import com.jsoniter.spi.JsonException
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.SchemaComponents
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * What this module has to get right on its own, where the agreement test only
 * says that it agrees.
 *
 * jsoniter binds a JSON object to a Java bean, and everything below is a shape
 * that a bean binder gets wrong: a constructor default, a property that is only
 * nullable, a sealed hierarchy, a `java.time` value. Each is checked through
 * the wire format rather than through the binding, because the wire format is
 * the thing a caller sees.
 */
class JsoniterCodecsTest {

    enum class Status { PENDING, SHIPPED }

    data class Line(val sku: String, val quantity: Int = 1, val note: String? = null)

    data class Order(val id: Long, val status: Status, val lines: List<Line>, val labels: Map<String, String>)

    data class Page<T>(val items: List<T>, val total: Int)

    data class Timestamps(val id: UUID, val placed: Instant, val due: LocalDate)

    @JvmInline value class Sku(val value: String)

    @JvmInline value class Pence(val amount: Int)

    data class Priced(val sku: Sku, val pence: Pence, val discount: Pence?, val alternative: Sku?)

    sealed interface Payment {
        data class Card(val last4: String, val expiry: String) : Payment
        data class Transfer(val iban: String) : Payment
        data class Voucher(val code: String, val pence: Int = 0) : Payment
    }

    data class Checkout(val paidWith: Payment, val alternatives: List<Payment>)

    private inline fun <reified T> codec(): BodyCodec<T> = JsoniterCodecs.codec(typeOf<T>())

    @Test
    fun `a missing property takes the constructor's default, not a zero`() {
        // The reason this module does its own binding. jsoniter constructs
        // first and assigns fields after, so it refuses a class with no
        // no-argument constructor outright, and the `@JsonCreator` it offers
        // instead throws on a `quantity` nobody sent rather than defaulting it.
        codec<Line>().decodeFromString("""{"sku":"sku-1"}""") shouldBe Line("sku-1")
        codec<Line>().decodeFromString("""{"sku":"sku-1","quantity":7}""") shouldBe Line("sku-1", 7)
    }

    @Test
    fun `a missing property that is only nullable arrives as null`() {
        // `note` has a default too, so `Line` alone cannot tell the two rules
        // apart: this is the one with nothing to fall back on.
        data class Note(val sku: String, val note: String?)
        JsoniterCodecs.codec<Note>(typeOf<Note>()).decodeFromString("""{"sku":"s"}""") shouldBe Note("s", null)
    }

    @Test
    fun `a missing property with neither says which property it was`() {
        val failure = shouldThrow<JsonException> { codec<Line>().decodeFromString("""{"quantity":2}""") }
        failure.message shouldContain "Line"
        failure.message shouldContain "sku"
    }

    @Test
    fun `a property the class does not declare is ignored`() {
        // The same lenience the other two modules are configured for: an unknown
        // field is a caller running ahead of this server, not a bad request.
        codec<Line>().decodeFromString("""{"sku":"s","gift":true,"extra":{"deep":[1,2]}}""") shouldBe Line("s")
    }

    @Test
    fun `a default is written and a null is left out`() {
        // The one spelling the three codec modules share: a nullable property
        // is optional in the schema, so its null is absence rather than a word
        // one library writes and another does not. A default is a value and is
        // written, which is what `encodeDefaults` means for the other two.
        codec<Line>().encodeToString(Line("s")) shouldBe """{"sku":"s","quantity":1}"""
    }

    @Test
    fun `enums, collections and maps make the round trip`() {
        val order = Order(7, Status.SHIPPED, listOf(Line("a", 2), Line("b")), mapOf("dc" to "east"))
        val text = codec<Order>().encodeToString(order)

        text shouldContain """"status":"SHIPPED""""
        codec<Order>().decodeFromString(text) shouldBe order
    }

    @Test
    fun `an enum constant nobody declared is named, along with the ones that were`() {
        val failure = shouldThrow<JsonException> {
            codec<Order>().decodeFromString("""{"id":1,"status":"LOST","lines":[],"labels":{}}""")
        }
        failure.message shouldContain "LOST"
        failure.message shouldContain "PENDING"
    }

    @Test
    fun `a body whose own type is a collection is read and written`() {
        // No model to resolve and no class to bind: the top level is jsoniter's
        // own list and map handling, with this module's binding inside it.
        val lines = listOf(Line("a"), Line("b", 3))
        codec<List<Line>>().decodeFromString(codec<List<Line>>().encodeToString(lines)) shouldBe lines

        val depots = mapOf("east" to Line("a"), "west" to Line("b"))
        codec<Map<String, Line>>().decodeFromString(codec<Map<String, Line>>().encodeToString(depots)) shouldBe depots
    }

    @Test
    fun `a nullable element inside a collection survives both directions`() {
        // The null element stays where a null property would be dropped: an
        // absent property is one the schema called optional, and a missing
        // element would be a shorter list.
        val attempts = listOf(Line("a"), null)
        val text = codec<List<Line?>>().encodeToString(attempts)

        text shouldBe """[{"sku":"a","quantity":1},null]"""
        codec<List<Line?>>().decodeFromString(text) shouldBe attempts
    }

    @Test
    fun `the types jsoniter never learned travel as the strings the document says`() {
        // jsoniter predates `java.time` being ordinary and treats a UUID as an
        // object with fields; left alone it would publish a document saying
        // `string` and write something else entirely.
        val stamps = Timestamps(
            id = UUID.fromString("6f1c8c2e-0f6e-4f4a-9d0f-0a1b2c3d4e5f"),
            placed = Instant.parse("2026-02-01T09:30:00Z"),
            due = LocalDate.parse("2026-02-08"),
        )
        val text = codec<Timestamps>().encodeToString(stamps)

        text shouldBe """{"id":"6f1c8c2e-0f6e-4f4a-9d0f-0a1b2c3d4e5f",""" +
            """"placed":"2026-02-01T09:30:00Z","due":"2026-02-08"}"""
        codec<Timestamps>().decodeFromString(text) shouldBe stamps
        schemaOf<Timestamps>()["properties"].asObj()["placed"].asObj() shouldBe
            JsonObj(mapOf("type" to JsonStr("string"), "format" to JsonStr("date-time")))
    }

    @Test
    fun `a sealed hierarchy travels with the discriminator its document declares`() {
        val checkout = Checkout(
            paidWith = Payment.Card("4242", "01/30"),
            alternatives = listOf(Payment.Transfer("GB33BUKB20201555555555"), Payment.Voucher("XMAS", 500)),
        )
        val text = codec<Checkout>().encodeToString(checkout)

        text shouldContain """"paidWith":{"type":"Card","last4":"4242""""
        text shouldContain """{"type":"Transfer","iban":"""
        codec<Checkout>().decodeFromString(text) shouldBe checkout
    }

    @Test
    fun `a branch is read wherever its discriminator appears in the payload`() {
        // Which branch to build decides how the rest is read, and JSON makes no
        // promise about field order — so a discriminator arriving last has to
        // work as well as one arriving first.
        codec<Payment>().decodeFromString("""{"last4":"4242","expiry":"01/30","type":"Card"}""") shouldBe
            Payment.Card("4242", "01/30")
    }

    @Test
    fun `a payload with no discriminator, or an unknown one, says so`() {
        shouldThrow<JsonException> { codec<Payment>().decodeFromString("""{"iban":"GB33"}""") }
            .message shouldContain "type"
        shouldThrow<JsonException> { codec<Payment>().decodeFromString("""{"type":"Cheque"}""") }
            .message shouldContain "Cheque"
    }

    @Test
    fun `the union is documented as a oneOf over its branches, mapped by name`() {
        val components = Components()
        val schema = JsoniterCodecs.schema(typeOf<Payment>(), components)

        schema shouldBe components.ref("Payment")
        val union = components.schemas.getValue("Payment")
        (union["oneOf"] as JsonArr).items.map { (it as JsonObj)["\$ref"] } shouldBe listOf(
            JsonStr("#/components/schemas/Card"),
            JsonStr("#/components/schemas/Transfer"),
            JsonStr("#/components/schemas/Voucher"),
        )
        union["discriminator"].asObj()["propertyName"] shouldBe JsonStr("type")
        union["discriminator"].asObj()["mapping"].asObj()["Card"] shouldBe JsonStr("#/components/schemas/Card")
        // A branch's own schema describes only what the branch declares; the
        // discriminator is the union's, and `pelican-import` reads it there.
        components.schemas.getValue("Card").asObj()["properties"].asObj().fields.keys shouldBe setOf("last4", "expiry")
    }

    @Test
    fun `a recursive type terminates at its own reference`() {
        data class Category(val name: String, val children: List<Category>)

        val components = Components()
        JsoniterCodecs.schema(typeOf<Category>(), components)

        components.schemas.getValue("Category")["properties"].asObj()["children"].asObj()["items"] shouldBe
            components.ref("Category")
    }

    @Test
    fun `a payload type with type parameters is refused rather than read as a map`() {
        // Nothing carries the argument to where the binding happens: a property
        // typed `T` reflects as `T`, and jsoniter would hand back whatever the
        // JSON looked like. Refused in both directions, and in the document.
        val page = Page(listOf(Line("a")), total = 1)
        val codec = JsoniterCodecs.codec<Page<Line>>(typeOf<Page<Line>>())

        shouldThrow<JsonException> { codec.encodeToString(page) }.message shouldContain "Page"
        shouldThrow<JsonException> { codec.decodeFromString("""{"items":[],"total":0}""") }
            .message shouldContain "KotlinxCodecs"
        shouldThrow<IllegalStateException> { JsoniterCodecs.schema(typeOf<Page<Line>>(), Components()) }
            .message shouldContain "Page"
    }

    @Test
    fun `a value class travels as the value inside it`() {
        // Which is also the only thing that could travel: the JVM erases the
        // wrapper out of most signatures, so the document and the wire agree
        // on the value or they agree on nothing.
        val priced = Priced(Sku("sku-1"), Pence(250), null, Sku("sku-2"))
        val text = """{"sku":"sku-1","pence":250,"alternative":"sku-2"}"""

        codec<Priced>().encodeToString(priced) shouldBe text
        codec<Priced>().decodeFromString(text) shouldBe priced
        // And the spelling that writes the null still reads, which is what
        // makes leaving it out a choice about the wire and not about meaning.
        codec<Priced>().decodeFromString(
            """{"sku":"sku-1","pence":250,"discount":null,"alternative":"sku-2"}""",
        ) shouldBe priced

        // And as a body in its own right, where the wrapper is all there is.
        codec<Sku>().encodeToString(Sku("sku-1")) shouldBe """"sku-1""""
        codec<Sku>().decodeFromString(""""sku-1"""") shouldBe Sku("sku-1")
    }

    @Test
    fun `a value class is described as the value inside it`() {
        val properties = schemaOf<Priced>()["properties"].asObj()

        properties["sku"] shouldBe JsonObj(mapOf("type" to JsonStr("string")))
        properties["pence"].asObj()["format"] shouldBe JsonStr("int32")
        properties["discount"].asObj()["type"] shouldBe JsonArr(listOf(JsonStr("integer"), JsonStr("null")))
    }

    @Test
    fun `a config that was not built here is refused rather than silently wrong`() {
        // A plain jsoniter config parses perfectly well and refuses to bind a
        // data class at all, which is the failure this module exists to prevent.
        shouldThrow<IllegalArgumentException> { JsoniterCodecs(Config.Builder().build()) }
            .message shouldContain "jsoniterConfig"
    }

    @Test
    fun `a codegen mode is refused, because javassist is not a dependency here`() {
        shouldThrow<IllegalArgumentException> {
            jsoniterConfig { decodingMode(DecodingMode.DYNAMIC_MODE_AND_MATCH_FIELD_WITH_HASH) }
        }.message shouldContain "REFLECTION_MODE"
        shouldThrow<IllegalArgumentException> {
            jsoniterConfig { encodingMode(EncodingMode.DYNAMIC_MODE) }
        }.message shouldContain "REFLECTION_MODE"
    }

    @Test
    fun `an indented config indents the way jsoniter does`() {
        // The first field included: jsoniter's own encoder writes the indention
        // that `writeObjectStart` only counts, and an object that skips it opens
        // with its first field sharing the brace's line.
        val indented = JsoniterCodecs(jsoniterConfig { indentionStep(2) })

        indented.codec<Line>(typeOf<Line>()).encodeToString(Line("sku-1")) shouldBe
            "{\n  \"sku\": \"sku-1\",\n  \"quantity\": 1\n}"
        indented.codec<Payment>(typeOf<Payment>()).encodeToString(Payment.Transfer("GB33")) shouldBe
            "{\n  \"type\": \"Transfer\",\n  \"iban\": \"GB33\"\n}"
    }

    @Test
    fun `omitDefaultValue leaves out what jsoniter leaves out`() {
        val terse = JsoniterCodecs(jsoniterConfig { omitDefaultValue(true) })

        terse.codec<Line>(typeOf<Line>()).encodeToString(Line("sku-1", quantity = 0)) shouldBe """{"sku":"sku-1"}"""
        // And the setting is the config's, not the module's: jsoniter's rule
        // drops a zero as well as a null, and the default config writes the
        // zero, which is a value the document describes.
        codec<Line>().encodeToString(Line("sku-1", quantity = 0)) shouldBe """{"sku":"sku-1","quantity":0}"""
    }

    @Test
    fun `what follows a union in a payload is still read as Kotlin`() {
        // A branch is decoded by a nested `deserialize`, and jsoniter restores
        // the *default* config when one ends rather than the config that was
        // current — leaving the rest of the payload to a jsoniter that has
        // never heard of Kotlin.
        val text = """{"paidWith":{"type":"Transfer","iban":"GB33"},"alternatives":[{"type":"Voucher","code":"X"}]}"""

        codec<Checkout>().decodeFromString(text) shouldBe
            Checkout(Payment.Transfer("GB33"), listOf(Payment.Voucher("X")))
    }

    private inline fun <reified T> schemaOf(): JsonObj {
        val components = Components()
        val schema = JsoniterCodecs.schema(typeOf<T>(), components)
        val name = (schema["\$ref"] as? JsonStr)?.value?.substringAfterLast('/') ?: return schema
        return components.schemas.getValue(name)
    }

    /** The smallest thing a schema source can register into. */
    private class Components : SchemaComponents {
        val schemas = LinkedHashMap<String, JsonObj>()
        override fun register(name: String, schema: JsonObj) { schemas[name] = schema }
        override fun isRegistered(name: String) = name in schemas
        override fun ref(name: String) = JsonObj(mapOf("\$ref" to JsonStr("#/components/schemas/$name")))
    }
}

private fun JsonValue?.asObj(): JsonObj = this as? JsonObj ?: error("not an object: $this")
