package io.github.matthewjones372.pelican.importer

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The document spec 0063's server publishes, read back in.
 *
 * Pelican writes two tagged failures under one status as one response whose
 * schema is a `oneOf` with a `discriminator`, and the importer already has a
 * reading for exactly that shape: a sealed payload type whose branches the
 * discriminator names. So the status comes back as one `errorJson` carrying
 * that type rather than as two tagged declarations — a different spelling of
 * the same contract, and the one the importer can state without a tag of its
 * own. Here so that a change to either half has to notice the other.
 */
class TaggedFailuresImportTest {

    private val adoption = document(
        paths = """
        /pets/{petId}/adoption:
          post:
            operationId: adoptPet
            parameters:
              - { name: petId, in: path, required: true, schema: { type: integer, format: int64 } }
            responses:
              "200":
                description: adopted
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/Pet' }
              "503":
                description: The sale could not be recorded; The registry could not be reached
                content:
                  application/json:
                    schema:
                      oneOf:
                        - { ${'$'}ref: '#/components/schemas/NotRecorded' }
                        - { ${'$'}ref: '#/components/schemas/RegistryDown' }
                      discriminator:
                        propertyName: kind
                        mapping:
                          not_recorded: '#/components/schemas/NotRecorded'
                          registry_down: '#/components/schemas/RegistryDown'
        """,
        components = """
        Pet:
          type: object
          properties: { id: { type: integer, format: int64 } }
          required: [id]
        NotRecorded:
          type: object
          properties: { attempt: { type: integer } }
          required: [attempt]
        RegistryDown:
          type: object
          properties: { retryIn: { type: integer } }
          required: [retryIn]
        """,
    )

    @Test
    fun `one status is one declaration, carrying the union of what it can hold`() {
        val generated = imported(adoption)

        generated shouldContain "val adoptPetFailureUnavailable = errorJson<AdoptPetFailure>(503,"
        generated shouldContain "sealed interface AdoptPetFailure"
        generated shouldContain "data class NotRecorded("
        generated shouldContain "data class RegistryDown("
        generated shouldContain ") : AdoptPetFailure"
    }

    /** The tags, kept where a codec reads them: the field the discriminator named. */
    @Test
    fun `each tag survives as the wire name of its branch`() {
        val generated = imported(adoption)

        generated shouldContain """property = "kind""""
        generated shouldContain """JsonSubTypes.Type(value = NotRecorded::class, name = "not_recorded")"""
        generated shouldContain """JsonSubTypes.Type(value = RegistryDown::class, name = "registry_down")"""
    }

    /**
     * Not two declarations under one status, which would need the tag to be
     * part of the description rather than part of the payload's codec.
     */
    @Test
    fun `the status is not declared twice`() {
        imported(adoption) shouldNotContain ".tagged("
    }
}
