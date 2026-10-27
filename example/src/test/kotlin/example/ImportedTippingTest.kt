package example

import example.importedtipping.importedTippingEndpoints
import example.importedtipping.importedTippingSpec
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.openApi
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Spec 0092's round trip: the document a keyed endpoint publishes, imported, is that endpoint again. */
class ImportedTippingTest {

    @Test
    fun `a keyed endpoint reads back as idempotencyKey, not as a header and two failures`() {
        val tip = importedTippingEndpoints.single()

        tip.idempotency.shouldNotBeNull().required shouldBe true
        tip.headerParams.single() shouldBe tip.idempotency?.header
    }

    /**
     * Every response but `default`, which an imported endpoint declares for itself and so documents with the
     * response headers it emits; the original derives it and does not. That is the importer's reading of any
     * endpoint that emits a header, not of the key.
     */
    @Test
    fun `and publishes the key, the 409 and the 422 it was read from`() {
        withoutDefaults(importedTippingSpec(JacksonCodecs).openApi()).render() shouldBe
            withoutDefaults(tippingSpec().openApi()).render()
    }

    private fun withoutDefaults(document: JsonObj): JsonObj {
        val paths = (document.fields.getValue("paths") as JsonObj).fields.mapValues { (_, item) ->
            JsonObj(
                (item as JsonObj).fields.mapValues { (_, raw) ->
                    val operation = raw as JsonObj
                    val responses = operation.fields.getValue("responses") as JsonObj
                    JsonObj(operation.fields + ("responses" to JsonObj(responses.fields - "default")))
                },
            )
        }
        return JsonObj(document.fields + ("paths" to JsonObj(paths)))
    }
}
