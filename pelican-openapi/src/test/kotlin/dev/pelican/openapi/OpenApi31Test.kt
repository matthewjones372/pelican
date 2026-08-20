package dev.pelican.openapi

import dev.pelican.*
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * The document says 3.1, and then behaves like it.
 *
 * Bumping the version string is the easy half; the half that matters is that
 * nothing 3.0-shaped survives underneath it. Each test here pins one keyword
 * that changed meaning or disappeared between the two versions, because a
 * document that claims 3.1 and carries `nullable: true` is worse than one that
 * honestly claimed 3.0 — a 3.1 validator ignores the keyword rather than
 * complaining, so the field silently stops being nullable.
 */
class OpenApi31Test {

    /**
     * Stands in for a codec module, and returns the shapes core's `orNull`
     * produces — so this module's tests can assert on 3.1 nullability without
     * acquiring a dependency on Jackson or kotlinx to generate it.
     */
    object PartSchemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(
                    name,
                    jsonObj {
                        "type" to "object"
                        put(
                            "properties",
                            jsonObj {
                                put("id", jsonObj { "type" to "integer" })
                                // What used to be `type: string, nullable: true`.
                                put("note", jsonObj { "type" to "string" }.orNull())
                                // What 3.0 could not express at all.
                                put("parent", components.ref(name).orNull())
                            },
                        )
                        put("required", jsonStrings(listOf("id", "note")))
                    },
                )
            }
            return components.ref(name)
        }
    }

    data class Part(val id: Long, val note: String?, val parent: Part?)

    private val quantity = queryParam("quantity", IntCodec.positive(), description = "How many")
    private val upload = rawBody()

    private val listParts = endpoint(quantity) {
        get("parts")
        json<Part>()
    }

    private val uploadPart = endpoint(upload) {
        post("parts" / "blueprint")
        bytes(mediaType = "image/png")
    }

    private fun doc(): JsonObj =
        ApiSpec(listOf(listParts, uploadPart), PartSchemas, title = "Parts").openApi()

    @Test
    fun `the document declares OpenAPI 3_1_0`() {
        (doc() / "openapi").str() shouldBe "3.1.0"
    }

    @Test
    fun `a nullable property is a type union rather than the 3_0 nullable keyword`() {
        val part = doc() / "components" / "schemas" / "Part" / "properties"
        (part / "note" / "type").strings() shouldBe listOf("string", "null")
        (part / "note" / "nullable").shouldBeNull()
    }

    @Test
    fun `a nullable reference is an anyOf, which is the one nullability 3_0 could not state`() {
        val parent = doc() / "components" / "schemas" / "Part" / "properties" / "parent"
        val branches = (parent / "anyOf").arr()
        (branches[0] / "\$ref").str() shouldBe "#/components/schemas/Part"
        (branches[1] / "type").str() shouldBe "null"
    }

    @Test
    fun `an exclusive bound is the bound itself, not a boolean modifying another keyword`() {
        val schema = doc() / "paths" / "/parts" / "get" / "parameters"
        val quantity = schema.arr().single { (it / "name").str() == "quantity" }
        // 3.0 spelled this `minimum: 0, exclusiveMinimum: true`, so a `minimum`
        // beside it would mean the 3.0 pair had been emitted after all.
        quantity / "schema" / "exclusiveMinimum" shouldBe JsonNum(0)
        (quantity / "schema" / "minimum").shouldBeNull()
    }

    @Test
    fun `opaque bytes carry a content media type rather than format binary`() {
        val document = doc()
        val request = document / "paths" / "/parts/blueprint" / "post" / "requestBody" /
            "content" / "application/octet-stream" / "schema"
        (request / "contentMediaType").str() shouldBe "application/octet-stream"

        // The response names what it actually streams, which `format: binary`
        // had no way to say.
        val response = document / "paths" / "/parts/blueprint" / "post" / "responses" / "200" /
            "content" / "image/png" / "schema"
        (response / "contentMediaType").str() shouldBe "image/png"
    }

    @Test
    fun `no 3_0-only keyword survives anywhere in the document`() {
        val rendered = doc().render()
        listOf("nullable", "\"format\":\"binary\"").forEach {
            withClue("$it is 3.0's, and this document claims 3.1:\n$rendered") { rendered shouldNotContain it }
        }
    }
}
