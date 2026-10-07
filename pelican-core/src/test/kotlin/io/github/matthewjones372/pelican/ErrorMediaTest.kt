package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** A failure in a media type of its own: what it may be written as, and what a tag asks of it. See spec 0070. */
class ErrorMediaTest {

    data class Report(val status: String)

    @Test
    fun `a failure declared with errorJson is JSON, and one with errorMedia is its own media type`() {
        errorJson<Report>(503, "Down").mediaType shouldBe "application/json"
        errorMedia<Report>("application/health+json", 503, "Down").mediaType shouldBe "application/health+json"
    }

    @Test
    fun `a media type that is not one concrete type is refused where it is declared`() {
        shouldThrow<IllegalArgumentException> { errorMedia<Report>("application/*", 503, "Down") }
            .message shouldContain "one concrete type/subtype"
    }

    @Test
    fun `a tagged failure in a +json type keeps its media type`() {
        errorMedia<Report>("application/health+json", 503, "Down").tagged("db").mediaType shouldBe
            "application/health+json"
    }

    @Test
    fun `a failure that is not JSON cannot carry a tag, which is written into a JSON body`() {
        shouldThrow<IllegalArgumentException> { errorMedia<Report>("text/csv", 503, "Down").tagged("db") }
            .message shouldContain "only a failure in JSON or a +json type can carry one"
    }
}
