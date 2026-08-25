package io.github.matthewjones372.pelican.jsoniter

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.importer.Import
import io.github.matthewjones372.pelican.importer.ImportOptions
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.openapi.openApi
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
