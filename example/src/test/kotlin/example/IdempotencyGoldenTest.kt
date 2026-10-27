package example

import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.test.golden.Golden
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/** What an idempotency key publishes, read by Jackson's schemas rather than hand-written ones. Strict: every line. */
class IdempotencyGoldenTest {

    data class Order(val sku: String, val quantity: Int)

    data class OutOfStock(val sku: String)

    private val outOfStock = errorJson<OutOfStock>(409, "Out of stock").tagged("out_of_stock", field = "error")

    private val placeOrder = endpoint {
        post("orders")
        operationId = "placeOrder"
        idempotencyKey()
        body(jsonBody<Order>())
        json<Order>(201).orFail(outOfStock)
    }

    private val golden =
        Golden(directory = Paths.get("src", "test", "resources", "golden", "idempotency"), strict = true)

    @Test
    fun `an idempotency key publishes its header and the failures answered for it`() {
        golden.document(apiSpec(listOf(placeOrder), schemas = JacksonCodecs) { title = "Orders" })
    }
}
