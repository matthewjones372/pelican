package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Two failures under one status, published as one response whose schema is a `oneOf` discriminated
 * by the tag. The point of writing the tag into the body is that a client outside Pelican can
 * branch on it, and the document is the only thing that tells such a client the tag exists.
 *
 * Schemas are hand-written here, as in [SeveralResponsesTest], so nothing needs a codec module.
 */
class TaggedFailuresDocumentTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(name, jsonObj { "type" to "object" })
            }
            return components.ref(name)
        }
    }

    data class Pet(val id: Long)

    data class NotRecorded(val attempt: Int)

    data class RegistryDown(val retryIn: Int)

    private val petId = pathParam<Long>("petId")

    private val notRecorded = errorJson<NotRecorded>(503, "The sale could not be recorded")
        .tagged("not_recorded")

    private val registryDown = errorJson<RegistryDown>(503, "The chip registry could not be reached")
        .tagged("registry_down")

    private val adoptPet = endpoint(petId) {
        post("pets" / petId / "adoption")
        json<Pet>().orFail(notRecorded, registryDown)
    }

    private fun doc(): JsonObj = apiSpec(listOf(adoptPet), Schemas).openApi()

    private fun responsesOf(doc: JsonObj) = doc / "paths" / "/pets/{petId}/adoption" / "post" / "responses"

    private fun unavailable() = responsesOf(doc()) / "503"

    private fun schema() = unavailable() / "content" / "application/json" / "schema"

    @Test
    fun `one status, one response entry`() {
        responsesOf(doc()).keys() shouldContainExactly setOf("200", "503", "default")
    }

    @Test
    fun `both schemas are named under that one status`() {
        (schema() / "oneOf").arr().map { (it / "\$ref").str() } shouldContainExactly listOf(
            "#/components/schemas/NotRecorded",
            "#/components/schemas/RegistryDown",
        )
    }

    @Test
    fun `the discriminator maps each tag to its schema`() {
        val discriminator = schema() / "discriminator"

        (discriminator / "propertyName").str() shouldBe "kind"
        (discriminator / "mapping" / "not_recorded").str() shouldBe "#/components/schemas/NotRecorded"
        (discriminator / "mapping" / "registry_down").str() shouldBe "#/components/schemas/RegistryDown"
        (discriminator / "mapping").keys() shouldContainExactly setOf("not_recorded", "registry_down")
    }

    @Test
    fun `the status lists each tag with what its failure said`() {
        (unavailable() / "description").str() shouldBe
            "One of:\n\n" +
            "- `not_recorded`: The sale could not be recorded\n" +
            "- `registry_down`: The chip registry could not be reached"
    }

    @Test
    fun `each tagged schema carries its own failure's description`() {
        (doc() / "components" / "schemas" / "NotRecorded" / "description").str() shouldBe
            "The sale could not be recorded"
        (doc() / "components" / "schemas" / "RegistryDown" / "description").str() shouldBe
            "The chip registry could not be reached"
    }

    @Test
    fun `a description the type already gives its schema is kept`() {
        val described = object : SchemaSource {
            override fun schema(type: KType, components: SchemaComponents): JsonObj {
                val name = (type.classifier as KClass<*>).simpleName!!
                components.register(name, jsonObj { "type" to "object"; "description" to "The type's own words" })
                return components.ref(name)
            }
        }

        (apiSpec(listOf(adoptPet), described).openApi() / "components" / "schemas" / "NotRecorded" / "description")
            .str() shouldBe "The type's own words"
    }

    @Test
    fun `a single failure's status keeps its description as it was`() {
        val only = endpoint(petId) {
            post("pets" / petId / "adoption")
            json<Pet>().orFail(errorJson<NotRecorded>(503, "The sale could not be recorded"))
        }

        (responsesOf(apiSpec(listOf(only), Schemas).openApi()) / "503" / "description").str() shouldBe
            "The sale could not be recorded"
    }

    @Test
    fun `each tagged schema declares its tag, first and required, as the body carries it`() {
        val notRecorded = doc() / "components" / "schemas" / "NotRecorded"

        (notRecorded / "properties").keys().first() shouldBe "kind"
        (notRecorded / "properties" / "kind" / "const").str() shouldBe "not_recorded"
        (notRecorded / "required").arr().map { it.str() } shouldContainExactly listOf("kind")
        (doc() / "components" / "schemas" / "RegistryDown" / "properties" / "kind" / "const").str() shouldBe
            "registry_down"
    }

    @Test
    fun `an untagged failure's schema is left as its type describes it`() {
        val only = endpoint(petId) {
            post("pets" / petId / "adoption")
            json<Pet>().orFail(errorJson<NotRecorded>(503, "The sale could not be recorded"))
        }

        (apiSpec(listOf(only), Schemas).openApi() / "components" / "schemas" / "NotRecorded").keys() shouldBe
            setOf("type")
    }

    @Test
    fun `a type tagged in one place and plain in another is refused, since one schema cannot be both`() {
        val lookUp = endpoint(petId) {
            get("pets" / petId / "adoption")
            json<NotRecorded>()
        }

        shouldThrow<IllegalArgumentException> { apiSpec(listOf(adoptPet, lookUp), Schemas).openApi() }
            .message shouldContain "NotRecorded is tagged `not_recorded` as a failure, and also used where it is not"
    }

    @Test
    fun `a type under two tags is refused, since its schema carries one`() {
        // Declared outside the builder, whose own errorJson adds an untagged failure as it is called.
        val unrecorded = errorJson<NotRecorded>(503, "Not recorded").tagged("unrecorded")
        val returnPet = endpoint(petId) {
            post("pets" / petId / "return")
            json<Pet>().orFail(unrecorded, registryDown)
        }

        shouldThrow<IllegalArgumentException> { apiSpec(listOf(adoptPet, returnPet), Schemas).openApi() }
            .message shouldContain "NotRecorded is tagged `not_recorded` under `kind` and `unrecorded` under `kind`"
    }

    @Test
    fun `a payload with a field of the tag's name is refused rather than overwritten`() {
        val withKind = object : SchemaSource {
            override fun schema(type: KType, components: SchemaComponents): JsonObj {
                val name = (type.classifier as KClass<*>).simpleName!!
                components.register(
                    name,
                    jsonObj {
                        "type" to "object"
                        put("properties", jsonObj { put("kind", jsonObj { "type" to "string" }) })
                        put("required", jsonStrings(listOf("kind")))
                    },
                )
                return components.ref(name)
            }
        }

        shouldThrow<IllegalArgumentException> { apiSpec(listOf(adoptPet), withKind).openApi() }
            .message shouldContain "already has a field `kind`"
    }

    @Test
    fun `a single failure under a status is still one schema, not a oneOf of one`() {
        val only = endpoint(petId) {
            post("pets" / petId / "adoption")
            json<Pet>().orFail(errorJson<NotRecorded>(503, "The sale could not be recorded"))
        }
        val schema = responsesOf(apiSpec(listOf(only), Schemas).openApi()) /
            "503" / "content" / "application/json" / "schema"

        (schema / "\$ref").str() shouldBe "#/components/schemas/NotRecorded"
    }
}
