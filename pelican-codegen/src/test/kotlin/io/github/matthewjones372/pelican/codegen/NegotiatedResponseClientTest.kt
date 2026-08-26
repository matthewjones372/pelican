package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import io.github.matthewjones372.pelican.media
import io.github.matthewjones372.pelican.negotiated
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
/**
 * A response written two ways, generated as two methods.
 *
 * The caller picks the representation by picking the method. A media type
 * parameter on one method would have to type its result as whatever the two
 * renderings have in common, which for a payload and its CSV is nothing.
 */
class NegotiatedResponseClientTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(
                    name,
                    jsonObj {
                        "type" to "object"
                        put("properties", jsonObj { put("year", jsonObj { "type" to "integer" }) })
                        put("required", jsonStrings(listOf("year")))
                    },
                )
            }
            return components.ref(name)
        }
    }

    data class Report(val year: Int)
    data class Problem(val message: String)

    private val year = pathParam<Int>("year")

    private val export = endpoint(year) {
        get("reports" / year)
        operationId = "exportReport"
        negotiated(json<Report>(), media<Report>("text/csv"))
    }

    private val guarded = endpoint(year) {
        get("guarded" / year)
        operationId = "guardedReport"
        negotiated(json<Report>(), media<Report>("text/csv")) orFail errorJson<Problem>(404, "No such year")
    }

    private val client = apiSpec(listOf(export, guarded), Schemas) {
        title = "Reports"
    }.kotlinClient("com.example")

    @Test
    fun `one method per rendering, named after the subtype`() {
        client shouldContain "fun exportReportAsJson(year: Int): Report {"
        client shouldContain "fun exportReportAsCsv(year: Int): String {"
    }

    @Test
    fun `each asks for the rendering it decodes`() {
        client shouldContain """headerParams = listOf("Accept" to "application/json")"""
        client shouldContain """headerParams = listOf("Accept" to "text/csv")"""
    }

    @Test
    fun `the JSON one decodes the payload and the other hands back the text it asked for`() {
        client shouldContain
            "return reportCodec.decoded(response.body, Method.GET, \"/reports/{year}\", response.status)"
        client shouldContain "return response.body"
    }

    @Test
    fun `a declared failure is the other half of both of them`() {
        client shouldContain "fun guardedReportAsJson(year: Int): Outcome<GuardedReportFailure, Report> {"
        client shouldContain "fun guardedReportAsCsv(year: Int): Outcome<GuardedReportFailure, String> {"
    }

    @Test
    fun `and no method is generated under the bare operation name`() {
        client shouldNotContain "fun exportReport("
        client shouldNotContain "fun guardedReport("
    }

    @Test
    fun `the payload type is declared once, from the schema the document publishes`() {
        client shouldContain "data class Report("
    }
}
