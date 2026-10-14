package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.JsonBool
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.atLeast
import io.github.matthewjones372.pelican.commaSeparated
import io.github.matthewjones372.pelican.cookieParam
import io.github.matthewjones372.pelican.describedAs
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.lensInputs
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.pipeSeparated
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.repeated
import io.github.matthewjones372.pelican.spaceSeparated
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * What the document says about a parameter carrying several values.
 *
 * The interesting half is what is *not* written. `style` and `explode` only
 * appear where the encoding differs from what OpenAPI already assumes at that
 * location, so the common repeated query parameter documents as a plain array
 * — and a reader who does meet one of the keywords can take it as meaning
 * something.
 */
class ListParametersTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) components.register(name, jsonObj { "type" to "object" })
            return components.ref(name)
        }
    }

    data class Widget(val id: Long)

    private val tag = queryParam<String>("tag", description = "Only these tags").repeated().optional()
    private val ids = queryParam("ids", IntCodec.atLeast(1)).commaSeparated().optional()
    private val fields = queryParam<String>("fields").spaceSeparated().optional()
    private val sort = queryParam<String>("sort").pipeSeparated().optional()
    private val feature = headerParam("X-Feature", StringCodec.describedAs(example = "beta")).commaSeparated()
    private val seen = cookieParam<String>("seen").repeated().optional()

    private val search = endpoint(lensInputs) {
        get("widgets")
        operationId = "searchWidgets"
        query(tag, ids, fields, sort)
        header(feature)
        cookie(seen)
        json<Widget>()
    }

    private val document = apiSpec(listOf(search), Schemas) {
        title = "Widgets"
    }.openApi()

    private val parameters = (document / "paths" / "/widgets" / "get" / "parameters")
        .arr()
        .associateBy { (it / "name").str() }

    private fun parameter(name: String): JsonObj = parameters.getValue(name).obj()

    @Test
    fun `a list is an array of what one value decodes to`() {
        parameter("tag") / "schema" / "type" shouldBe JsonStr("array")
        parameter("tag") / "schema" / "items" / "type" shouldBe JsonStr("string")
        parameter("tag") / "description" shouldBe JsonStr("Only these tags")
    }

    @Test
    fun `the default encoding at each location writes neither keyword`() {
        // A repeated query parameter is `form` and exploded, and a
        // comma-separated header is `simple` and not — which is what OpenAPI
        // means by each location's silence.
        parameter("tag").fields.keys shouldBe setOf("name", "in", "required", "description", "schema")
        parameter("X-Feature").fields.keys shouldBe setOf("name", "in", "required", "schema")
        parameter("seen").fields.keys shouldBe setOf("name", "in", "required", "schema")
    }

    @Test
    fun `a comma-joined query parameter says only the half that differs`() {
        parameter("ids") / "explode" shouldBe JsonBool(false)
        parameter("ids").fields.containsKey("style") shouldBe false
    }

    @Test
    fun `the delimited styles are named, and their explode is already false`() {
        parameter("fields") / "style" shouldBe JsonStr("spaceDelimited")
        parameter("sort") / "style" shouldBe JsonStr("pipeDelimited")
        parameter("fields").fields.containsKey("explode") shouldBe false
    }

    @Test
    fun `a refinement on the element reaches the schema it refines`() {
        parameter("ids") / "schema" / "items" / "minimum" shouldBe JsonNum(1)
    }

    @Test
    fun `an example belongs to the element, and travels with it`() {
        // A parameter-level `example` for an array would have to be an array,
        // and how long it is is not something the description said.
        parameter("X-Feature") / "schema" / "items" / "example" shouldBe JsonStr("beta")
        parameter("X-Feature").fields.containsKey("example") shouldBe false
    }

    @Test
    fun `required is the ordinary required, and says at least one value arrives`() {
        parameter("X-Feature") / "required" shouldBe JsonBool(true)
        parameter("tag") / "required" shouldBe JsonBool(false)
    }
}
