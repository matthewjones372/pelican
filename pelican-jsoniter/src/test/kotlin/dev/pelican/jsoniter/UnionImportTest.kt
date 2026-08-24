package dev.pelican.jsoniter

import dev.pelican.ApiSpec
import dev.pelican.endpoint
import dev.pelican.importer.Import
import dev.pelican.importer.ImportOptions
import dev.pelican.jsonBody
import dev.pelican.openapi.openApi
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A union published by this module, read back by `pelican-import`.
 *
 * Writing a discriminator is the easy half. The half worth a test is whether
 * the document it produces describes the payload well enough for a reader who
 * has none of the Kotlin — which is what the importer is: it holds no
 * annotations, no constructor and no branch, only the document. A union that
 * only travels outwards is a union whose two halves are free to drift.
 *
 * The other two codec modules make this claim from annotations that say which
 * value selects which branch. This one has no annotations to read, so the
 * branch's own name is the value — and that convention is only worth anything
 * if it survives the trip.
 */
class UnionImportTest {

    sealed interface Payment {
        data class Card(val number: String) : Payment
        data class Bank(val iban: String) : Payment
    }

    private val spec = ApiSpec(
        endpoints = listOf(
            endpoint(jsonBody<Payment>()) {
                post("payments")
                operationId = "pay"
                empty(status = 204)
            },
        ),
        schemas = JsoniterCodecs,
        title = "Payments",
        version = "1.0.0",
    )

    @Test
    fun `the document this module published imports back as the same hierarchy`(@TempDir directory: File) {
        val document = File(directory, "openapi.json")
        document.writeText(spec.openApi().render())

        val generated = Import.kotlin(document, ImportOptions("app", "payments")).values.single()

        generated shouldContain "sealed interface Payment"
        generated shouldContain "data class Card("
        generated shouldContain "data class Bank("
        withClue("the branch's own name is what this module writes, so it is what the mapping has to carry") {
            generated shouldContain """JsonSubTypes.Type(value = Card::class, name = "Card")"""
        }
        withClue("the hierarchy carries the discriminator, so no branch declares it as a property") {
            generated shouldNotContain "val type:"
        }
    }
}
