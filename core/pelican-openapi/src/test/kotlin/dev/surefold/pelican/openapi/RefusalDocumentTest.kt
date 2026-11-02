package dev.surefold.pelican.openapi

import dev.surefold.pelican.ApiError
import dev.surefold.pelican.ApiErrorEnvelope
import dev.surefold.pelican.Endpoint
import dev.surefold.pelican.JsonObj
import dev.surefold.pelican.JsonValue
import dev.surefold.pelican.ProblemDetails
import dev.surefold.pelican.RefusalRenderer
import dev.surefold.pelican.SchemaComponents
import dev.surefold.pelican.SchemaSource
import dev.surefold.pelican.apiSpec
import dev.surefold.pelican.div
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.jsonObj
import dev.surefold.pelican.openapi.div
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * A service answering RFC 9457 while its document says `ApiError` has documented
 * a service nobody is running. The envelope is one value and both halves — the
 * bytes and the schema — are read off it.
 */
class RefusalDocumentTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) components.register(name, jsonObj { "type" to "object" })
            return components.ref(name)
        }
    }

    data class Report(val title: String)

    private val list = endpoint {
        get("reports")
        operationId = "listReports"
        json<List<Report>>()
    }

    /** An endpoint that says for itself what anything it does not enumerate comes back as. */
    private val declaring = endpoint {
        get("reports" / "own")
        operationId = "ownDefault"
        defaultJson<ApiError>("Whatever this service felt like")
        json<Report>()
    }

    private fun document(refusals: RefusalRenderer, vararg endpoints: Endpoint<*, *>): JsonObj =
        apiSpec(endpoints.toList(), Schemas) {
            title = "Reports"
            refusals(refusals)
        }.openApi()

    private fun refusalIn(document: JsonObj, path: String): JsonValue? =
        document / "paths" / path / "get" / "responses" / "default"

    @Test
    fun `every operation says what a refusal comes back as`() {
        val responses = document(ApiErrorEnvelope, list) / "paths" / "/reports" / "get" / "responses"

        responses.keys().toList() shouldContainExactly listOf("200", "default")
    }

    @Test
    fun `and it is the envelope the wire is written in`() {
        val content = refusalIn(document(ProblemDetails, list), "/reports") / "content"

        content.keys().toList() shouldContainExactly listOf("application/problem+json")
        (content / "application/problem+json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/ProblemDetails"
    }

    /** One component, not one schema per operation: an importer reads a `$ref` back as one type. */
    @Test
    fun `the envelope is published once and pointed at`() {
        val document = document(ApiErrorEnvelope, list)

        (refusalIn(document, "/reports") / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/ApiError"
        (document / "components" / "schemas" / "ApiError") shouldBe ApiErrorEnvelope.schema
    }

    @Test
    fun `an endpoint that declares its own default keeps it`() {
        (refusalIn(document(ProblemDetails, declaring), "/reports/own") / "description").str() shouldBe
            "Whatever this service felt like"
    }
}
