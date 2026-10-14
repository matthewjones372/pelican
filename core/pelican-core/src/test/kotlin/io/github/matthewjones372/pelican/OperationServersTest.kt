package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class OperationServersTest {

    data class Receipt(val id: Long)

    private val userId = pathParam<Long>("userId")

    @Test
    fun `an endpoint carries the server it declared`() {
        val ep = endpoint(userId) {
            post("users" / userId / "receipts")
            servers("https://uploads.example.com")
            json<Receipt>(status = 201)
        }

        ep.servers shouldBe listOf("https://uploads.example.com")
    }

    /** Which is what almost every endpoint says: the API's own list, whatever it is. */
    @Test
    fun `an endpoint that says nothing declares no server of its own`() {
        val ep = endpoint(userId) {
            get("users" / userId / "receipts")
            json<Receipt>()
        }

        ep.servers.shouldBeEmpty()
    }

    /**
     * OpenAPI allows several and a document is worth republishing as it was
     * read, so they are kept in order. A client picks the first, as it does
     * with the API's own list.
     */
    @Test
    fun `several are kept, in the order they were declared`() {
        val ep = endpoint(userId) {
            get("users" / userId / "receipts")
            servers("https://eu.example.com", "https://us.example.com")
            json<Receipt>()
        }

        ep.servers shouldBe listOf("https://eu.example.com", "https://us.example.com")
    }

    /** A blank URL would build a client that quietly called a relative path. */
    @Test
    fun `a blank server url is refused where it is written`() {
        shouldThrow<IllegalArgumentException> {
            endpoint(userId) {
                get("users" / userId / "receipts")
                servers("")
                json<Receipt>()
            }
        }.message.orEmpty() shouldContain "A server URL is where calls to this endpoint go"
    }
}
