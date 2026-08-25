package io.github.matthewjones372.pelican.schema

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonBool
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsoniter.JsoniterCodecs
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.typeOf
import io.kotest.matchers.string.shouldContain as shouldContainText

/**
 * A branch schema that a validator accepts and the codec then refuses is worse
 * than no schema: the property telling the branches apart belongs to no Kotlin
 * type, so derivation has nothing to emit and every codec synthesises it when
 * encoding.
 */
class UnionBranchesTest {

    @Serializable
    data class Issuer(val name: String, val country: String)

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "method")
    @JsonSubTypes(
        JsonSubTypes.Type(value = Card::class, name = "card"),
        JsonSubTypes.Type(value = Transfer::class, name = "transfer"),
    )
    @Serializable
    sealed interface PaymentMethod

    @Serializable
    @SerialName("card")
    data class Card(val number: String, val issuer: Issuer) : PaymentMethod

    @Serializable
    @SerialName("transfer")
    data class Transfer(val iban: String) : PaymentMethod

    private val sources = listOf(
        "JacksonCodecs" to JacksonCodecs,
        "KotlinxCodecs" to KotlinxCodecs,
        "JsoniterCodecs" to JsoniterCodecs,
    )

    private fun documentFrom(codecs: Codecs): JsonObj =
        StandaloneSchemas(codecs).schema(typeOf<PaymentMethod>())

    @Test
    fun `a payload written to satisfy a branch schema decodes through the codec that described it`() {
        sources.forEach { (name, codecs) ->
            val document = documentFrom(codecs)
            val branches = document.branchesOf("PaymentMethod")

            withClue("$name did not describe both branches") { branches shouldHaveSize 2 }
            branches.forEach { branch ->
                val payload = document.payloadFor(branch).render()
                val codec = codecs.codec<PaymentMethod>(typeOf<PaymentMethod>())
                withClue("$name: $payload satisfies the schema and does not decode") {
                    codec.decodes(payload) shouldBe true
                }
            }
        }
    }

    @Test
    fun `the branch carries the property that selects it, and the choice carries no discriminator`() {
        sources.forEach { (name, codecs) ->
            val document = documentFrom(codecs)
            val union = document.defs()["PaymentMethod"] as JsonObj

            withClue("$name left OpenAPI's discriminator where JSON Schema cannot read it") {
                union["discriminator"] shouldBe null
            }
            document.branchesOf("PaymentMethod").forEach { branch ->
                // What the value is spelled as is the codec's business: jsoniter
                // picks a branch by its class name where the other two read an
                // annotation. That it is pinned at all is this pass's business.
                val property = branch.constants().keys.singleOrNull()
                withClue("$name left a branch with nothing saying which branch it is") {
                    property.shouldNotBeNull()
                }
                withClue("$name did not require the property it pinned") {
                    (branch["required"] as JsonArr).items shouldContain JsonStr(property!!)
                }
            }
        }
    }

    // ------------------------------------------------------------- refusals

    @Serializable
    sealed interface Payable

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Both::class, name = "both"))
    interface Settled

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Both::class, name = "elsewhere"))
    interface Pending

    data class Both(val id: String) : Settled, Pending

    /** Both hierarchies in one document, which is the only way the clash is visible. */
    data class Ledger(val settled: Settled, val pending: Pending)

    @Test
    fun `a branch two hierarchies select differently is refused, naming what it is called twice`() {
        val message = assertThrows<IllegalArgumentException> {
            StandaloneSchemas(JacksonCodecs).schema(typeOf<Ledger>())
        }.message.orEmpty()

        withClue("the refusal has to name the branch and both values") {
            message shouldContainText "Both"
            message shouldContainText "both"
            message shouldContainText "elsewhere"
        }
    }

    @Serializable
    abstract class OpenPayment

    @Test
    fun `an open hierarchy is refused rather than described as an object that accepts anything`() {
        val message = assertThrows<IllegalArgumentException> {
            StandaloneSchemas(KotlinxCodecs).schema(typeOf<OpenPayment>())
        }.message.orEmpty()

        withClue("the refusal has to say what to write instead") { message shouldContainText "sealed" }
    }
}

// ------------------------------------------------------------------ helpers

private fun BodyCodec<*>.decodes(payload: String): Boolean =
    runCatching { decodeFromString(payload) }.isSuccess

private fun JsonObj.defs(): JsonObj = this["\$defs"] as? JsonObj ?: JsonObj(emptyMap())

/** The branch schemas a `oneOf` names, followed to where the document keeps them. */
private fun JsonObj.branchesOf(union: String): List<JsonObj> {
    val defs = defs()
    val choice = (defs[union] as? JsonObj)?.get("oneOf") as? JsonArr ?: return emptyList()
    return choice.items.mapNotNull { branch ->
        val pointer = ((branch as? JsonObj)?.get("\$ref") as? JsonStr)?.value ?: return@mapNotNull branch as? JsonObj
        defs[pointer.substringAfterLast('/')] as? JsonObj
    }
}

/** Every property this schema pins to one value: what a discriminator becomes. */
private fun JsonObj.constants(): Map<String, JsonValue> =
    ((this["properties"] as? JsonObj)?.fields.orEmpty())
        .mapNotNull { (name, schema) -> ((schema as? JsonObj)?.get("const"))?.let { name to it } }
        .toMap()

/**
 * The smallest payload this schema accepts: every required property, at the
 * value it pins or an arbitrary one of the right type. Written from the schema
 * alone, so a property the schema forgot is a property the payload lacks.
 */
private fun JsonObj.payloadFor(schema: JsonObj): JsonObj {
    val properties = (schema["properties"] as? JsonObj)?.fields.orEmpty()
    val required = ((schema["required"] as? JsonArr)?.items.orEmpty()).mapNotNull { (it as? JsonStr)?.value }
    return JsonObj(required.mapNotNull { name -> (properties[name] as? JsonObj)?.let { name to valueFor(it) } }.toMap())
}

private fun JsonObj.valueFor(schema: JsonObj): JsonValue {
    schema["const"]?.let { return it }
    (schema["\$ref"] as? JsonStr)?.let { ref ->
        val target = defs()[ref.value.substringAfterLast('/')] as? JsonObj
        return target?.let { payloadFor(it) } ?: JsonObj(emptyMap())
    }
    return when (((schema["type"] as? JsonStr)?.value)) {
        "integer", "number" -> JsonNum(1)
        "boolean" -> JsonBool(true)
        "array" -> JsonArr(emptyList())
        "object" -> JsonObj(emptyMap())
        else -> JsonStr("x")
    }
}
