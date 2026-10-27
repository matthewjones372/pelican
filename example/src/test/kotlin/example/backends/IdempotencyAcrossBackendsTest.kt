package example.backends

import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveHeader
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** One keyed endpoint, the same answers from every backend: replayed, refused, in flight, released. */
class IdempotencyAcrossBackendsTest {

    companion object {
        private val running: Map<String, Running> = runnableBackends.associate { it.name to it.start(port = 0) }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        @JvmStatic
        fun backends(): List<Array<Any>> = runnableBackends.map { arrayOf(it.name, clients.getValue(it.name)) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
        }
    }

    private val key = UUID.randomUUID().toString()

    private fun ApiClient.tipRequest(amount: Int, key: String = this@IdempotencyAcrossBackendsTest.key): RequestSpec =
        request(leaveTip, Tip(amount)).withHeader("Idempotency-Key", key)

    private fun ApiClient.send(request: RequestSpec): ResponseSpec = transport.send(request)

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a repeat is answered with the first receipt, and its request id is its own`(name: String, client: ApiClient) {
        val first = client.send(client.tipRequest(5)).shouldHaveStatus(201)
        val again = client.send(client.tipRequest(5))
            .shouldHaveStatus(201)
            .shouldHaveHeader("Idempotent-Replayed", "true")

        again.body shouldBe first.body
        again.header("X-Request-Id") shouldNotBe first.header("X-Request-Id")
        client.send(client.tipRequest(5, key = "$key-other")).body shouldNotBe first.body
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `whitespace and field order in the body do not make it another request`(name: String, client: ApiClient) {
        val first = client.send(client.tipRequest(5)).shouldHaveStatus(201)

        val spaced = client.tipRequest(5).withBody("{ \"amount\" :\n 5 }")
        val replayed = client.send(spaced).shouldHaveStatus(201).shouldHaveHeader("Idempotent-Replayed", "true")
        replayed.body shouldBe first.body
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the same key with a different tip is a 422`(name: String, client: ApiClient) {
        client.send(client.tipRequest(5)).shouldHaveStatus(201)

        client.send(client.tipRequest(6)).shouldHaveStatus(422).body shouldContain "idempotency_key_reused"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a repeat while the first is still being answered is a 409 with Retry-After`(name: String, client: ApiClient) {
        TipJar.hold()
        val first = CompletableFuture.supplyAsync { client.send(client.tipRequest(HELD_TIP)) }
        try {
            TipJar.awaitHeld()
            client.send(client.tipRequest(HELD_TIP)).shouldHaveStatus(409).shouldHaveHeader("Retry-After", "1")
                .body shouldContain "idempotency_key_in_flight"
        } finally {
            TipJar.release()
        }
        first.get(WAIT_SECONDS, TimeUnit.SECONDS).shouldHaveStatus(201)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a tip the handler throws on gives the key back`(name: String, client: ApiClient) {
        client.send(client.tipRequest(0)).shouldHaveStatus(500)

        // Released, so this is a new request under the key rather than a 422 for a different body.
        client.send(client.tipRequest(5)).shouldHaveStatus(201)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a tip without a key is a 400`(name: String, client: ApiClient) {
        client.send(client.request(leaveTip, Tip(5))).shouldHaveStatus(400)
    }
}

/** Longer than any of these requests takes; reached only if a backend never answers. */
private const val WAIT_SECONDS = 30L
