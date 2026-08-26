package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.codegen.CodecAnnotations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class UnionsTest {

    private fun union(discriminator: String = "propertyName: kind"): String =
        """
        openapi: 3.1.0
        info: { title: Payments, version: "1.0.0" }
        components:
          schemas:
            Card:
              type: object
              properties:
                kind: { type: string }
                number: { type: string }
              required: [kind, number]
            Bank:
              type: object
              properties:
                kind: { type: string }
                iban: { type: string }
              required: [kind, iban]
            Payment:
              oneOf:
                - { ${'$'}ref: '#/components/schemas/Card' }
                - { ${'$'}ref: '#/components/schemas/Bank' }
              discriminator: { $discriminator }
        paths:
          /payments:
            post:
              operationId: pay
              requestBody:
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/Payment' }
              responses:
                "204": { description: ok }
        """

    @Test
    fun `a discriminated union becomes a sealed interface and one class per branch`() {
        val generated = imported(union())

        generated shouldContain "sealed interface Payment"
        generated shouldContain "data class Card("
        generated shouldContain "data class Bank("
        generated shouldContain ") : Payment"
    }

    /**
     * The property the discriminator names is not a property of the branch.
     * The hierarchy carries it, and a branch declaring it as well would be two
     * places holding one value — which kotlinx.serialization refuses outright
     * and Jackson would happily let disagree.
     */
    @Test
    fun `the discriminator is carried by the hierarchy, not by the branches`() {
        val generated = imported(union())

        generated shouldContain "val number: String,"
        generated shouldNotContain "val kind: String,"
    }

    /** What a codec needs to read the branch back, written where a reader can see it. */
    @Test
    fun `the wire value of each branch is in the generated source`() {
        val generated = imported(union())

        generated shouldContain """property = "kind""""
        generated shouldContain """JsonSubTypes.Type(value = Card::class, name = "Card")"""
        generated shouldContain "import com.fasterxml.jackson.annotation.JsonSubTypes"
    }

    @Test
    fun `a mapping key names the branch, and names it everywhere the branch is used`() {
        val generated = imported(
            union(
                "propertyName: kind, mapping: { " +
                    "card: '#/components/schemas/Card', bank_transfer: '#/components/schemas/Bank' }",
            ),
        )

        generated shouldContain """JsonSubTypes.Type(value = BankTransfer::class, name = "bank_transfer")"""
        generated shouldContain "data class BankTransfer("
        generated shouldNotContain "data class Bank("
    }

    @Test
    fun `an inline branch falls back to a positional name`() {
        val generated = imported(
            """
            openapi: 3.1.0
            info: { title: Events, version: "1.0.0" }
            components:
              schemas:
                Event:
                  oneOf:
                    - type: object
                      properties:
                        at: { type: string, const: created }
                        id: { type: string }
                    - type: object
                      properties:
                        at: { type: string, const: deleted }
                        reason: { type: string }
                  discriminator: { propertyName: at }
            paths:
              /events:
                get:
                  operationId: latest
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema: { ${'$'}ref: '#/components/schemas/Event' }
            """,
        )

        generated shouldContain "data class EventVariant1("
        generated shouldContain "data class EventVariant2("
        generated shouldContain """name = "created""""
    }

    /**
     * The other spelling of the same hierarchy, and the one swagger-core still
     * writes — which makes it the one a document Pelican itself published from
     * annotated Jackson classes uses.
     */
    @Test
    fun `a hierarchy written as 3_0 wrote one is read as the union it is`() {
        val generated = imported(
            """
            openapi: 3.1.0
            info: { title: Payments, version: "1.0.0" }
            components:
              schemas:
                Payment:
                  properties: { kind: { type: string } }
                  required: [kind]
                  discriminator: { propertyName: kind }
                Card:
                  allOf:
                    - { ${'$'}ref: '#/components/schemas/Payment' }
                    - type: object
                      properties: { number: { type: string } }
                      required: [number]
                Bank:
                  allOf:
                    - { ${'$'}ref: '#/components/schemas/Payment' }
                    - type: object
                      properties: { iban: { type: string } }
                      required: [iban]
            paths:
              /payments:
                post:
                  operationId: pay
                  requestBody:
                    content:
                      application/json:
                        schema: { ${'$'}ref: '#/components/schemas/Payment' }
                  responses:
                    "204": { description: ok }
            """,
        )

        generated shouldContain "sealed interface Payment"
        generated shouldContain "data class Card("
        generated shouldContain "val number: String,"
        generated shouldNotContain "val kind: String,"
        generated shouldContain """JsonSubTypes.Type(value = Card::class, name = "Card")"""
    }

    /** The names are the document's, so the same document produces the same file. */
    @Test
    fun `the same document generates the same names twice`() {
        imported(union()) shouldBe imported(union())
    }

    @Test
    fun `several schemas merged into one become one class`() {
        val generated = imported(
            """
            openapi: 3.1.0
            info: { title: Orders, version: "1.0.0" }
            components:
              schemas:
                Timestamps:
                  type: object
                  properties: { createdAt: { type: string, format: date-time } }
                  required: [createdAt]
                Order:
                  allOf:
                    - { ${'$'}ref: '#/components/schemas/Timestamps' }
                    - type: object
                      properties: { id: { type: string } }
                      required: [id]
            paths:
              /orders:
                get:
                  operationId: latest
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema: { ${'$'}ref: '#/components/schemas/Order' }
            """,
        )

        generated shouldContain "data class Order("
        generated shouldContain "val createdAt: String,"
        generated shouldContain "val id: String,"
    }

    @Test
    fun `a union written at the endpoint adopts the components it names`() {
        val generated = imported(
            """
            openapi: 3.1.0
            info: { title: Payments, version: "1.0.0" }
            components:
              schemas:
                Card: { type: object, properties: { number: { type: string } }, required: [number] }
                Bank: { type: object, properties: { iban: { type: string } }, required: [iban] }
            paths:
              /payments:
                get:
                  operationId: latest
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema:
                            oneOf:
                              - { ${'$'}ref: '#/components/schemas/Card' }
                              - { ${'$'}ref: '#/components/schemas/Bank' }
                            discriminator: { propertyName: kind }
            """,
        )

        generated shouldContain "sealed interface LatestResponse"
        generated shouldContain "data class Card("
        generated shouldContain ") : LatestResponse"
        generated shouldContain "json<LatestResponse>()"
    }

    // ------------------------------------------------------- still refused

    private fun refusing(yaml: String): String =
        shouldThrow<ImportFailure> { imported(yaml) }.message.orEmpty()

    @Test
    fun `merged schemas that disagree about a property are refused rather than resolved`() {
        val message = refusing(
            """
            openapi: 3.1.0
            info: { title: Orders, version: "1.0.0" }
            components:
              schemas:
                Order:
                  allOf:
                    - type: object
                      properties: { id: { type: string } }
                    - type: object
                      properties: { id: { type: integer } }
            paths:
              /orders:
                get:
                  operationId: latest
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema: { ${'$'}ref: '#/components/schemas/Order' }
            """,
        )

        message shouldContain "they disagree about `id`"
        message shouldContain "components.schemas.Order.allOf"
    }

    /** A build that overflowed its stack on a malformed document would say nothing useful. */
    @Test
    fun `a schema that merges itself is refused rather than followed forever`() {
        val message = refusing(
            """
            openapi: 3.1.0
            info: { title: Orders, version: "1.0.0" }
            components:
              schemas:
                Order:
                  allOf:
                    - { ${'$'}ref: '#/components/schemas/Order' }
                    - type: object
                      properties: { id: { type: string } }
            paths:
              /orders:
                get:
                  operationId: latest
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema: { ${'$'}ref: '#/components/schemas/Order' }
            """,
        )

        message shouldContain "a merge that includes itself"
    }

    @Test
    fun `a union with nothing saying which branch a payload is says so`() {
        val message = refusing(
            """
            openapi: 3.1.0
            info: { title: Payments, version: "1.0.0" }
            components:
              schemas:
                Payment:
                  oneOf:
                    - { type: object, properties: { card: { type: string } } }
                    - { type: object, properties: { iban: { type: string } } }
            paths:
              /payments:
                post:
                  operationId: pay
                  requestBody:
                    content:
                      application/json:
                        schema: { ${'$'}ref: '#/components/schemas/Payment' }
                  responses:
                    "204": { description: ok }
            """,
        )

        message shouldContain "add a `discriminator`"
    }

    @Test
    fun `anyOf of several branches is refused, and the message says what it would have cost`() {
        val message = refusing(
            """
            openapi: 3.1.0
            info: { title: Payments, version: "1.0.0" }
            components:
              schemas:
                Payment:
                  anyOf:
                    - { type: object, properties: { card: { type: string } } }
                    - { type: object, properties: { iban: { type: string } } }
            paths:
              /payments:
                post:
                  operationId: pay
                  requestBody:
                    content:
                      application/json:
                        schema: { ${'$'}ref: '#/components/schemas/Payment' }
                  responses:
                    "204": { description: ok }
            """,
        )

        message shouldContain "satisfy two branches at once"
        message shouldContain "components.schemas.Payment.anyOf"
    }

    @Test
    fun `a union whose branch is itself a union is refused, and the message says how to flatten it`() {
        val message = refusing(
            """
            openapi: 3.1.0
            info: { title: Payments, version: "1.0.0" }
            components:
              schemas:
                Cash: { type: object, properties: { amount: { type: integer } }, required: [amount] }
                Card: { type: object, properties: { number: { type: string } }, required: [number] }
                Bank: { type: object, properties: { iban: { type: string } }, required: [iban] }
                Electronic:
                  oneOf:
                    - { ${'$'}ref: '#/components/schemas/Card' }
                    - { ${'$'}ref: '#/components/schemas/Bank' }
                  discriminator:
                    propertyName: type
                    mapping: { card: '#/components/schemas/Card', bank: '#/components/schemas/Bank' }
                Payment:
                  oneOf:
                    - { ${'$'}ref: '#/components/schemas/Cash' }
                    - { ${'$'}ref: '#/components/schemas/Electronic' }
                  discriminator:
                    propertyName: kind
                    mapping: { cash: '#/components/schemas/Cash', electronic: '#/components/schemas/Electronic' }
            paths:
              /payments:
                post:
                  operationId: pay
                  requestBody:
                    content:
                      application/json:
                        schema: { ${'$'}ref: '#/components/schemas/Payment' }
                  responses:
                    "204": { description: ok }
            """,
        )

        message shouldContain "components.schemas.Payment.oneOf"
        message shouldContain "`electronic`"
        message shouldContain "spread over two properties"
        message shouldContain "one `oneOf` listing every leaf schema"
    }

    /** The same nesting in the spelling 3.0 had for a hierarchy, refused the same way. */
    @Test
    fun `a nested hierarchy written as 3_0 wrote one is refused too`() {
        val message = refusing(
            """
            openapi: 3.1.0
            info: { title: Payments, version: "1.0.0" }
            components:
              schemas:
                Payment:
                  properties: { kind: { type: string } }
                  discriminator: { propertyName: kind }
                Electronic:
                  allOf:
                    - { ${'$'}ref: '#/components/schemas/Payment' }
                    - type: object
                      properties: { type: { type: string } }
                  discriminator: { propertyName: type }
                Card:
                  allOf:
                    - { ${'$'}ref: '#/components/schemas/Electronic' }
                    - type: object
                      properties: { number: { type: string } }
            paths:
              /payments:
                post:
                  operationId: pay
                  requestBody:
                    content:
                      application/json:
                        schema: { ${'$'}ref: '#/components/schemas/Payment' }
                  responses:
                    "204": { description: ok }
            """,
        )

        message shouldContain "branch `Electronic` is itself a union"
        message shouldContain "components.schemas.Payment.discriminator"
    }

    @Test
    fun `a discriminator with no branches beneath it names the shape the document should have`() {
        val message = refusing(
            """
            openapi: 3.1.0
            info: { title: Payments, version: "1.0.0" }
            components:
              schemas:
                Payment:
                  type: object
                  properties: { kind: { type: string } }
                  discriminator: { propertyName: kind }
            paths:
              /payments:
                post:
                  operationId: pay
                  requestBody:
                    content:
                      application/json:
                        schema: { ${'$'}ref: '#/components/schemas/Payment' }
                  responses:
                    "204": { description: ok }
            """,
        )

        message shouldContain "no branches to discriminate"
    }

    // ------------------------------------------------------------ the codecs

    @Test
    fun `chosen for kotlinx, the payload types carry the annotations kotlinx needs`() {
        val generated = imported(
            union(),
            importOptions("app", "test") { codec = CodecAnnotations.KOTLINX },
        )

        generated shouldContain "@Serializable"
        generated shouldContain """@JsonClassDiscriminator("kind")"""
        generated shouldContain """@SerialName("Card")"""
        generated shouldContain "import kotlinx.serialization.json.JsonClassDiscriminator"
        generated shouldNotContain "com.fasterxml.jackson"
    }

    @Test
    fun `chosen for jackson, nothing kotlinx needs is written`() {
        val generated = imported(union())

        generated shouldNotContain "kotlinx.serialization"
        generated shouldContain "@JsonTypeInfo"
    }
}
