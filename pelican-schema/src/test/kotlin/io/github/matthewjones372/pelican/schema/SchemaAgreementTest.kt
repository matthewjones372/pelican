package io.github.matthewjones372.pelican.schema

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsoniter.JsoniterCodecs
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * The claim the codec/schema split rests on, and the one nothing checked: does a
 * body this library writes satisfy the schema this library publishes for it?
 *
 * `SchemaSource` and `CodecFactory` are separate interfaces on purpose — a
 * document has to be generatable with no server present — and separate
 * interfaces are two things that can disagree. `OpenApiSpecQualityTest` says the
 * document is well-formed and `ThreeCodecsTest` says the three libraries write
 * the same bytes; neither asks whether the bytes fit the schema.
 *
 * It is not documentation-only. `FormShape.of` reads the published schema to
 * decide whether a form field is a string, an integer or an array, so a
 * disagreement is a 400 rather than a bad document.
 *
 * The validator did not write the schema, which is the principle that already
 * puts swagger-parser in front of the emitted document.
 */
class SchemaAgreementTest {

    // ------------------------------------------------------------- the shapes

    @Serializable
    data class Owner(val id: Long, val email: String)

    @Serializable
    data class Item(val id: Long, val name: String, val owner: Owner?)

    @Serializable
    data class Page(val items: List<Item>, val next: String?, val total: Long)

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(
        JsonSubTypes.Type(value = Draft::class, name = "draft"),
        JsonSubTypes.Type(value = Published::class, name = "published"),
    )
    @Serializable
    sealed interface State

    @Serializable
    @SerialName("draft")
    data class Draft(val editedBy: String) : State

    @Serializable
    @SerialName("published")
    data class Published(val at: String) : State

    @Serializable
    data class Envelope(val item: Item, val state: State)

    // ------------------------------------------------------- the harder ones

    @Serializable
    data class Node(val name: String, val children: List<Node>)

    @JvmInline
    @Serializable
    value class UserId(val raw: Long)

    @Serializable
    data class Wrapped(val who: UserId, val tags: Map<String, List<Long?>>)

    @Serializable
    enum class Level { LOW, HIGH }

    @Serializable
    data class Scored(val level: Level, val score: Double)

    /** One shape and a value of it, which is the pair every claim below needs. */
    private class Shape(val name: String, val type: KType, val value: Any)

    private val shapes = listOf(
        Shape("a record", typeOf<Owner>(), Owner(1, "ada@example.com")),
        Shape("a nullable reference, present", typeOf<Item>(), Item(1, "widget", Owner(2, "a@b.c"))),
        Shape("a nullable reference, absent", typeOf<Item>(), Item(1, "widget", null)),
        Shape("a list of records", typeOf<Page>(), Page(listOf(Item(1, "w", null)), null, 1)),
        Shape("a sealed branch", typeOf<State>(), Draft("ada")),
        Shape("the other branch", typeOf<State>(), Published("yesterday")),
        Shape("a union nested in a record", typeOf<Envelope>(), Envelope(Item(1, "w", null), Draft("ada"))),
        Shape("a recursive type", typeOf<Node>(), Node("root", listOf(Node("leaf", emptyList())))),
        Shape(
            "a value class and a map of nullable lists",
            typeOf<Wrapped>(),
            Wrapped(UserId(7), mapOf("a" to listOf(1L, null))),
        ),
        Shape("an enum and a double", typeOf<Scored>(), Scored(Level.HIGH, 0.5)),
        Shape("a list of unions", typeOf<List<State>>(), listOf(Draft("ada"), Published("y"))),
    )

    private val sources = listOf(
        "JacksonCodecs" to JacksonCodecs,
        "KotlinxCodecs" to KotlinxCodecs,
        "JsoniterCodecs" to JsoniterCodecs,
    )

    // -------------------------------------------------------------- the claim

    @TestFactory
    fun `every codec's own bytes satisfy its own published schema`(): List<DynamicTest> =
        sources.flatMap { (library, codecs) ->
            shapes.map { shape ->
                DynamicTest.dynamicTest("$library: ${shape.name}") {
                    val written = write(codecs, shape)
                    val errors = validate(StandaloneSchemas(codecs).schema(shape.type).render(), written)

                    withClue("$library wrote $written, and its own schema refuses it: $errors") {
                        errors.shouldBeEmpty()
                    }
                }
            }
        }

    /**
     * The harness, checked against a schema that is wrong on purpose.
     *
     * Every claim above passes today, so without this there is no evidence the
     * validator is looking at anything: a `getSchema` that silently accepted
     * everything would be indistinguishable from three codecs that agree.
     */
    @Test
    fun `a schema that contradicts the bytes is caught`() {
        val wrong = """{"type":"object","properties":{"id":{"type":"string"}},"required":["id","nope"]}"""
        val written = write(JacksonCodecs, shapes.first())

        withClue("the validator accepted $written against a schema that forbids it") {
            validate(wrong, written).shouldNotBeEmpty()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun write(codecs: Codecs, shape: Shape): String =
        codecs.codec<Any>(shape.type).encodeToString(shape.value)

    private fun validate(schema: String, instance: String): List<String> {
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        return factory.getSchema(schema)
            .validate(mapper.readTree(instance))
            .map { it.message }
    }
}
