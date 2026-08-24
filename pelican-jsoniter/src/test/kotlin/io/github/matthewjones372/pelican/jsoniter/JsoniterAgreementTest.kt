package io.github.matthewjones372.pelican.jsoniter

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.openApi
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

/**
 * The same claim `CodecAgreementTest` makes for kotlinx.serialization, made for
 * jsoniter: one set of endpoint descriptions, documented through two codec
 * modules, has to produce one document.
 *
 * It is a sharper check here than there. kotlinx's descriptors and Jackson's
 * annotations are both metadata written *for serialization*; this module's
 * schemas come from Kotlin's own reflection over a constructor, with no
 * serialization metadata behind them at all — so the models below carry no
 * annotation of any kind, and both sides still have to say the same thing about
 * what is required, what may be null, and how deep the nullability goes.
 */
class JsoniterAgreementTest {

    enum class Status { PENDING, SHIPPED, CANCELLED }

    data class Address(val street: String, val city: String, val postcode: String?)

    data class Line(val sku: String, val quantity: Int = 1, val note: String? = null)

    /**
     * The shapes the two sources have any reason to disagree about: defaults,
     * nullability at property level, and nullability one and two levels inside a
     * collection — where the Java type is identical either way and only the
     * Kotlin type still knows. Each nullable collection is paired with a
     * non-nullable sibling of the same element type, so a source that widens a
     * shared schema object in place is caught by the sibling rather than by the
     * property it meant to widen.
     */
    data class Order(
        val id: Long,
        val status: Status,
        val previousStatus: Status?,
        val history: List<Status?>,
        val lines: List<Line>,
        val attempts: List<Line?>,
        val batches: List<List<Line>?>,
        val shipTo: Address,
        val billTo: Address?,
        val depots: Map<String, Address?>,
        val couriers: List<String>?,
        val labels: Map<String, String>,
        val weight: Double,
        val gift: Boolean = false,
    )

    /** Recursive on purpose: both sources have to terminate at a `$ref`. */
    data class Category(val name: String, val children: List<Category>, val related: List<Category?>)

    data class CreateOrder(val lines: List<Line>, val shipTo: Address, val gift: Boolean = false)

    data class Failure(val code: String, val detail: String? = null)

    private val orderId = pathParam<Long>("orderId")
    private val limit = queryParam<Int>("limit").default(20)
    private val newOrder = jsonBody<CreateOrder>(description = "The order to place")

    /** A body whose own type is a map of nullable values, so no model resolver sees it. */
    private val depotUpdate = jsonBody<Map<String, Address?>>(description = "Depots, by code")

    private val endpoints = listOf(
        endpoint(orderId) {
            get("orders" / orderId)
            summary = "Fetch an order"
            errorJson<Failure>(404, "No such order")
            json<Order>()
        },
        endpoint(limit) {
            get("orders")
            summary = "Stream orders"
            ndjson<Order>()
        },
        endpoint(newOrder) {
            post("orders")
            json<Order>(status = 201)
        },
        endpoint(noInputs) {
            get("categories")
            json<Category>()
        },
        endpoint(depotUpdate) {
            put("depots")
            summary = "Replace the depot list"
            json<List<Line?>>()
        },
    )

    private fun documentWith(source: SchemaSource): JsonObj = ApiSpec(
        endpoints = endpoints,
        schemas = source,
        title = "Orders",
        version = "3.1.0",
        description = "Described once, documented twice.",
        servers = listOf("http://localhost:8080"),
    ).openApi()

    @Test
    fun `jackson and jsoniter produce the same document`() {
        val jackson = documentWith(JacksonCodecs)
        val jsoniter = documentWith(JsoniterCodecs)

        withClue("the two schema sources disagree; the abstraction is leaking") {
            jackson.normalise().renderPretty() shouldBe jsoniter.normalise().renderPretty()
        }
    }

    @Test
    fun `both describe every model, and the comparison is not vacuous`() {
        // A guard on the test itself: comparing two empty documents would pass.
        listOf(JacksonCodecs, JsoniterCodecs).forEach { source ->
            val schemas = documentWith(source)["components"].asObj()["schemas"].asObj()
            withClue("$source did not describe the expected models") {
                schemas.fields.keys shouldBe setOf("Order", "Line", "Address", "Category", "CreateOrder", "Failure")
            }
            val order = schemas["Order"].asObj()
            order["properties"].asObj().fields.keys.toList() shouldBe listOf(
                "id", "status", "previousStatus", "history", "lines", "attempts",
                "batches", "shipTo", "billTo", "depots", "couriers", "labels",
                "weight", "gift",
            )
            // `gift` has a default, so a payload may leave it out.
            order["required"].asStrings() shouldNotContain "gift"
        }
    }

    @Test
    fun `both spell nullability the way 3_1 does, in all three of its shapes`() {
        listOf(JacksonCodecs, JsoniterCodecs).forEach { source ->
            val document = documentWith(source)
            val properties = document["components"].asObj()["schemas"].asObj()["Order"]
                .asObj()["properties"].asObj()

            withClue("$source did not widen an enum's type") {
                properties["previousStatus"].asObj()["type"].asStrings() shouldBe listOf("string", "null")
            }
            withClue("$source did not widen an array's type") {
                properties["couriers"].asObj()["type"].asStrings() shouldBe listOf("array", "null")
            }
            // A reference has no `type` to widen, so it goes under `anyOf`.
            assertNullableRef(source, properties["billTo"], "Address")

            withClue("$source still writes 3.0's keyword") { document.render() shouldNotContain "nullable" }
        }
    }

    @Test
    fun `both find nullability inside a collection, where only the Kotlin type still knows`() {
        listOf(JacksonCodecs, JsoniterCodecs).forEach { source ->
            val schemas = documentWith(source)["components"].asObj()["schemas"].asObj()
            val order = schemas["Order"].asObj()["properties"].asObj()

            // List<Status?> — the element widens; its constants stay where they are.
            val element = order["history"].asObj()["items"].asObj()
            withClue("$source: List<Status?>") { element["type"].asStrings() shouldBe listOf("string", "null") }
            element["enum"].asStrings() shouldBe listOf("PENDING", "SHIPPED", "CANCELLED")

            // List<Line?> and Map<String, Address?> — references again, so `anyOf`.
            assertNullableRef(source, order["attempts"].asObj()["items"], "Line")
            assertNullableRef(source, order["depots"].asObj()["additionalProperties"], "Address")

            // List<List<Line>?> — two levels down, and only the middle is nullable.
            val batch = order["batches"].asObj()["items"].asObj()
            withClue("$source: List<List<Line>?>") { batch["type"].asStrings() shouldBe listOf("array", "null") }
            batch["items"].asObj().ref() shouldBe "#/components/schemas/Line"

            // A type that refers to itself, one collection down.
            val category = schemas["Category"].asObj()["properties"].asObj()
            assertNullableRef(source, category["related"].asObj()["items"], "Category")

            // The non-nullable siblings are untouched.
            order["lines"].asObj()["items"].asObj().ref() shouldBe "#/components/schemas/Line"
            order["labels"].asObj()["additionalProperties"].asObj()["type"].asString() shouldBe "string"
            order["status"].asObj()["type"].asString() shouldBe "string"
        }
    }

    /** `{"anyOf": [{"$ref": ".../name"}, {"type": "null"}]}` and nothing else. */
    private fun assertNullableRef(source: SchemaSource, schema: JsonValue?, name: String) {
        val branches = (schema.asObj()["anyOf"] as? JsonArr)?.items
            ?: fail("$source: expected an anyOf for a nullable $name, got ${schema?.render()}")
        withClue("$source: $name") { branches.size shouldBe 2 }
        withClue("$source: $name") { branches[0].asObj().ref() shouldBe "#/components/schemas/$name" }
        withClue("$source: $name") { branches[1].asObj()["type"].asString() shouldBe "null" }
    }

    @Test
    fun `a body encoded by one codec is readable by the other`() {
        // Agreeing on the document is half of it — the document describes a wire
        // format, and both codecs have to actually produce it.
        val line = Line("sku-1", quantity = 4, note = null)
        val type = typeOf<Line>()

        val byJackson = JacksonCodecs.codec<Line>(type)
        val byJsoniter = JsoniterCodecs.codec<Line>(type)

        byJsoniter.decodeFromString(byJackson.encodeToString(line)) shouldBe line
        byJackson.decodeFromString(byJsoniter.encodeToString(line)) shouldBe line
    }

    @Test
    fun `a form body reads the same through either codec`() {
        // A form carries strings, so the schema decides what `quantity=4` is —
        // and because that decision is made before either library sees the
        // document, the two have to land on the same value.
        val type = typeOf<Line>()
        val form = "sku=sku-1&quantity=4"

        val expected = Line("sku-1", quantity = 4, note = null)
        JacksonCodecs.formCodec<Line>(type).decodeFromString(form) shouldBe expected
        JsoniterCodecs.formCodec<Line>(type).decodeFromString(form) shouldBe expected

        JsoniterCodecs.formCodec<Line>(type).encodeToString(expected).split("&").toSet() shouldBe
            JacksonCodecs.formCodec<Line>(type).encodeToString(expected).split("&").toSet()
    }
}

// ------------------------------------------------------------------ helpers

private fun JsonValue?.asObj(): JsonObj = this as? JsonObj ?: error("not an object: $this")

private fun JsonValue?.asString(): String = (this as? JsonStr)?.value ?: error("not a string: $this")

private fun JsonObj.ref(): String = this["\$ref"].asString()

private fun JsonValue?.asStrings(): List<String> =
    (this as? JsonArr)?.items?.map { (it as JsonStr).value }.orEmpty()

/**
 * Removes the two differences that carry no meaning: the order of an object's
 * keys, and the order of a `required` list. Everything else is compared exactly.
 */
private fun JsonValue.normalise(): JsonValue = when (this) {
    is JsonObj -> JsonObj(
        fields.toSortedMap().mapValues { (key, value) ->
            if (key == "required" && value is JsonArr) {
                JsonArr(value.items.sortedBy { (it as? JsonStr)?.value.orEmpty() })
            } else {
                value.normalise()
            }
        },
    )

    is JsonArr -> JsonArr(items.map { it.normalise() })

    else -> this
}
