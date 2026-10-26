package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KType

/** A keyed endpoint served in process: what a repeat is answered with, and when the handler runs again. */
class IdempotentServingTest {

    private object Strings : Codecs {
        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: KType): BodyCodec<T> = object : BodyCodec<String> {
            override fun encodeToString(value: String) = value
            override fun decodeFromString(text: String) = text
        } as BodyCodec<T>

        override fun schema(type: KType, components: SchemaComponents): JsonObj = jsonObj { "type" to "string" }
    }

    private val bearer = bearerAuth()
    private val caller = authenticated(bearer) { it.subject }
    private val order = jsonBody<String>()
    private val soldOut = errorJson<String>(410, "That product is no longer sold")
    private val receipt = responseHeader<String>("X-Receipt")
    private val requestId = responseHeader<String>("X-Request-Id")

    private val placeOrder = endpoint(order) {
        post("orders")
        operationId = "placeOrder"
        authenticatedBy(caller)
        idempotencyKey()
        emits(receipt, requestId)
        json<String>(201).orFail(soldOut)
    }

    private val tipWaiter = endpoint(order) {
        post("tips")
        operationId = "tipWaiter"
        idempotencyKey(required = false)
        json<String>(201)
    }

    private val calls = AtomicInteger()

    /** Set to hold the next answer back, so a repeat can arrive while it is in flight. */
    private var held: CompletableFuture<Any?>? = null

    private val placeOrderHandler = ServerEndpoint(placeOrder) { p ->
        val n = calls.incrementAndGet()
        when (p[order]) {
            "boom" -> error("the warehouse is on fire")

            "gone" -> CompletableFuture.completedFuture(soldOut("no more ${p[order]}"))

            else -> {
                p.setHeader(receipt, "r-$n")
                held ?: CompletableFuture.completedFuture(ok("order $n of ${p[order]} for ${p[caller]}"))
            }
        }
    }

    private val tipHandler = ServerEndpoint(tipWaiter) { _ ->
        CompletableFuture.completedFuture("tip ${calls.incrementAndGet()}" as Any?)
    }

    private val store = InMemoryIdempotencyStore()

    private val requests = AtomicInteger()

    private val transport = InMemoryClientTransport(
        api(listOf(placeOrderHandler, tipHandler), codecs = Strings) {
            authenticate(bearer) { c -> c.bearerToken()?.let { Identity(it) } }
            idempotency(store)
            filter { params, next ->
                // Declared on the endpoint, so only when it was set tells it apart from the handler's own.
                if (params.endpoint === placeOrder) params.setHeader(requestId, "req-${requests.incrementAndGet()}")
                next(params)
            }
        },
    )

    private fun sendAsync(
        body: String,
        key: String? = "k-1",
        who: String = "ada",
        path: String = "orders",
    ): CompletableFuture<ClientResponse> {
        val headers = listOfNotNull(
            key?.let { "Idempotency-Key" to it },
            "Authorization" to "Bearer $who",
            "Content-Type" to "application/json",
        )
        val request = ClientRequest(Method.POST, "http://shop.test/$path", headers, ClientRequest.Body.Text(body))
        return transport.send(request).toCompletableFuture()
    }

    private fun send(body: String, key: String? = "k-1", who: String = "ada", path: String = "orders") =
        sendAsync(body, key, who, path).join()

    @Test
    fun `a repeat is answered with the first response and the handler runs once`() {
        val first = send("tea")
        val again = send("tea")

        first.status shouldBe 201
        again.status shouldBe 201
        again.text() shouldBe first.text()
        again.header("X-Receipt") shouldBe "r-1"
        again.header("Idempotent-Replayed") shouldBe "true"
        first.header("Idempotent-Replayed") shouldBe null
        calls.get() shouldBe 1
    }

    @Test
    fun `a header a filter set belongs to the request it was set on, not to the replay`() {
        send("tea").header("X-Request-Id") shouldBe "req-1"
        send("tea").header("X-Request-Id") shouldBe "req-2"
    }

    @Test
    fun `a declared failure is kept and answered again`() {
        send("gone").status shouldBe 410
        val again = send("gone")

        again.status shouldBe 410
        again.text() shouldBe "no more gone"
        calls.get() shouldBe 1
    }

    @Test
    fun `the same key with a different request is a 422 the handler never sees`() {
        send("tea")
        val reused = send("coffee")

        reused.status shouldBe 422
        reused.text() shouldContain "idempotency_key_reused"
        calls.get() shouldBe 1
    }

    @Test
    fun `a repeat while the first is still running is a 409 with Retry-After`() {
        val answer = CompletableFuture<Any?>()
        held = answer
        val first = sendAsync("tea")

        val repeat = send("tea")
        repeat.status shouldBe 409
        repeat.header("Retry-After") shouldBe "1"
        repeat.text() shouldContain "idempotency_key_in_flight"

        answer.complete(ok("order 1"))
        first.join().status shouldBe 201
        held = null
        send("tea").header("Idempotent-Replayed") shouldBe "true"
        calls.get() shouldBe 1
    }

    @Test
    fun `a handler that throws gives the key back, so a retry runs it again`() {
        send("boom").status shouldBe 500
        send("boom").status shouldBe 500

        calls.get() shouldBe 2
    }

    @Test
    fun `one key from two callers is two requests`() {
        send("tea", who = "ada")
        val grace = send("tea", who = "grace")

        grace.header("Idempotent-Replayed") shouldBe null
        calls.get() shouldBe 2
    }

    @Test
    fun `a required key that is missing is a 400`() {
        send("tea", key = null).status shouldBe 400
        calls.get() shouldBe 0
    }

    @Test
    fun `an optional key left off runs the handler every time`() {
        send("5", key = null, path = "tips")
        send("5", key = null, path = "tips")
        calls.get() shouldBe 2

        send("5", key = "t-1", path = "tips")
        send("5", key = "t-1", path = "tips").header("Idempotent-Replayed") shouldBe "true"
        calls.get() shouldBe 3
    }

    @Test
    fun `a keyed endpoint with nowhere to keep responses is refused when the Api is built`() {
        shouldThrow<IllegalArgumentException> { api(listOf(tipHandler), codecs = Strings) }
            .message shouldContain "idempotency(store"
    }

    @Test
    fun `a keyed endpoint marked nonBlocking is refused, since the store blocks`() {
        shouldThrow<IllegalArgumentException> {
            api(listOf(tipHandler.nonBlocking()), codecs = Strings) { idempotency(store) }
        }.message shouldContain "nonBlocking"
    }
}
