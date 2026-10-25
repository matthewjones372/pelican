package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.RequestView
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RequestViewTest {

    private fun view(vararg fields: Pair<String, String>) = object : RequestView {
        override val method = "GET"
        override val rawPath = "/"
        override val rawQuery: String? = null

        override fun header(name: String) = headers(name).firstOrNull()

        override fun headers(name: String) =
            fields.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
    }

    @Test
    fun `a cookie is read from whichever Cookie line carries it`() {
        val view = view("Cookie" to "theme=dark", "cookie" to "session=abc; lang=en")
        view.cookie("session") shouldBe "abc"
        view.cookie("theme") shouldBe "dark"
    }

    @Test
    fun `a cookie nobody sent is null`() {
        view().cookie("session").shouldBeNull()
    }
}
