package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.responseHeader
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KType

/**
 * Two successful responses is a headline feature, and both arrived in the
 * document saying "Success." — the only field that could tell a reader which
 * was which was the one nothing could set. A declared failure has taken a
 * description as its second argument since it existed.
 */
class SuccessDescriptionsTest {

    private object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj = jsonObj { "type" to "object" }
    }

    private data class Greeting(val text: String)

    private val location = responseHeader<String>("Location")

    private val remember = endpoint {
        put("greetings")
        json<Greeting>(201, location, description = "Newly learned") or
            json<Greeting>(200, description = "The service already knew it")
    }

    private val plain = endpoint {
        get("greetings")
        json<Greeting>()
    }

    private fun responsesOf(path: String, method: String) =
        apiSpec(listOf(remember, plain), Schemas).openApi() / "paths" / path / method / "responses"

    @Test
    fun `each declared success carries the description it was given`() {
        val responses = responsesOf("/greetings", "put")
        (responses / "201" / "description").str() shouldBe "Newly learned"
        (responses / "200" / "description").str() shouldBe "The service already knew it"
    }

    @Test
    fun `one that says nothing keeps the wording its media type implies`() {
        (responsesOf("/greetings", "get") / "200" / "description").str() shouldBe "Success."
    }
}
