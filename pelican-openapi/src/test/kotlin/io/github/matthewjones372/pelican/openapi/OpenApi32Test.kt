package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.*
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * What moves when the same descriptions are written against 3.2 instead of 3.1.
 *
 * The three differences below are the whole of it, and the last test in this
 * file says so by comparing the two documents wholesale: anything else that
 * starts differing is either a mistake or a decision nobody has written down.
 */
class OpenApi32Test {

    /** Names the payload types; the shapes themselves do not matter here. */
    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(name, jsonObj { "type" to "object" })
            }
            return components.ref(name)
        }
    }

    data class Order(val id: Long)

    data class Tick(val at: Long)

    private val session = cookieParam<String>("session", description = "Who is asking")
    private val seen = cookieParam<String>("seen").repeated()
    private val tags = queryParam<String>("tag").repeated()

    private val stream = endpoint(session, seen, tags) {
        get("orders")
        ndjson<Order>()
    }

    private val watch = endpoint {
        get("orders" / "watch")
        sse<Tick>(eventName = "order")
    }

    private val unnamed = endpoint {
        get("orders" / "pulse")
        sse<Tick>()
    }

    private val batch = endpoint {
        get("orders" / "all")
        jsonArray<Order>()
    }

    private fun spec() = ApiSpec(listOf(stream, watch, unnamed, batch), Schemas, title = "Orders")

    private fun doc(version: OpenApiVersion) = spec().openApi(version)

    private fun content(version: OpenApiVersion, path: String, mediaType: String) =
        doc(version) / "paths" / path / "get" / "responses" / "200" / "content" / mediaType

    // ------------------------------------------------------------ the number

    @Test
    fun `the version asked for is the version written`() {
        (doc(OpenApiVersion.V3_1_0) / "openapi").str() shouldBe "3.1.0"
        (doc(OpenApiVersion.V3_2_0) / "openapi").str() shouldBe "3.2.0"
    }

    @Test
    fun `a caller who does not choose gets 3_1_0`() {
        (spec().openApi() / "openapi").str() shouldBe "3.1.0"
        spec().openApiJson().contains("\"openapi\": \"3.1.0\"") shouldBe true
        spec().openApiYaml().startsWith("openapi: 3.1.0") shouldBe true
    }

    @Test
    fun `the API's own version is not the specification's`() {
        // The two are both called `version`, and the emitter binds them in
        // separate scopes for exactly that reason. This is the test that
        // notices if they are ever put back together.
        (doc(OpenApiVersion.V3_2_0) / "info" / "version").str() shouldBe "1.0.0"
    }

    // -------------------------------------------------------- sequential media

    @Test
    fun `an NDJSON frame is an itemSchema under 3_2 and a schema under 3_1`() {
        val v31 = content(OpenApiVersion.V3_1_0, "/orders", "application/x-ndjson")
        (v31 / "schema" / "\$ref").str() shouldBe "#/components/schemas/Order"
        (v31 / "itemSchema").shouldBeNull()

        val v32 = content(OpenApiVersion.V3_2_0, "/orders", "application/x-ndjson")
        (v32 / "itemSchema" / "\$ref").str() shouldBe "#/components/schemas/Order"
        withClue("`schema` would claim the whole stream is one Order") {
            (v32 / "schema").shouldBeNull()
        }
    }

    @Test
    fun `a streamed JSON array stays an array in both, because it is not sequential`() {
        listOf(OpenApiVersion.V3_1_0, OpenApiVersion.V3_2_0).forEach { version ->
            val body = content(version, "/orders/all", "application/json")
            withClue("$version") {
                (body / "schema" / "type").str() shouldBe "array"
                (body / "itemSchema").shouldBeNull()
            }
        }
    }

    @Test
    fun `a 3_2 event stream describes the event, not the payload`() {
        val item = content(OpenApiVersion.V3_2_0, "/orders/watch", "text/event-stream") / "itemSchema"

        (item / "type").str() shouldBe "object"
        (item / "properties" / "event" / "const").str() shouldBe "order"
        (item / "properties" / "data" / "type").str() shouldBe "string"
        (item / "properties" / "data" / "contentMediaType").str() shouldBe "application/json"
        (item / "properties" / "data" / "contentSchema" / "\$ref").str() shouldBe "#/components/schemas/Tick"
        (item / "required").strings() shouldBe listOf("event", "data")
    }

    @Test
    fun `an unnamed event stream documents no event field, because none is sent`() {
        val item = content(OpenApiVersion.V3_2_0, "/orders/pulse", "text/event-stream") / "itemSchema"

        (item / "properties" / "event").shouldBeNull()
        (item / "required").strings() shouldBe listOf("data")
    }

    @Test
    fun `a 3_1 event stream can only name the payload`() {
        val body = content(OpenApiVersion.V3_1_0, "/orders/watch", "text/event-stream")

        (body / "schema" / "\$ref").str() shouldBe "#/components/schemas/Tick"
        (body / "itemSchema").shouldBeNull()
    }

    // ----------------------------------------------------------------- cookies

    private fun parameter(version: OpenApiVersion, name: String) =
        (doc(version) / "paths" / "/orders" / "get" / "parameters").arr()
            .single { (it / "name").str() == name }

    @Test
    fun `every cookie says style cookie under 3_2, list or not`() {
        withClue("a single cookie value is not percent-encoded either") {
            (parameter(OpenApiVersion.V3_2_0, "session") / "style").str() shouldBe "cookie"
        }
        (parameter(OpenApiVersion.V3_2_0, "seen") / "style").str() shouldBe "cookie"
        withClue("`explode` defaults to true for `cookie`, and `repeated()` is exploded") {
            (parameter(OpenApiVersion.V3_2_0, "seen") / "explode").shouldBeNull()
        }
    }

    @Test
    fun `under 3_1 a cookie says nothing, because form is all there is to say`() {
        (parameter(OpenApiVersion.V3_1_0, "session") / "style").shouldBeNull()
        (parameter(OpenApiVersion.V3_1_0, "seen") / "style").shouldBeNull()
    }

    @Test
    fun `a query parameter is untouched by any of this`() {
        listOf(OpenApiVersion.V3_1_0, OpenApiVersion.V3_2_0).forEach { version ->
            withClue("$version") {
                // `form` exploded is what a repeated query already defaults to.
                (parameter(version, "tag") / "style").shouldBeNull()
                (parameter(version, "tag") / "explode").shouldBeNull()
            }
        }
    }

    // ------------------------------------------------------------- and nothing else

    @Test
    fun `the two documents differ in the places above and nowhere else`() {
        val differences = differences(doc(OpenApiVersion.V3_1_0), doc(OpenApiVersion.V3_2_0))

        differences shouldBe listOf(
            "/openapi",
            // Index 0 is the query parameter, which does not move.
            "/paths//orders/get/parameters/1/style",
            "/paths//orders/get/parameters/2/style",
            "/paths//orders/get/responses/200/content/application/x-ndjson/itemSchema",
            "/paths//orders/get/responses/200/content/application/x-ndjson/schema",
            "/paths//orders/pulse/get/responses/200/content/text/event-stream/itemSchema",
            "/paths//orders/pulse/get/responses/200/content/text/event-stream/schema",
            "/paths//orders/watch/get/responses/200/content/text/event-stream/itemSchema",
            "/paths//orders/watch/get/responses/200/content/text/event-stream/schema",
        )
    }

    @Test
    fun `the comparison the last test rests on can tell two documents apart`() {
        // A test whose whole job is a list of paths is worth nothing if the
        // thing producing the list returns an empty one.
        differences(doc(OpenApiVersion.V3_1_0), doc(OpenApiVersion.V3_1_0)) shouldBe emptyList()
        differences(doc(OpenApiVersion.V3_1_0), doc(OpenApiVersion.V3_2_0)) shouldNotBe emptyList<String>()
    }

    /** Every path at which two documents disagree, in order, as JSON pointers. */
    private fun differences(left: JsonValue?, right: JsonValue?, at: String = ""): List<String> = when {
        left is JsonObj && right is JsonObj ->
            (left.fields.keys + right.fields.keys).sorted().flatMap { key ->
                differences(left[key], right[key], "$at/$key")
            }

        left is JsonArr && right is JsonArr && left.items.size == right.items.size ->
            left.items.indices.flatMap { i -> differences(left.items[i], right.items[i], "$at/$i") }

        left == right -> emptyList()

        else -> listOf(at)
    }

    @Test
    fun `both renderings survive being written out`() {
        // The YAML path takes the same tree; this is here so a version that
        // renders as JSON and not as YAML cannot ship.
        spec().openApiYaml(OpenApiVersion.V3_2_0).lineSequence().first() shouldBe "openapi: 3.2.0"
        spec().openApiJson(OpenApiVersion.V3_2_0).shouldNotBeNull()
    }
}
