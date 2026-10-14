package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.errorMedia
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.orFail
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/** A failure declared in a media type of its own is documented under it, not under JSON. See spec 0070. */
class ErrorMediaDocumentTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) components.register(name, jsonObj { "type" to "object" })
            return components.ref(name)
        }
    }

    data class Report(val status: String)

    data class Problem(val message: String)

    private val unavailable = errorMedia<Report>("application/health+json", 503, "A check failed")
    private val broken = errorJson<Problem>(500, "Something broke")

    private val ready = endpoint {
        get("health" / "ready")
        json<Report>().orFail(unavailable, broken)
    }

    private fun responses() =
        apiSpec(listOf(ready), Schemas).openApi() / "paths" / "/health/ready" / "get" / "responses"

    @Test
    fun `the failure is documented under its own media type`() {
        (responses() / "503" / "content").keys() shouldContainExactly setOf("application/health+json")
        (responses() / "503" / "content" / "application/health+json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/Report"
        (responses() / "503" / "description").str() shouldBe "A check failed"
    }

    @Test
    fun `and a failure declared with errorJson is still JSON`() {
        (responses() / "500" / "content").keys() shouldContainExactly setOf("application/json")
    }
}
