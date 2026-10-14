package io.github.matthewjones372.pelican.jackson

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.SchemaRegistry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** One codec, and so one resolver, describing two documents whose types share a simple name. */
class SeparateDocumentsTest {

    object Gifts {
        data class Card(val message: String)
    }

    object Orders {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
        @JsonSubTypes(
            JsonSubTypes.Type(value = Card::class, name = "card"),
            JsonSubTypes.Type(value = Cash::class, name = "cash"),
        )
        sealed interface PaymentMethod

        data class Card(val number: String) : PaymentMethod

        data class Cash(val amount: Long) : PaymentMethod
    }

    private val codecs = JacksonCodecs(defaultMapper())

    @Test
    fun `a name one document gave a class is free for another document to give a different class`() {
        schemas(typeOf<Gifts.Card>())["Card"].properties() shouldBe setOf("message")

        val orders = schemas(typeOf<Orders.PaymentMethod>())

        orders["Card"].properties() shouldBe setOf("number")
        ((orders["PaymentMethod"] as JsonObj)["discriminator"] as JsonObj)["mapping"] shouldBe JsonObj(
            mapOf(
                "card" to JsonStr("#/components/schemas/Card"),
                "cash" to JsonStr("#/components/schemas/Cash"),
            ),
        )
    }

    @Test
    fun `the first document is described the same after the second`() {
        val before = schemas(typeOf<Gifts.Card>())
        schemas(typeOf<Orders.PaymentMethod>())

        schemas(typeOf<Gifts.Card>()) shouldBe before
    }

    private fun schemas(type: KType): JsonObj {
        val registry = SchemaRegistry()
        codecs.schema(type, registry)
        return registry.all()
    }

    private fun Any?.properties(): Set<String> = ((this as JsonObj)["properties"] as JsonObj).fields.keys
}
