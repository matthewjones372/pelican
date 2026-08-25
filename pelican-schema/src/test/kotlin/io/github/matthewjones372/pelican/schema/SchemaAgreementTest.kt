package io.github.matthewjones372.pelican.schema

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonBool
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.emptyJsonObj
import io.github.matthewjones372.pelican.formCodec
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsoniter.JsoniterCodecs
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
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

    @Serializable
    data class Signup(val name: String, val visits: Int?)

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
     * The inverse claim, and the one the encode direction cannot make: a caller
     * who sends exactly what the schema asks for and nothing more has to be
     * read. What the schema calls optional is where the three libraries
     * disagree, so the payload is built from the schema alone rather than from
     * a value one of them wrote.
     */
    @TestFactory
    fun `every codec reads the smallest payload its own published schema accepts`(): List<DynamicTest> =
        sources.flatMap { (library, codecs) ->
            shapes.map { shape ->
                DynamicTest.dynamicTest("$library: ${shape.name}") {
                    val document = StandaloneSchemas(codecs).schema(shape.type)
                    val minimal = document.smallestPayload().render()

                    withClue("$minimal is not a payload $library's own schema accepts, so this asserts nothing") {
                        validate(document.render(), minimal).shouldBeEmpty()
                    }

                    val refusal = runCatching { codecs.codec<Any>(shape.type).decodeFromString(minimal) }
                        .exceptionOrNull()
                    withClue("$library published a schema that accepts $minimal, and refuses it: $refusal") {
                        refusal shouldBe null
                    }
                }
            }
        }

    /**
     * The one place a published schema decides how a request is *read* rather
     * than how it is documented, so a disagreement here is a 400. All three
     * sources spell a nullable Int the 3.1 way, as a type array.
     */
    @TestFactory
    fun `a form field its own schema calls a nullable integer arrives as an integer`(): List<DynamicTest> =
        sources.map { (library, codecs) ->
            DynamicTest.dynamicTest(library) {
                val codec = codecs.formCodec<Signup>(typeOf<Signup>())

                withClue("$library read visits=3 as something other than the 3 its schema describes") {
                    codec.decodeFromString("name=ada&visits=3") shouldBe Signup("ada", 3)
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

// ------------------------------------------------------------------ helpers

/**
 * The smallest payload this document's root accepts: the required properties
 * and no others, each at the value the schema pins or an arbitrary one of the
 * type it names.
 *
 * Written from the schema alone, which is the point — a property the schema
 * calls optional is a property a caller following the document leaves out.
 */
private fun JsonObj.smallestPayload(): JsonValue = sampleOf(this)

private fun JsonObj.sampleOf(schema: JsonObj): JsonValue =
    schema["const"]
        ?: (schema["\$ref"] as? JsonStr)?.let { sampleOf(definition(it.value)) }
        ?: (schema["enum"] as? JsonArr)?.items?.firstOrNull()
        // A `oneOf` is a union and an `anyOf` is how a nullable `$ref` is spelled;
        // either way the first branch is a value the schema accepts.
        ?: ((schema["oneOf"] ?: schema["anyOf"]) as? JsonArr)?.items?.firstOrNull()
            ?.let { sampleOf(it as? JsonObj ?: emptyJsonObj) }
        ?: byType(schema)

private fun JsonObj.byType(schema: JsonObj): JsonValue = when (schema.typeName()) {
    "object" -> requiredOf(schema)
    "array" -> JsonArr(emptyList())
    "integer", "number" -> JsonNum(1)
    "boolean" -> JsonBool(true)
    else -> JsonStr("x")
}

private fun JsonObj.requiredOf(schema: JsonObj): JsonObj {
    val properties = (schema["properties"] as? JsonObj)?.fields.orEmpty()
    val required = ((schema["required"] as? JsonArr)?.items.orEmpty()).mapNotNull { (it as? JsonStr)?.value }
    return JsonObj(
        required.mapNotNull { name -> (properties[name] as? JsonObj)?.let { name to sampleOf(it) } }.toMap(),
    )
}

private fun JsonObj.definition(pointer: String): JsonObj =
    (this["\$defs"] as? JsonObj)?.get(pointer.substringAfterLast('/')) as? JsonObj ?: emptyJsonObj

/** What a schema calls itself, reading past the `"null"` a 3.1 type array adds. */
private fun JsonObj.typeName(): String? = when (val type = this["type"]) {
    is JsonStr -> type.value
    is JsonArr -> type.items.filterIsInstance<JsonStr>().map { it.value }.firstOrNull { it != "null" }
    else -> null
}
