package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Two failures under one status reach a generated client as two members, told
 * apart by the tag in the body. `TaggedFailuresClientTest` in `example` calls
 * the generated client against a running service; this one is about the source
 * it reads, where that one is about the answer it gives.
 */
class TaggedFailuresClientTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(
                    name,
                    jsonObj {
                        "type" to "object"
                        put("properties", jsonObj { put("id", jsonObj { "type" to "integer" }) })
                        put("required", jsonStrings(listOf("id")))
                    },
                )
            }
            return components.ref(name)
        }
    }

    data class Pet(val id: Long)
    data class NotRecorded(val id: Long)
    data class RegistryDown(val id: Long)

    private val petId = pathParam<Long>("petId")

    private val notRecorded = errorJson<NotRecorded>(503, "The sale could not be recorded")
        .tagged("not_recorded")

    private val registryDown = errorJson<RegistryDown>(503, "The chip registry could not be reached")
        .tagged("registry_down")

    private val adopt = endpoint(petId) {
        post("pets" / petId / "adoption")
        operationId = "adoptPet"
        json<Pet>().orFail(notRecorded, registryDown)
    }

    private val client = apiSpec(listOf(adopt), Schemas) { title = "Adoption" }.kotlinClient("com.example")

    @Test
    fun `each tag is its own member of the failure type`() {
        client shouldContain "sealed interface AdoptPetFailure {"
        client shouldContain "data class NotRecorded(val body: com.example.NotRecorded) : AdoptPetFailure {"
        client shouldContain "data class RegistryDown(val body: com.example.RegistryDown) : AdoptPetFailure {"
    }

    /** One `when` on the status, and inside it one on the tag. */
    @Test
    fun `the status is matched once and the tag decides which failure it was`() {
        client shouldContain "503 -> {"
        client shouldContain "when (tagIn(response.body, \"kind\")) {"
        client shouldContain "\"not_recorded\" -> return Outcome.Err(AdoptPetFailure.NotRecorded("
        client shouldContain "\"registry_down\" -> return Outcome.Err(AdoptPetFailure.RegistryDown("
    }

    /**
     * No `else`: an unclaimed tag falls out of both `when`s onto the
     * `failed(...)` the method already ends with, which is where an undeclared
     * body has always gone.
     */
    @Test
    fun `a tag no declaration claims is a failed call`() {
        client shouldContain "if (!response.succeeded()) failed(Method.POST, \"/pets/{petId}/adoption\", response)"
        client shouldNotContain "else -> return Outcome.Err"
    }

    @Test
    fun `the reader is imported only because something here is tagged`() {
        client shouldContain "import io.github.matthewjones372.pelican.spi.tagIn"
        apiSpec(listOf(untagged), Schemas) { title = "Adoption" }
            .kotlinClient("com.example") shouldNotContain "spi.tagIn"
    }

    /** The same endpoint with one plain failure, so the import has no reason to be there. */
    private val untagged = endpoint(petId) {
        post("pets" / petId / "adoption")
        operationId = "adoptPet"
        json<Pet>().orFail(errorJson<NotRecorded>(503, "The sale could not be recorded"))
    }

    /** With no tag the member is named after the status, exactly as before. */
    @Test
    fun `an untagged failure is still named after its status`() {
        val plain = apiSpec(listOf(untagged), Schemas) { title = "Adoption" }.kotlinClient("com.example")

        plain shouldContain "data class ServiceUnavailable(val body: NotRecorded) : AdoptPetFailure {"
        plain shouldContain "503 -> return Outcome.Err(AdoptPetFailure.ServiceUnavailable("
    }
}
