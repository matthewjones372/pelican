package io.github.matthewjones372.pelican.schema

import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.SchemaRegistry
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.reflect.typeOf
import io.kotest.matchers.string.shouldContain as shouldContainText

/**
 * A component is named after the type's simple name in every source, so
 * two types called the same thing want one name. Registering the second under
 * the first's name publishes a schema for a payload nothing sends, and every
 * consumer of the document — a validator, a generated client, the importer —
 * believes it.
 */
class NameCollisionsTest {

    object Catalogue {
        data class Item(val sku: String)
    }

    object Basket {
        data class Item(val quantity: Int)
    }

    data class Order(val listed: Catalogue.Item, val taken: Basket.Item)

    private val sources = listOf(
        "JacksonCodecs" to JacksonCodecs,
    )

    @TestFactory
    fun `two types wanting one component name are refused, naming both`(): List<DynamicTest> =
        sources.map { (library, codecs) ->
            DynamicTest.dynamicTest(library) {
                val message = refusalFrom(codecs)

                withClue("$library has to say which name is wanted twice, and by what") {
                    message shouldContainText "Item"
                    message shouldContainText "Catalogue"
                    message shouldContainText "Basket"
                }
            }
        }

    private fun refusalFrom(codecs: Codecs): String =
        runCatching { codecs.schema(typeOf<Order>(), SchemaRegistry()) }
            .exceptionOrNull()
            .let { failure ->
                requireNotNull(failure) { "$codecs described two types called Item and said nothing" }
                failure.message.orEmpty()
            }
}
