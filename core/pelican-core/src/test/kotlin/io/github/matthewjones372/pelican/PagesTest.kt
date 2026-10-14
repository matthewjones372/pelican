package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Spec 0059: which file answers a path, and that no path leaves the directory. */
class PagesTest {

    private val files = setOf("ui/index.html", "ui/about.html", "ui/app.js", "ui/guide/index.html", "ui/ops.html")
    private val ui = pages("ui")

    private fun resolved(path: String) = ui.resolve(path, files::contains)

    @Test
    fun `a path answers as the file, then as html, then as a directory's index`() {
        resolved("") shouldBe Pages.Resolved("ui/index.html", redirect = false)
        resolved("app.js") shouldBe Pages.Resolved("ui/app.js", redirect = false)
        resolved("about") shouldBe Pages.Resolved("ui/about.html", redirect = false)
        resolved("guide/") shouldBe Pages.Resolved("ui/guide/index.html", redirect = false)
    }

    @Test
    fun `a directory without its slash is sent to it, so the page's relative links resolve inside it`() {
        resolved("guide") shouldBe Pages.Resolved("ui/guide/index.html", redirect = true)
    }

    @Test
    fun `a path that names no file, or would leave the directory, answers nothing`() {
        resolved("missing").shouldBeNull()
        resolved("../secret.txt").shouldBeNull()
        resolved("guide/../about").shouldBeNull()
        resolved("./about").shouldBeNull()
        resolved("a//b").shouldBeNull()
        resolved("..\\secret").shouldBeNull()
    }

    @Test
    fun `pages are named as a classpath directory and served under a path`() {
        shouldThrow<IllegalArgumentException> { pages("/ui") }
        shouldThrow<IllegalArgumentException> { pages("ui", at = "ui") }
        pages("ui/", at = "/site/").let { it.resources shouldBe "ui"; it.at shouldBe "/site" }
    }
}
