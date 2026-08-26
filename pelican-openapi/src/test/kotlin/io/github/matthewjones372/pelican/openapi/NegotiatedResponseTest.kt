package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.media
import io.github.matthewjones372.pelican.negotiated
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.orFail
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * One response written several ways, published.
 *
 * OpenAPI's `content` is a map from media type to a media type object and
 * always could say this — it is what the request half already emits for a
 * negotiated body. What could not say it was the endpoint.
 */
class NegotiatedResponseTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) components.register(name, jsonObj { "type" to "object" })
            return components.ref(name)
        }
    }

    data class Report(val year: Int)
    data class Problem(val code: String)

    private val export = endpoint {
        get("reports")
        operationId = "exportReport"
        negotiated(json<Report>(), media<Report>("text/csv")) orFail errorJson<Problem>(404, "No such year")
    }

    private val document = apiSpec(listOf(export), Schemas) {
        title = "Reports"
    }.openApi()
    private val responses = document / "paths" / "/reports" / "get" / "responses"

    @Test
    fun `one status, with one entry per rendering in declaration order`() {
        responses.keys().toList() shouldBe listOf("200", "404", "default")
        (responses / "200" / "content").keys().toList() shouldBe listOf("application/json", "text/csv")
    }

    @Test
    fun `every rendering publishes the schema of the value, because it is one value`() {
        val content = responses / "200" / "content"

        (content / "application/json" / "schema" / "\$ref").str() shouldBe "#/components/schemas/Report"
        (content / "text/csv" / "schema" / "\$ref").str() shouldBe "#/components/schemas/Report"
    }

    /** The 3.2 rendering moves a *sequential* media type's schema, and this is not one. */
    @Test
    fun `and says the same thing under 3_2`() {
        val v32 = apiSpec(listOf(export), Schemas) {
            title = "Reports"
        }.openApi(OpenApiVersion.V3_2_0)
        val content = v32 / "paths" / "/reports" / "get" / "responses" / "200" / "content"

        content.keys().toList() shouldBe listOf("application/json", "text/csv")
        (content / "text/csv" / "schema" / "\$ref").str() shouldBe "#/components/schemas/Report"
    }

    @Test
    fun `the declared failure is untouched by any of it`() {
        (responses / "404" / "content").keys().toList() shouldBe listOf("application/json")
    }
}
