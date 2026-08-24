package io.github.matthewjones372.pelican.kotlinx

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.openApi
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test

/**
 * The payoff test for the whole `Codecs` abstraction.
 *
 * The same endpoint descriptions are documented twice — once with Jackson and
 * swagger-core, once with kotlinx.serialization and a descriptor walker — and
 * the two documents have to say the same thing. Two independent metadata
 * systems reading one set of Kotlin classes is a real check: if a schema source
 * quietly disagrees about what is required, or what may be null, the documents
 * diverge here rather than in somebody's client generator.
 *
 * The models below are chosen to cover the shapes that actually differ between
 * the two: defaults, nullability, enums, collections, maps, nesting and
 * recursion.
 */
class CodecAgreementTest {

    @Serializable
    enum class Status { PENDING, SHIPPED, CANCELLED }

    @Serializable
    data class Address(val street: String, val city: String, val postcode: String?)

    @Serializable
    data class Line(val sku: String, val quantity: Int = 1, val note: String? = null)

    /**
     * `billTo`, `previousStatus` and `couriers` were added when the emitter
     * moved to 3.1. Under 3.0 the first of them had no expressible answer at
     * all — `nullable` could not sit beside a `$ref` — so both sources dropped
     * it and agreed on a document that was wrong in the same way. The other two
     * are the type union over shapes that are not a bare primitive.
     *
     * `history`, `attempts`, `depots` and `batches` are the harder half, and
     * this file went years without them. Every nullable field above is nullable
     * at *property* level, which is the level Jackson's own metadata still
     * carries; inside a `List` or a `Map` the Java type is identical whether or
     * not the element may be null, so a source reading only the property is
     * wrong there and nothing here noticed. Each is paired with a
     * non-nullable sibling of the same element type — `lines` beside
     * `attempts`, `labels` beside `depots`, `status` beside `history` — because
     * the fix widens schema objects in place, and a sibling left alone is the
     * evidence that no shared instance was widened out from under it.
     */
    @Serializable
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

    /**
     * Recursive on purpose: both sources have to terminate at a `$ref`. And
     * `related` is that same termination one level inside a collection, where
     * the element is the type being defined.
     */
    @Serializable
    data class Category(
        val name: String,
        val children: List<Category>,
        val related: List<Category?>,
    )

    @Serializable
    data class CreateOrder(val lines: List<Line>, val shipTo: Address, val gift: Boolean = false)

    @Serializable
    data class Failure(val code: String, val detail: String? = null)

    private val orderId = pathParam<Long>("orderId")
    private val limit = queryParam<Int>("limit").default(20)
    private val status = queryParam<Status>("status").optional()
    private val trace = headerParam<String>("X-Trace-Id").optional()
    private val newOrder = jsonBody<CreateOrder>(description = "The order to place")
    private val upload = rawBody()

    /**
     * A body whose *own* type is a collection of nullable elements. It never
     * reaches a model resolver — there is no model, only a `Map` — so it is the
     * top-level half of the same problem the fields inside [Order] are the
     * nested half of.
     */
    private val depotUpdate = jsonBody<Map<String, Address?>>(description = "Depots, by code")

    private val endpoints = listOf(
        endpoint(orderId) {
            get("orders" / orderId)
            summary = "Fetch an order"
            tag("orders")
            errorJson<Failure>(404, "No such order")
            json<Order>()
        },
        endpoint(limit, status, trace) {
            get("orders")
            summary = "Stream orders"
            ndjson<Order>()
        },
        endpoint(limit) {
            get("orders" / "list")
            summary = "Stream orders as an array"
            jsonArray<Order>()
        },
        endpoint(limit) {
            get("orders" / "watch")
            sse<Order>(eventName = "order")
        },
        endpoint(newOrder) {
            post("orders")
            errorJson<Failure>(401, "Not allowed")
            json<Order>(status = 201)
        },
        endpoint(orderId) {
            delete("orders" / orderId)
            empty(status = 204)
        },
        endpoint(noInputs) {
            get("categories")
            json<Category>()
        },
        endpoint(upload) {
            post("upload")
            bytes()
        },
        endpoint(noInputs) {
            get("health")
            text()
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
    fun `jackson and kotlinx produce the same document`() {
        val jackson = documentWith(JacksonCodecs)
        val kotlinx = documentWith(KotlinxCodecs)

        withClue("the two schema sources disagree; the abstraction is leaking") {
            jackson.normalise().renderPretty() shouldBe kotlinx.normalise().renderPretty()
        }
    }

    @Test
    fun `both describe every model, and the comparison is not vacuous`() {
        // A guard on the test itself: comparing two empty documents would pass.
        listOf(JacksonCodecs, KotlinxCodecs).forEach { source ->
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
        // The agreement test above would pass just as well if both sources had
        // gone on emitting `nullable: true` together, so the shapes are pinned
        // here rather than only compared to each other.
        listOf(JacksonCodecs, KotlinxCodecs).forEach { source ->
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
        // The test above pins nullability at property level, which is where
        // Jackson's own metadata reaches. One `List` down there is no metadata
        // left: `List<Line?>` and `List<Line>` are the same Java type, and only
        // the Kotlin type says which is which. A source that reads the property
        // and stops is wrong here, silently, and the comparison at the top of
        // this file passed for years without covering a single one of these.
        listOf(JacksonCodecs, KotlinxCodecs).forEach { source ->
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

            // And the same shapes when the *body's own* type is the collection,
            // which no model resolver ever sees — there is no model to resolve,
            // so this path has to know about nullability by itself.
            val operation = documentWith(source)["paths"].asObj()["/depots"].asObj()["put"].asObj()
            val body = operation["requestBody"].asObj()["content"].asObj()["application/json"]
                .asObj()["schema"].asObj()
            assertNullableRef(source, body["additionalProperties"], "Address")
            val returned = operation["responses"].asObj()["200"].asObj()["content"].asObj()["application/json"]
                .asObj()["schema"].asObj()
            assertNullableRef(source, returned["items"], "Line")

            // The non-nullable siblings are untouched. Widening happens in place
            // on swagger's objects, so this is the evidence that no schema
            // instance was shared and widened out from under another property.
            order["lines"].asObj()["type"].asString() shouldBe "array"
            order["lines"].asObj()["items"].asObj().ref() shouldBe "#/components/schemas/Line"
            order["labels"].asObj()["additionalProperties"].asObj()["type"].asString() shouldBe "string"
            order["status"].asObj()["type"].asString() shouldBe "string"
            category["children"].asObj()["items"].asObj().ref() shouldBe "#/components/schemas/Category"
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
        // Agreeing on the document is only half of it — the documents describe a
        // wire format, and both codecs have to actually produce it.
        val order = Line("sku-1", quantity = 4, note = null)
        val type = typeOfLine()

        val byJackson = JacksonCodecs.codec<Line>(type)
        val byKotlinx = KotlinxCodecs.codec<Line>(type)

        byKotlinx.decodeFromString(byJackson.encodeToString(order)) shouldBe order
        byJackson.decodeFromString(byKotlinx.encodeToString(order)) shouldBe order
    }

    @Test
    fun `a form body reads the same through either codec`() {
        // The claim `formCodec` is built on. A form carries strings, so the
        // schema decides what `quantity=4` is — and because that decision is
        // made before either library sees the document, Jackson's willingness
        // to coerce and kotlinx.serialization's refusal to stop mattering.
        val type = typeOfLine()
        val form = "sku=sku-1&quantity=4"

        val byJackson = JacksonCodecs.formCodec<Line>(type)
        val byKotlinx = KotlinxCodecs.formCodec<Line>(type)

        val expected = Line("sku-1", quantity = 4, note = null)
        byJackson.decodeFromString(form) shouldBe expected
        byKotlinx.decodeFromString(form) shouldBe expected
        byKotlinx.encodeToString(expected).split("&").toSet() shouldBe
            byJackson.encodeToString(expected).split("&").toSet()
    }

    private fun typeOfLine() = kotlin.reflect.typeOf<Line>()
}

// ------------------------------------------------------------------ helpers

private fun JsonValue?.asObj(): JsonObj = this as? JsonObj ?: error("not an object: $this")

private fun JsonValue?.asString(): String = (this as? JsonStr)?.value ?: error("not a string: $this")

private fun JsonObj.ref(): String = this["\$ref"].asString()

private fun JsonValue?.asStrings(): List<String> =
    (this as? JsonArr)?.items?.map { (it as JsonStr).value }.orEmpty()

/**
 * Removes the two differences that carry no meaning: the order of an object's
 * keys, and the order of a `required` list. Neither is significant in JSON or
 * in OpenAPI, and two independent generators have no reason to agree on either.
 * Everything else is compared exactly.
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
