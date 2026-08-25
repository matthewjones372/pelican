package example

import example.imported.importedSpec
import example.imported32.imported32Spec
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.codegen.writeKotlinClient
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.openApi
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test

class ImportedOrdersTest {

    @Test
    fun `the document survives being read back as endpoint descriptions`() {
        contractOf(ordersSpec().openApi()) shouldBe contractOf(importedSpec(JacksonCodecs).openApi())
    }

    @Test
    fun `and with its own schemas it comes back whole`() {
        canonical(withoutSuccessDescriptions(importedSpec().openApi())) shouldBe
            canonical(withoutSuccessDescriptions(ordersSpec().openApi()))
    }

    /**
     * Choosing 3.2 changes what the document says, not what it means.
     *
     * `imported32Spec` is generated from the same endpoints written against
     * OpenAPI 3.2 — a separate `documents` entry with `openApiVersion` set, and
     * a separate `endpoints` entry reading what it wrote. Three fields moved in
     * that rendering, and the orders service exercises all three: two NDJSON
     * responses and an event stream, whose frames leave `schema` for
     * `itemSchema` and whose payload ends up two levels inside a described SSE
     * event, and two cookie parameters that acquire `style: "cookie"`.
     *
     * If the emitter ever says something under 3.2 that the importer cannot
     * read back, it fails here, against a real document rather than a fixture.
     */
    @Test
    fun `the 3_2 rendering reads back as exactly the same descriptions`() {
        canonical(imported32Spec().openApi()) shouldBe canonical(importedSpec().openApi())
    }

    /** The same document with every object's keys in one order, rendered. */
    private fun canonical(value: JsonValue): String = when (value) {
        is JsonObj -> value.fields.toSortedMap().map { (key, field) -> "${JsonStr(key).render()}:${canonical(field)}" }
            .joinToString(",", "{", "}")

        is JsonArr -> value.items.joinToString(",", "[", "]") { canonical(it) }

        else -> value.render()
    }

    private fun withoutSuccessDescriptions(document: JsonObj): JsonObj {
        val paths = document.fields("paths").mapValues { (_, item) ->
            JsonObj(
                item.fields().mapValues { (_, operation) ->
                    val response = (operation as JsonObj).fields("responses")
                        .mapValues { (status, body) ->
                            if (status.startsWith("2")) JsonObj((body as JsonObj).fields - "description")
                            else body
                        }
                    JsonObj(operation.fields + mapOf("responses" to JsonObj(response)))
                },
            )
        }
        return JsonObj(document.fields + mapOf("paths" to JsonObj(paths)))
    }

    /**
     * The union, and the one thing about it a document can silently lose.
     *
     * `bank_transfer` lives in a `@JsonSubTypes` annotation in `Model.kt` and
     * nowhere else in the Kotlin. It reaches the document only because
     * `pelican-jackson` writes the `discriminator.mapping` from that
     * annotation; without it OpenAPI's implicit-mapping rule makes the value
     * the *schema's* name, `BankTransfer`, and every reader of the document
     * would encode a payload this service rejects.
     *
     * So the trip is made twice over. The second document is derived from the
     * Kotlin the importer generated, which means the annotation that comes out
     * has to say what the annotation that went in said — the round trip closing
     * on the wire format rather than only on the shape.
     */
    @Test
    fun `the value that selects each branch survives the trip out and back`() {
        val published = mappingOf(ordersSpec().openApi())

        published shouldBe mapOf(
            "card" to "Card",
            "bank_transfer" to "BankTransfer",
            "invoice" to "Invoice",
        )
        mappingOf(importedSpec(JacksonCodecs).openApi()) shouldBe published
    }

    /** `discriminator.mapping` on `PaymentMethod`, as wire value -> schema name. */
    private fun mappingOf(document: JsonObj): Map<String, String> {
        val union = document.obj("components")?.fields("schemas")?.get("PaymentMethod") as JsonObj
        val discriminator = union["discriminator"] as JsonObj
        return (discriminator["mapping"] as JsonObj).fields
            .mapValues { (_, target) -> (target as JsonStr).value.substringAfterLast('/') }
    }

    @Test
    fun `so do the payload types the document named`() {
        schemaNames(ordersSpec().openApi()) shouldBe schemaNames(importedSpec(JacksonCodecs).openApi())
    }

    @Test
    fun `a webhook comes back as a webhook, and not as a route`() {
        val imported = importedSpec()

        imported.webhooks.map { it.name } shouldBe listOf("orderPlaced")
        imported.webhooks.single().operation.pathSpec.segments shouldBe emptyList()
        imported.endpoints.none { it.webhookName != null } shouldBe true
    }

    /** And a client generated from it grows the sender, pointed at nobody in particular. */
    @Test
    fun `the sender generated from the imported webhook takes the subscriber's url`(@TempDir directory: File) {
        val written = importedSpec().writeKotlinClient(directory, "example.imported.webhookclient")

        written.readText() shouldContain "fun orderPlaced(url: String, body: Order, xSignature: String)"
    }

    @Test
    fun `a client generates from the imported descriptions`(@TempDir directory: File) {
        val written = importedSpec().writeKotlinClient(directory, "example.imported.client")

        val client = written.readText()
        client shouldContain "class OrdersClient("
        client shouldContain "fun getUser("
        client shouldContain "data class Order("
    }

    /** Every route, and what a caller has to send and can expect back. */
    private fun contractOf(document: JsonObj): Map<String, Any> = buildMap {
        document.fields("paths").forEach { (template, item) ->
            item.fields().forEach { (method, raw) ->
                val operation = raw as JsonObj
                put(
                    "$method $template",
                    mapOf(
                        "operationId" to operation.text("operationId"),
                        "tags" to operation.list("tags").map { (it as JsonStr).value },
                        "deprecated" to (operation["deprecated"] != null),
                        "security" to operation.list("security").map { it.render() },
                        "parameters" to operation.list("parameters").map { parameter ->
                            (parameter as JsonObj).let {
                                listOf(it.text("name"), it.text("in"), it["required"]?.render(), it.type())
                            }
                        },
                        "requestBody" to operation.obj("requestBody")?.fields("content")?.keys,
                        "responses" to operation.fields("responses").mapValues { (_, response) ->
                            (response as JsonObj).fields("content").keys
                        },
                    ),
                )
            }
        }
    }

    private fun schemaNames(document: JsonObj): Set<String> =
        document.obj("components")?.fields("schemas")?.keys.orEmpty()

    // ------------------------------------------------------------------ tree

    private fun JsonObj.fields(key: String): Map<String, JsonValue> = (this[key] as? JsonObj)?.fields.orEmpty()

    private fun JsonValue.fields(): Map<String, JsonValue> = (this as? JsonObj)?.fields.orEmpty()

    private fun JsonObj.obj(key: String): JsonObj? = this[key] as? JsonObj

    private fun JsonObj.list(key: String): List<JsonValue> = (this[key] as? JsonArr)?.items.orEmpty()

    private fun JsonObj.text(key: String): String? = (this[key] as? JsonStr)?.value

    /** A parameter's type as the wire sees it: the schema, minus anything only a reader reads. */
    private fun JsonObj.type(): String? = obj("schema")
        ?.let { JsonObj(it.fields - "description" - "example") }
        ?.render()
}
