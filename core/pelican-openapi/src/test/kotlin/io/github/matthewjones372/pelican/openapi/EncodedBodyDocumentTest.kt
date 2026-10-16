package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jsonObj
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KType

/** The document is the declaration. The bytes a codec already holds are not part of it. */
class EncodedBodyDocumentTest {

    @Test
    fun `a json endpoint's document does not call encoded`() {
        val quiet = document(blowUp = false)
        val loud = document(blowUp = true)
        loud shouldBe quiet
        loud shouldContain "\"application/json\""
    }

    private fun document(blowUp: Boolean): String {
        val list = endpoint {
            get("pets")
            json<String>()
        }
        return api(
            listOf(ServerEndpoint(list) { CompletableFuture.completedStage("x") }),
            codecs(blowUp),
        ).spec().openApiJson()
    }

    private fun codecs(blowUp: Boolean): Codecs = object : Codecs {
        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: KType): BodyCodec<T> = object : BodyCodec<String> {
            override fun encodeToString(value: String) = value
            override fun decodeFromString(text: String) = text
            override fun encoded(value: String): ByteArray? = if (blowUp) error("encoded") else null
        } as BodyCodec<T>

        override fun schema(type: KType, components: SchemaComponents): JsonObj = jsonObj { "type" to "string" }
    }
}
