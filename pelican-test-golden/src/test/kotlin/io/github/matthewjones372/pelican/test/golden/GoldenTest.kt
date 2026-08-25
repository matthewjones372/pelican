package io.github.matthewjones372.pelican.test.golden

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The workflow, which is the whole of what this module is: a snapshot nobody
 * has read is never accepted on a test's behalf, and one that stops matching
 * fails loudly enough to say which line moved.
 *
 * The goldens here are written into a `@TempDir` rather than committed — these
 * are tests of the mechanism, and a committed golden of a golden would be a
 * second thing to keep in step for no gain.
 */
class GoldenTest {

    data class Order(val id: Long, val total: String)

    private val getOrder = endpoint(noInputs) {
        get("orders")
        operationId = "getOrder"
        json<Order>()
    }

    private val countOrders = endpoint(noInputs) {
        get("orders" / "count")
        json<Order>()
    }

    private val secretOrders = endpoint(noInputs) {
        get("orders" / "secret")
        hidden = true
        json<Order>()
    }

    private val client = ApiClient(
        transport = object : Transport {
            override fun send(request: RequestSpec) = ResponseSpec(
                status = 200,
                headers = listOf("Content-Type" to "application/json", "Date" to "whenever this ran"),
                body = """{"id":1,"total":"9.99"}""",
            )
        },
        codecs = JacksonCodecs,
    )

    private fun message(block: () -> Unit): String =
        shouldThrow<AssertionError>(block).message.shouldNotBeNull()

    // -------------------------------------------------------- the first run

    @Test
    fun `a missing golden is proposed rather than accepted`(@TempDir dir: Path) {
        val golden = Golden(directory = dir, update = false)

        val failure = message { golden.text("orders", "txt", "the contract") }

        failure shouldContain "no golden file yet"
        failure shouldContain "orders_new.txt"
        Files.readString(dir.resolve("orders_new.txt")) shouldBe "the contract\n"
        Files.exists(dir.resolve("orders.txt")) shouldBe false
    }

    @Test
    fun `the proposal becomes the golden by being renamed, and then it passes`(@TempDir dir: Path) {
        val golden = Golden(directory = dir, update = false)
        message { golden.text("orders", "txt", "the contract") }

        Files.move(dir.resolve("orders_new.txt"), dir.resolve("orders.txt"))

        golden.text("orders", "txt", "the contract")
    }

    // ------------------------------------------------------------ afterwards

    @Test
    fun `a match clears the leftovers from the run that failed`(@TempDir dir: Path) {
        val golden = Golden(directory = dir, update = false)
        Files.writeString(dir.resolve("orders.txt"), "the contract\n")
        Files.writeString(dir.resolve("orders_changed.txt"), "something older\n")

        golden.text("orders", "txt", "the contract")

        Files.exists(dir.resolve("orders_changed.txt")) shouldBe false
    }

    @Test
    fun `a mismatch names the line that moved and keeps both files to diff`(@TempDir dir: Path) {
        val golden = Golden(directory = dir, update = false)
        Files.writeString(dir.resolve("orders.txt"), "GET /orders\nlimit: 20\naccept: json\n")

        val failure = message { golden.text("orders", "txt", "GET /orders\nmax: 20\naccept: json\n") }

        failure shouldContain "line 2"
        failure shouldContain "golden: limit: 20"
        failure shouldContain "now:    max: 20"
        failure shouldContain "accept it with `mv orders_changed.txt orders.txt`"
        Files.readString(dir.resolve("orders_changed.txt")) shouldContain "max: 20"
        Files.readString(dir.resolve("orders.txt")) shouldContain "limit: 20"
    }

    @Test
    fun `a long divergence counts the rest instead of printing all of it`(@TempDir dir: Path) {
        val golden = Golden(directory = dir, update = false)
        Files.writeString(dir.resolve("orders.txt"), (1..20).joinToString("\n") { "line $it" })

        val failure = message { golden.text("orders", "txt", (1..20).joinToString("\n") { "moved $it" }) }

        failure shouldContain "and 17 more differing lines"
    }

    @Test
    fun `a file that grew reports the end of the shorter one rather than an index`(@TempDir dir: Path) {
        val golden = Golden(directory = dir, update = false)
        Files.writeString(dir.resolve("orders.txt"), "GET /orders\n")

        val failure = message { golden.text("orders", "txt", "GET /orders\nlimit: 20\n") }

        failure shouldContain "the file ends here"
    }

    @Test
    fun `update rewrites the golden and clears the proposals`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("orders.txt"), "the old contract\n")
        Files.writeString(dir.resolve("orders_changed.txt"), "a rejected one\n")

        Golden(directory = dir, update = true).text("orders", "txt", "the new contract")

        Files.readString(dir.resolve("orders.txt")) shouldBe "the new contract\n"
        Files.exists(dir.resolve("orders_changed.txt")) shouldBe false
    }

    @Test
    fun `a golden written on Windows still matches what this run produced`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("orders.txt"), "GET /orders\r\nlimit: 20\r\n")

        Golden(directory = dir, update = false).text("orders", "txt", "GET /orders\nlimit: 20")
    }

    // ------------------------------------------------------- what is recorded

    @Test
    fun `the whole document is one file, preamble and all`(@TempDir dir: Path) {
        val api = ApiSpec(endpoints = listOf(getOrder), schemas = JacksonCodecs, title = "Orders")

        message { Golden(directory = dir, update = false).document(api) }

        val proposed = Files.readString(dir.resolve("openapi_new.json"))
        proposed shouldContain "\"openapi\": \"3.1.0\""
        proposed shouldContain "\"/orders\""
    }

    // ---------------------------------------------------------- per endpoint

    private fun spec(
        orders: List<Endpoint<*, *>> = listOf(getOrder, countOrders, secretOrders),
    ) = ApiSpec(endpoints = orders, schemas = JacksonCodecs, title = "Orders")

    @Test
    fun `every endpoint is recorded without a line of test code per endpoint`(@TempDir dir: Path) {
        message { Golden(directory = dir, update = false).operations(spec()) }

        val recorded = Files.list(dir.resolve("operations"))
            .use { files -> files.map { it.fileName.toString() }.sorted().toList() }

        // Named by operationId where there is one, and by the call where there is not.
        recorded shouldBe listOf("get-orders-count_new.json", "getOrder_new.json")
    }

    @Test
    fun `a hidden endpoint is served but not published, so it is not recorded`(@TempDir dir: Path) {
        message { Golden(directory = dir, update = false).operations(spec()) }

        Files.exists(dir.resolve("operations/get-orders-secret_new.json")) shouldBe false
    }

    @Test
    fun `an operation records what it publishes and not the preamble every file would repeat`(@TempDir dir: Path) {
        message { Golden(directory = dir, update = false).operation(spec(), getOrder) }

        val recorded = Files.readString(dir.resolve("operations/getOrder_new.json"))

        recorded shouldContain "\"/orders\""
        recorded shouldContain "\"Order\""
        recorded shouldNotContain "\"openapi\""
        recorded shouldNotContain "\"title\""
    }

    // ------------------------------------------------ the change that breaks

    /** The spec as it is now, with `getOrder` replaced by whatever the test is doing to it. */
    private fun published(dir: Path, orders: List<Endpoint<*, *>> = listOf(getOrder)) =
        Golden(directory = dir, update = true).also { it.operations(spec(orders)) }

    @Test
    fun `a required parameter nobody was sending fails the test, and says who it refuses`(@TempDir dir: Path) {
        published(dir)

        val stricter = endpoint(queryParam<String>("currency")) {
            get("orders")
            operationId = "getOrder"
            json<Order>()
        }

        val failure = message { Golden(directory = dir, update = false).operations(spec(listOf(stricter))) }

        failure shouldContain "1 change breaks callers"
        failure shouldContain "GET /orders"
        failure shouldContain "the `currency` query parameter is new and required"
        failure shouldContain "every caller that is not sending it is refused"
        // The golden still holds the contract that was published, so the next
        // run compares against it and not against the break.
        Files.readString(dir.resolve("operations/getOrder.json")) shouldNotContain "currency"
        Files.readString(dir.resolve("operations/getOrder_changed.json")) shouldContain "currency"
    }

    @Test
    fun `an endpoint that was deleted fails for the callers still holding it`(@TempDir dir: Path) {
        published(dir, listOf(getOrder, countOrders))

        val failure = message { Golden(directory = dir, update = false).operations(spec(listOf(getOrder))) }

        failure shouldContain "GET /orders/count"
        failure shouldContain "gone from the descriptions"
        failure shouldContain "gets a 404"
    }

    @Test
    fun `a webhook that was retired is not described as a 404, because nobody calls it`(@TempDir dir: Path) {
        val sent = webhook("orderPlaced") {
            body(jsonBody<Order>())
            empty(status = 204)
        }
        val announced = ApiSpec(listOf(getOrder), JacksonCodecs, title = "Orders", webhooks = listOf(sent))
        Golden(directory = dir, update = true).operations(announced)

        val failure = message { Golden(directory = dir, update = false).operations(spec(listOf(getOrder))) }

        failure shouldContain "the webhook is gone from the descriptions"
        failure shouldContain "every subscriber registered for it stops being told"
    }

    @Test
    fun `an optional parameter is not a break, so the golden moves and the test passes`(@TempDir dir: Path) {
        published(dir)

        val wider = endpoint(queryParam<String>("currency").optional()) {
            get("orders")
            operationId = "getOrder"
            json<Order>()
        }

        Golden(directory = dir, update = false).operations(spec(listOf(wider)))

        // Passed, and the golden now holds the contract the next change is measured against.
        Files.readString(dir.resolve("operations/getOrder.json")) shouldContain "currency"
        Files.exists(dir.resolve("operations/getOrder_changed.json")) shouldBe false
    }

    @Test
    fun `strict reports the compatible change too, for a document that is published as it stands`(
        @TempDir dir: Path,
    ) {
        published(dir)

        val wider = endpoint(queryParam<String>("currency").optional()) {
            get("orders")
            operationId = "getOrder"
            json<Order>()
        }

        val failure = message {
            Golden(directory = dir, update = false, strict = true).operations(spec(listOf(wider)))
        }

        failure shouldContain "none of them breaking"
        failure shouldContain "is new and optional"
    }

    @Test
    fun `a golden that is not a document says so rather than failing as a diff`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("operations"))
        Files.writeString(dir.resolve("operations/getOrder.json"), "openapi: 3.1.0\n")

        val failure = message { Golden(directory = dir, update = false).operations(spec(listOf(getOrder))) }

        failure shouldContain "is not an OpenAPI document this can read back"
    }

    @Test
    fun `an exchange records what went out and what came back`(@TempDir dir: Path) {
        message { Golden(directory = dir, update = false).exchange("get-order", client, getOrder, Unit) }

        val proposed = Files.readString(dir.resolve("get-order_new.http"))

        proposed shouldContain "GET /orders"
        proposed shouldContain "--- response"
        proposed shouldContain "200"
        // Pretty-printed, so a changed field is a changed line.
        proposed shouldContain "\"total\": \"9.99\""
        // The clock is not part of anybody's contract.
        proposed shouldNotContain "whenever this ran"
    }

    @Test
    fun `a client that only builds pins the URL without anything running`() {
        val calls = requestsOnly(JacksonCodecs)

        calls.request(getOrder, Unit).target shouldBe "/orders"

        val refused = shouldThrow<UnsupportedOperationException> { calls.call(getOrder, Unit) }
        refused.message.shouldNotBeNull() shouldContain "pelican-test-pekko"
    }

    @Test
    fun `a body that is not JSON is recorded exactly as it travelled`() {
        val form = ResponseSpec(200, listOf("Content-Type" to "application/x-www-form-urlencoded"), "id=1&total=9.99")

        form.wireText() shouldContain "id=1&total=9.99"
    }

    @Test
    fun `a body that only looks like JSON is left alone rather than lost`() {
        val broken = ResponseSpec(500, emptyList(), "{ this was never JSON")

        broken.wireText() shouldContain "{ this was never JSON"
    }

    @Test
    fun `which headers count as volatile is the caller's to change`() {
        val response = ResponseSpec(200, listOf("X-Request-Id" to "9f2c", "Content-Type" to "application/json"), "")

        response.wireText(ignoring = setOf("X-Request-Id")) shouldNotContain "9f2c"
        response.wireText() shouldContain "9f2c"
    }
}
