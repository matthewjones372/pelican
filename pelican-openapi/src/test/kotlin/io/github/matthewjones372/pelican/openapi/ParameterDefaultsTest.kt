package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.LongCodec
import io.github.matthewjones372.pelican.NoCodecs
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.between
import io.github.matthewjones372.pelican.commaSeparated
import io.github.matthewjones372.pelican.cookieParam
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.queryParam
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A parameter with a default is one the server fills in, and the document said
 * nothing about it. The bounds beside it arrived, because a refinement lives on
 * the codec; the default lives on the parameter and nothing read it — so a
 * caller reading the document could not tell what leaving it out would do.
 */
class ParameterDefaultsTest {

    private val size = queryParam("size", IntCodec.between(1, 200)).default(50)
    private val cursor = queryParam<String>("cursor").optional()
    private val required = queryParam<String>("term")
    private val locale = cookieParam<String>("locale").default("en")
    private val depth = headerParam("X-Depth", LongCodec).default(3L)
    private val ids = queryParam("id", LongCodec).commaSeparated()

    private val ep = endpoint(size, cursor, required, locale, depth, ids) {
        get("search")
        text()
    }

    private val params = (apiSpec(listOf(ep), NoCodecs).openApi() / "paths" / "/search" / "get" / "parameters")
        .arr()
        .associateBy { (it / "name").str() }

    private fun schemaOf(name: String) = params.getValue(name) / "schema"

    @Test
    fun `a default is published beside the constraint that was already there`() {
        (schemaOf("size") / "default").num().toInt() shouldBe 50
        (schemaOf("size") / "minimum").num().toInt() shouldBe 1
        (schemaOf("size") / "maximum").num().toInt() shouldBe 200
    }

    @Test
    fun `it is encoded by the parameter's own codec, so the document spells it as the wire does`() {
        (schemaOf("locale") / "default").str() shouldBe "en"
        (schemaOf("X-Depth") / "default").num().toLong() shouldBe 3L
    }

    @Test
    fun `an optional parameter has no default, because absent is not a value`() {
        (schemaOf("cursor") / "default") shouldBe null
    }

    @Test
    fun `nor does a required one, which has nothing to fall back to`() {
        (schemaOf("term") / "default") shouldBe null
    }

    @Test
    fun `a list without one is left alone`() {
        (schemaOf("id") / "default") shouldBe null
    }
}
