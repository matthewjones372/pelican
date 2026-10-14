package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.codegen.CodecAnnotations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class HintsTest {

    private fun hinted(yaml: String, vararg hints: Pair<String, String>): String =
        imported(yaml, importOptions("test", "test") { discriminators = hints.toMap() })

    private fun refusing(yaml: String, vararg hints: Pair<String, String>): String =
        shouldThrow<ImportFailure> { hinted(yaml, *hints) }.message.orEmpty()

    private fun payments(
        cards: String = PLAIN_CARDS,
        names: List<String> = listOf("Card", "Bank"),
        keyword: String = "oneOf",
        discriminator: String? = null,
    ): String = buildList {
        add("openapi: 3.1.0")
        add("""info: { title: Payments, version: "1.0.0" }""")
        add("components:")
        add("  schemas:")
        add(cards.prependIndent("    "))
        add("    Payment:")
        add("      $keyword:")
        names.forEach { add("        - { \$ref: '#/components/schemas/$it' }") }
        discriminator?.let { add("      discriminator: { $it }") }
        add("paths:")
        add("  /payments:")
        add("    post:")
        add("      operationId: pay")
        add("      requestBody:")
        add("        content:")
        add("          application/json:")
        add("            schema: { \$ref: '#/components/schemas/Payment' }")
        add("      responses:")
        add("""        "204": { description: ok }""")
    }.joinToString("\n")

    // --------------------------------------------------------- what it makes

    @Test
    fun `a hinted component union becomes the same sealed hierarchy a documented one would`() {
        val generated = hinted(payments(), "Payment" to "kind")

        generated shouldContain "sealed interface Payment"
        generated shouldContain "data class Card("
        generated shouldContain "data class Bank("
        generated shouldContain ") : Payment"
        generated shouldContain """property = "kind""""
    }

    @Test
    fun `the hinted discriminator is carried by the hierarchy, not by the branches`() {
        val generated = hinted(payments(), "Payment" to "kind")

        generated shouldContain "val number: String,"
        generated shouldNotContain "val kind: String,"
    }

    /**
     * A branch that declares nothing about the property is selected by the
     * name of the schema it points at — which is what OpenAPI defines an
     * implicit mapping to be, and the one name the document does give it.
     */
    @Test
    fun `a branch that states no value is selected by the name of the schema it points at`() {
        val generated = hinted(payments(), "Payment" to "kind")

        generated shouldContain """JsonSubTypes.Type(value = Card::class, name = "Card")"""
        generated shouldContain """JsonSubTypes.Type(value = Bank::class, name = "Bank")"""
    }

    /**
     * A `const` is the document stating the wire value, and it wins over the
     * schema's own name: OpenAPI's rule that an unmapped branch is selected by
     * its name only applies to a document that claimed a `discriminator`, and
     * this one never did. Publishing `CardPayment` where the document says
     * `card` would be confidently wrong rather than merely vague.
     */
    @Test
    fun `a const for the property is the wire value, and names the branch with it`() {
        val generated = hinted(payments(CONST_CARDS, names = listOf("CardPayment", "BankPayment")), "Payment" to "kind")

        generated shouldContain """JsonSubTypes.Type(value = Card::class, name = "card")"""
        generated shouldContain """JsonSubTypes.Type(value = BankTransfer::class, name = "bank_transfer")"""
        generated shouldContain "data class Card("
        generated shouldNotContain "data class CardPayment("
    }

    /** The other spelling of a constant, and the same fact. */
    @Test
    fun `a single-valued enum says what a const says`() {
        val generated = hinted(payments(ENUM_CARDS), "Payment" to "kind")

        generated shouldContain """JsonSubTypes.Type(value = Card::class, name = "card")"""
    }

    @Test
    fun `an inline union under a property is addressed by pointer`() {
        val generated = hinted(
            """
            openapi: 3.1.0
            info: { title: Orders, version: "1.0.0" }
            components:
              schemas:
                Card: { type: object, properties: { kind: { type: string }, number: { type: string } } }
                Bank: { type: object, properties: { kind: { type: string }, iban: { type: string } } }
                Order:
                  type: object
                  properties:
                    id: { type: string }
                    payment:
                      oneOf:
                        - { ${'$'}ref: '#/components/schemas/Card' }
                        - { ${'$'}ref: '#/components/schemas/Bank' }
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
            "Order/properties/payment" to "kind",
        )

        generated shouldContain "sealed interface OrderPayment"
        generated shouldContain ") : OrderPayment"
        generated shouldContain "val payment: OrderPayment? = null,"
    }

    /** A union written at the endpoint, where the address has to start at the root. */
    @Test
    fun `a union written at the endpoint is addressed by a pointer from the root`() {
        val generated = hinted(
            """
            openapi: 3.1.0
            info: { title: Payments, version: "1.0.0" }
            components:
              schemas:
                Card: { type: object, properties: { kind: { type: string }, number: { type: string } } }
                Bank: { type: object, properties: { kind: { type: string }, iban: { type: string } } }
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
            """,
            "#/paths/~1payments/get/responses/200/content/application~1json/schema" to "kind",
        )

        generated shouldContain "sealed interface LatestResponse"
        generated shouldContain "json<LatestResponse>()"
        generated shouldContain ") : LatestResponse"
    }

    @Test
    fun `the schemas the generated file publishes carry the discriminator and a full mapping`() {
        val generated = hinted(payments(CONST_CARDS, names = listOf("CardPayment", "BankPayment")), "Payment" to "kind")

        generated shouldContain "\\\"discriminator\\\":{\\\"propertyName\\\":\\\"kind\\\""
        generated shouldContain "\\\"card\\\":\\\"#/components/schemas/CardPayment\\\""
        generated shouldContain "\\\"bank_transfer\\\":\\\"#/components/schemas/BankPayment\\\""
    }

    @Test
    fun `a hint generates what the document stating it itself would have generated`() {
        hinted(payments(), "Payment" to "kind") shouldBe imported(
            payments(
                discriminator = "propertyName: kind, mapping: { " +
                    "Card: '#/components/schemas/Card', Bank: '#/components/schemas/Bank' }",
            ),
        )
    }

    @Test
    fun `a hinted union is annotated for kotlinx when kotlinx is what will read it`() {
        val generated = imported(
            payments(),
            importOptions("test", "test") {
                discriminators = mapOf("Payment" to "kind")
                codec = CodecAnnotations.KOTLINX
            },
        )

        generated shouldContain """@JsonClassDiscriminator("kind")"""
        generated shouldContain """@SerialName("Card")"""
        generated shouldNotContain "com.fasterxml.jackson"
    }

    /** A branch written inline states its own value, and the mapping has nothing to point at. */
    @Test
    fun `inline branches are selected by the const each declares`() {
        val generated = hinted(
            """
            openapi: 3.1.0
            info: { title: Events, version: "1.0.0" }
            components:
              schemas:
                Event:
                  oneOf:
                    - type: object
                      properties: { at: { type: string, const: created }, id: { type: string } }
                    - type: object
                      properties: { at: { type: string, const: deleted }, reason: { type: string } }
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
            "Event" to "at",
        )

        generated shouldContain "data class EventVariant1("
        generated shouldContain """name = "created""""
        generated shouldContain """name = "deleted""""
    }

    // ------------------------------------------------------- a wrong hint

    @Test
    fun `a hint addressing nothing says how a schema is addressed`() {
        val message = refusing(payments(), "Payments" to "kind")

        message shouldContain """discriminator("Payments", property = "kind")"""
        message shouldContain "at components.schemas.Payments"
        message shouldContain "There is no schema at that position"
    }

    @Test
    fun `a hint on something that is not a union says what is there instead`() {
        val message = refusing(payments(), "Card" to "kind")

        message shouldContain "there is no union here"
        message shouldContain "a schema with no `oneOf` in it"
    }

    /** `anyOf` stays refused, and a hint is not a way round it. */
    @Test
    fun `a hint on an anyOf does not turn it into a union`() {
        val message = refusing(
            payments(keyword = "anyOf"),
            "Payment" to "kind",
        )

        message shouldContain "an `anyOf`, which stays refused"
        message shouldContain "satisfy two of its branches at once"
    }

    @Test
    fun `a hint naming a property no branch declares lists the ones they do`() {
        val message = refusing(payments(), "Payment" to "knid")

        message shouldContain "No branch of this union declares `knid`"
        message shouldContain "the branches declare `kind`, `number`, `iban`"
        message shouldContain "Check the spelling"
    }

    @Test
    fun `a hint that gives two branches one wire value is refused`() {
        val message = refusing(payments(CLASHING_CARDS), "Payment" to "kind")

        message shouldContain "selected by the same `kind`: `card`"
        message shouldContain "cannot place a payload in two classes"
    }

    @Test
    fun `an inline branch that states no value is refused rather than given a positional one`() {
        val message = refusing(
            """
            openapi: 3.1.0
            info: { title: Events, version: "1.0.0" }
            components:
              schemas:
                Event:
                  oneOf:
                    - type: object
                      properties: { at: { type: string, const: created }, id: { type: string } }
                    - type: object
                      properties: { at: { type: string }, reason: { type: string } }
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
            "Event" to "at",
        )

        message shouldContain "at components.schemas.Event.oneOf[1]"
        message shouldContain "written inline and declares no `at`"
        message shouldContain "Give it a `const`"
    }

    /** Every wrong hint at once: the edit a reader makes is one edit to one block. */
    @Test
    fun `every wrong hint is reported together`() {
        val message = refusing(payments(), "Payments" to "kind", "Card" to "kind")

        message shouldContain "2 discriminator hints cannot be used as written"
        message shouldContain "There is no schema at that position"
        message shouldContain "there is no union here"
    }

    // ------------------------------------------------------ a stale hint

    @Test
    fun `a hint the document has caught up with is refused rather than ignored`() {
        val message = refusing(
            payments(discriminator = "propertyName: kind"),
            "Payment" to "kind",
        )

        message shouldContain "The document states its own `discriminator` (`propertyName: kind`)"
        message shouldContain "drop the hint"
    }

    @Test
    fun `a hint whose schema no imported operation reaches is refused`() {
        val message = shouldThrow<ImportFailure> {
            imported(
                payments(),
                importOptions("test", "test") {
                    exclude = setOf("pay")
                    discriminators = mapOf("Payment" to "kind")
                },
            )
        }.message.orEmpty()

        message shouldContain "Nothing generated from this document reaches that schema"
        message shouldContain "Drop the hint"
    }

    // -------------------------------------------------------- discoverability

    /** The refusal a hint answers names the hint, or nobody finds it. */
    @Test
    fun `the refusal for an undiscriminated union names the way through`() {
        val message = shouldThrow<ImportFailure> { imported(payments()) }.message.orEmpty()

        message shouldContain "add a `discriminator`"
        message shouldContain """discriminator("Payment", property = "kind")"""
    }
}

private val PLAIN_CARDS =
    """
    |Card:
    |  type: object
    |  properties: { kind: { type: string }, number: { type: string } }
    |  required: [kind, number]
    |Bank:
    |  type: object
    |  properties: { kind: { type: string }, iban: { type: string } }
    |  required: [kind, iban]
    """.trimMargin()

private val CONST_CARDS =
    """
    |CardPayment:
    |  type: object
    |  properties: { kind: { type: string, const: card }, number: { type: string } }
    |  required: [kind, number]
    |BankPayment:
    |  type: object
    |  properties: { kind: { type: string, const: bank_transfer }, iban: { type: string } }
    |  required: [kind, iban]
    """.trimMargin()

private val ENUM_CARDS =
    """
    |Card:
    |  type: object
    |  properties: { kind: { type: string, enum: [card] }, number: { type: string } }
    |  required: [kind, number]
    |Bank:
    |  type: object
    |  properties: { kind: { type: string, enum: [bank] }, iban: { type: string } }
    |  required: [kind, iban]
    """.trimMargin()

private val CLASHING_CARDS =
    """
    |Card:
    |  type: object
    |  properties: { kind: { type: string, const: card }, number: { type: string } }
    |  required: [kind, number]
    |Bank:
    |  type: object
    |  properties: { kind: { type: string, const: card }, iban: { type: string } }
    |  required: [kind, iban]
    """.trimMargin()
