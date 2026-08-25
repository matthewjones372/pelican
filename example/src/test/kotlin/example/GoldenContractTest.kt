package example

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.golden.Golden
import io.github.matthewjones372.pelican.test.golden.requestsOnly
import org.junit.jupiter.api.Test

/**
 * The half of the contract a typed test cannot see.
 *
 * Every other suite here calls the endpoints through the descriptions, which is
 * what makes a rename a compile error rather than a runtime 404 — and is also
 * why none of them would notice `/users/{userId}/orders` becoming
 * `/customers/{userId}/orders`. Both sides move together and the suite stays
 * green, while the callers already deployed against the old path do not.
 *
 * These snapshots are the second reader, and the first of them is not written
 * per endpoint: `operations` walks the spec, so the seventeen endpoints and the
 * webhook below are covered by one line, and an endpoint added tomorrow is
 * covered by the same line without anybody remembering to.
 *
 * Nothing is running: the calls are built straight from the descriptions, so
 * this whole suite costs no server and no port.
 */
class GoldenContractTest {

    private val golden = Golden()
    private val calls = requestsOnly(JacksonCodecs)

    @Test
    fun `every endpoint publishes what it published`() {
        golden.operations(ordersSpec())
    }

    /**
     * The same comparison over the whole document, because this one is also a
     * build artifact: `checkOrdersDocument` compares the descriptions against
     * this very file, so a break is caught by `./gradlew check` as well as by
     * the suite. One committed contract, read by both.
     */
    @Test
    fun `the published document is the one that was reviewed`() {
        golden.document(ordersSpec())
    }

    @Test
    fun `listing a user's orders builds the call its callers hold`() {
        golden.request("list-orders", calls.request(listOrders, In2(7L, 20)))
    }

    /**
     * A body and a header as well as a path — the encoded JSON is in the
     * snapshot, so a codec that starts spelling a field differently is a diff
     * here rather than a deserialisation failure in somebody else's service.
     */
    @Test
    fun `placing an order records the body that goes with it`() {
        golden.request(
            "place-order",
            calls.request(placeOrder, In3(7L, "an-api-key", CreateOrder(item = "a-widget", quantity = 2))),
        )
    }
}
