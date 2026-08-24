package io.github.matthewjones372.pelican.importer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * References, followed as far as the disk and no further.
 *
 * A document split across files is ordinary once it is large, so those are
 * read. A document that references another host is not read, and that is the
 * decision worth testing: a build that fetches a URL to know what to generate
 * produces different code on a different day, and no amount of convenience is
 * worth a generated client nobody can reproduce.
 *
 * It is the default rather than the only answer — `allowRemote` names a host
 * on purpose and a lockfile pins what it served — and `RemoteReferencesTest`
 * is where that bargain is asserted. What is asserted here is that nobody who
 * has not made it gets any of it.
 */
class ReferencesTest {

    @Test
    fun `a schema in another file keeps the name it had there`() {
        val document = documentOf(
            "openapi.yaml" to """
                openapi: 3.1.0
                info: { title: T, version: "1" }
                paths:
                  /widgets:
                    get:
                      operationId: listWidgets
                      responses:
                        "200":
                          description: ok
                          content:
                            application/json:
                              schema: { ${'$'}ref: './common.yaml#/components/schemas/Widget' }
            """,
            "common.yaml" to """
                components:
                  schemas:
                    Widget:
                      type: object
                      required: [id]
                      properties:
                        id: { type: string }
                        colour: { ${'$'}ref: '#/components/schemas/Colour' }
                    Colour:
                      type: string
                      enum: [red, green]
            """,
        )

        val generated = imported(document)
        generated shouldContain "data class Widget("
        // The nested reference was relative to the file it was written in, and
        // came back with its own name rather than one invented from where it
        // was used.
        generated shouldContain "enum class Colour { red, green }"
        generated shouldContain "val colour: Colour? = null"
    }

    @Test
    fun `a shared parameter is resolved, and the endpoint reads as if it were written out`() {
        val document = documentOf(
            "openapi.yaml" to """
                openapi: 3.1.0
                info: { title: T, version: "1" }
                components:
                  parameters:
                    Limit:
                      name: limit
                      in: query
                      required: false
                      schema: { type: integer, format: int32 }
                paths:
                  /widgets:
                    get:
                      operationId: listWidgets
                      parameters:
                        - { ${'$'}ref: '#/components/parameters/Limit' }
                      responses:
                        "204": { description: ok }
            """,
        )
        imported(document) shouldContain """val limit = queryParam<Int>("limit").optional()"""
    }

    @Test
    fun `a reference to another host is refused rather than fetched`() {
        val document = documentOf(
            "openapi.yaml" to """
                openapi: 3.1.0
                info: { title: T, version: "1" }
                paths:
                  /widgets:
                    get:
                      operationId: listWidgets
                      responses:
                        "200":
                          description: ok
                          content:
                            application/json:
                              schema: { ${'$'}ref: 'https://example.com/schemas.yaml#/Widget' }
            """,
        )
        val message = shouldThrow<ImportFailure> { imported(document) }.message.orEmpty()
        message shouldContain "on another host"
        message shouldContain "cannot be reproduced"
        // Three ways out and not two: the refusal points at the one that keeps
        // the reference, so nobody has to find it by reading the manual.
        message shouldContain "Bundle the document first"
        message shouldContain "allowRemote"
    }

    @Test
    fun `a path item's own parameters apply to every operation on it`() {
        val generated = imported(
            document(
                """
                /widgets/{id}:
                  parameters:
                    - { name: id, in: path, required: true, schema: { type: string } }
                  get:
                    operationId: getWidget
                    responses:
                      "204": { description: ok }
                  delete:
                    operationId: deleteWidget
                    responses:
                      "204": { description: ok }
                """,
            ),
        )
        generated shouldContain """val id = pathParam<String>("id")"""
        generated shouldContain """get("widgets" / id)"""
        generated shouldContain """delete("widgets" / id)"""
    }

    @Test
    fun `a route that captures something nothing declares is refused here rather than at startup`() {
        val message = shouldThrow<ImportFailure> {
            imported(
                document(
                    """
                    /widgets/{id}:
                      get:
                        operationId: getWidget
                        responses:
                          "204": { description: ok }
                    """,
                ),
            )
        }.message.orEmpty()
        message shouldContain "The route captures {id}, and no parameter declares it"
    }
}
