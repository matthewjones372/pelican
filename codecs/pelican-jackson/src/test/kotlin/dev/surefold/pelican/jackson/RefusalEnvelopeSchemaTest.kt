package dev.surefold.pelican.jackson

import dev.surefold.pelican.ApiError
import dev.surefold.pelican.ApiErrorEnvelope
import dev.surefold.pelican.JsonObj
import dev.surefold.pelican.SchemaRegistry
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

/**
 * The default envelope publishes `ApiError` under the name the type itself takes,
 * and a service declaring `errorJson<ApiError>` publishes the same component from
 * the class. Two descriptions of one shape, so they have to be one shape — a
 * document holding whichever was registered first would otherwise depend on the
 * order its operations happen to be walked in.
 */
class RefusalEnvelopeSchemaTest {

    @Test
    fun `the schema core writes by hand is the one this codec derives`() {
        val components = SchemaRegistry()
        JacksonCodecs.schema(typeOf<ApiError>(), components)

        val derived = components.all()[ApiErrorEnvelope.componentName] as JsonObj

        withClue("core writes the refusal envelope's schema without a codec; the two must agree") {
            derived shouldBe ApiErrorEnvelope.schema
        }
    }
}
