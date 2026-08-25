package io.github.matthewjones372.pelican.schema

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsoniter.JsoniterCodecs
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

/**
 * A derived schema has to resolve where it is handed over, and for a consumer
 * with no OpenAPI document the only place left is the object it came back in.
 *
 * The hierarchy is annotated three times over because the three sources find a
 * union three different ways, and where each addresses its pointers is the
 * thing under test: kotlinx.serialization's and jsoniter's come from the
 * `SchemaComponents` they were handed, swagger-core's from `pelican-jackson`'s
 * own rewrite, which until now asked nobody.
 */
class StandaloneSchemasTest {

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
    fun `every pointer in a derived schema resolves inside the document it came back in`() {
        sources.forEach { (name, codecs) ->
            val document = documentFrom(codecs)
            val pointers = document.pointers()

            withClue("$name wrote no pointers at all, so this asserts nothing") { pointers.shouldNotBeEmpty() }
            pointers.forEach { pointer ->
                withClue("$name: $pointer is dangling in ${document.render()}") {
                    document.resolve(pointer).shouldNotBeNull()
                }
            }
        }
    }

    @Test
    fun `the document is a pointer into the defs beside it, and those reach a nested type`() {
        // Otherwise the test above would pass on a document holding one inline
        // schema and no pointers worth resolving.
        sources.forEach { (name, codecs) ->
            val document = documentFrom(codecs)
            val defs = document["\$defs"] as? JsonObj

            withClue("$name registered no definitions") { defs.shouldNotBeNull() }
            // The hierarchy, its two branches and the type one branch holds.
            // What a branch is *called* is not pinned: kotlinx.serialization
            // uses its `@SerialName` where the other two use the class's, and
            // reconciling that is not this pass's business.
            withClue("$name did not describe the whole hierarchy") { defs!!.fields.size shouldBe 4 }
            withClue("$name did not name the hierarchy, or the type a branch holds") {
                defs!!.fields.keys shouldContainAll setOf("PaymentMethod", "Issuer")
            }
            withClue("$name did not hand back a pointer at the root") {
                (document["\$ref"] as? JsonStr)?.value shouldBe "#/\$defs/PaymentMethod"
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

/** Every pointer-shaped string: a `$ref`, and a `discriminator` mapping's values. */
private fun JsonValue.pointers(): List<String> = when (this) {
    is JsonObj -> fields.values.flatMap { it.pointers() }
    is JsonArr -> items.flatMap { it.pointers() }
    is JsonStr -> listOf(value).filter { it.startsWith("#/") }
    else -> emptyList()
}

/** What a `#/a/b` pointer names, or null where the document does not hold it. */
private fun JsonObj.resolve(pointer: String): JsonValue? =
    pointer.removePrefix("#/").split("/").fold<String, JsonValue?>(this) { node, segment ->
        (node as? JsonObj)?.get(segment)
    }
