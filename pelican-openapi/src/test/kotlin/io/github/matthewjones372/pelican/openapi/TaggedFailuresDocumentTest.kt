package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
    fun `the description keeps what each failure said`() {
        (unavailable() / "description").str() shouldBe
            "The sale could not be recorded; The chip registry could not be reached"
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
