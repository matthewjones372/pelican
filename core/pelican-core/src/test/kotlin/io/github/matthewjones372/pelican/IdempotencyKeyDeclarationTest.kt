package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class IdempotencyKeyDeclarationTest {

    data class Order(val sku: String)

    private val conflict = errorJson<ApiError>(409, "Out of stock")

    @Test
    fun `a key is an Idempotency-Key header, required unless said otherwise`() {
        val placeOrder = endpoint {
            post("orders")
            idempotencyKey()
            json<Order>(201)
        }

        val key = placeOrder.idempotency.shouldNotBeNull()
        key.header.name shouldBe "Idempotency-Key"
        key.required shouldBe true
        placeOrder.headerParams shouldContainExactly listOf(key.header)
    }

    @Test
    fun `an optional key is an optional header`() {
        val placeOrder = endpoint {
            post("orders")
            idempotencyKey(required = false)
            json<Order>(201)
        }

        placeOrder.idempotency.shouldNotBeNull().required shouldBe false
        placeOrder.headerParams.single().required shouldBe false
    }

    @Test
    fun `an endpoint without one has none`() {
        endpoint { post("orders"); json<Order>(201) }.idempotency.shouldBeNull()
    }

    @Test
    fun `a key declares the in-flight 409 and the reused-key 422, each tagged`() {
        val placeOrder = endpoint {
            post("orders")
            idempotencyKey()
            json<Order>(201)
        }
        val key = placeOrder.idempotency.shouldNotBeNull()

        key.inFlight.status shouldBe 409
        key.inFlight.tag shouldBe "idempotency_key_in_flight"
        key.inFlight.headers.map { it.name } shouldBe listOf("Retry-After")
        key.reused.status shouldBe 422
        key.reused.tag shouldBe "idempotency_key_reused"
        placeOrder.errors shouldContainExactly listOf(key.inFlight, key.reused)
    }

    @Test
    fun `a failure of the endpoint's own may share 409 when it is tagged on the same field`() {
        val outOfStock = conflict.tagged("out_of_stock", field = "error")
        val placeOrder = endpoint {
            post("orders")
            idempotencyKey()
            json<Order>(201).orFail(outOfStock)
        }

        placeOrder.errors.filter { it.status == 409 }.map { it.tag } shouldBe
            listOf("idempotency_key_in_flight", "out_of_stock")
    }

    @Test
    fun `an untagged failure of its own under 409 is refused, naming the tag to add`() {
        val e = shouldThrow<IllegalStateException> {
            endpoint {
                post("orders")
                idempotencyKey()
                json<Order>(201).orFail(conflict)
            }
        }
        e.message shouldContain "409"
    }

    @Test
    fun `a method that is already idempotent is refused`() {
        val e = shouldThrow<IllegalStateException> {
            endpoint { put("orders"); idempotencyKey(); json<Order>() }
        }
        e.message shouldContain "PUT is idempotent already"
    }

    @Test
    fun `a streamed body or response is refused`() {
        shouldThrow<IllegalStateException> {
            endpoint { post("upload"); body(rawBody()); idempotencyKey(); empty(204) }
        }.message shouldContain "streams"

        shouldThrow<IllegalStateException> {
            endpoint { post("orders"); idempotencyKey(); ndjson<Order>() }
        }.message shouldContain "streams"
    }

    @Test
    fun `a second declaration is refused`() {
        shouldThrow<IllegalArgumentException> {
            endpoint { post("orders"); idempotencyKey(); idempotencyKey(); json<Order>(201) }
        }
    }

    @Test
    fun `a header of its own named Idempotency-Key is refused as a second declaration of one header`() {
        shouldThrow<IllegalStateException> {
            endpoint {
                post("orders")
                header(headerParam<String>("idempotency-key"))
                idempotencyKey()
                json<Order>(201)
            }
        }
    }
}
