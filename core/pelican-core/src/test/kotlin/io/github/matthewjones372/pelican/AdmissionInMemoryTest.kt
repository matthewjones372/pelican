package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KType
import kotlin.time.Duration.Companion.minutes

class AdmissionInMemoryTest {
    private val events = mutableListOf<String>()
    private var poison = false
    private var identity: Identity? = Identity("synthetic")
    private val scheme = bearerAuth()
    private val caller = authenticated(scheme) { it.subject }
    private val tenant = tenantFrom(TenantResolver { mark("tenant"); "one" })
    private val id = pathParam<UUID>("id")
    private val number = queryParam<Int>("number")
    private val payload = jsonBody<String>()
    private val operation = endpoint(id, number, payload) {
        post("items" / id)
        authenticatedBy(caller)
        tenanted(tenant)
        rateLimited(rateLimit("admission", 1, 1.minutes))
        idempotencyKey()
        text()
    }
    private val valid = "http://unit/items/019f1285-6500-7000-8000-000000000001?number=1"
    private val headers = listOf("Content-Type" to "application/json", IDEMPOTENCY_KEY_HEADER to "synthetic")

    @ParameterizedTest
    @ValueSource(strings = ["capture", "query", "json"])
    fun `refusal precedes malformed inputs and every downstream gate`(bad: String) {
        poison = true
        val url = when (bad) {
            "capture" -> "http://unit/items/not-a-uuid?number=1"
            "query" -> valid.replace("number=1", "number=bad")
            else -> valid
        }
        val answer = send(service(hook(AdmissionDecision.Refuse(403))), url, body = ClientRequest.Body.Text("{"))
        answer.status shouldBe 403
        answer.text() shouldBe ""
        events shouldContainExactly listOf("admission")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `opted in Continue checks access before captures and absent admission retains its order`(admitted: Boolean) {
        identity = null
        val answer =
            send(service(if (admitted) hook() else null), valid.replace("019f1285-6500-7000-8000-000000000001", "bad"))
        answer.status shouldBe if (admitted) 401 else 400
        answer.text()
        events.count { it == "admission" } shouldBe if (admitted) 1 else 0
        events.count { it == "auth" } shouldBe if (admitted) 1 else 0
        events.contains("body") shouldBe false
    }

    @Test
    fun `Continue decodes native inputs and reaches the existing chain once`() {
        val native = send(service(null))
        native.status shouldBe 200
        val expected = native.text()
        val nativeEvents = events.toList()
        events.clear()
        val answer = send(service(hook()))
        answer.status shouldBe 200
        answer.text() shouldBe expected
        events.first() shouldBe "admission"
        events.drop(1) shouldContainExactly nativeEvents
        events.count { it == "admission" } shouldBe 1
        events.count { it == "handler" } shouldBe 1
        (events.indexOf("auth") < events.indexOf("body")) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `supplied refusals preserve empty bodies bytes headers and the observer boundary`(hasBody: Boolean) {
        val supplied = if (hasBody) RefusalBody("application/problem+json", "{}".toByteArray()) else null
        val decision = AdmissionDecision.Refuse(421, supplied, listOf("X-Choice" to "first", "X-Choice" to "second"))
        val answer = send(service(hook(decision)), body = ClientRequest.Body.Streaming { error("body opened") })
        answer.status shouldBe 421
        answer.text() shouldBe if (hasBody) "{}" else ""
        if (hasBody) answer.header("Content-Type") shouldBe "application/problem+json"
        else answer.header("Content-Type").shouldBeNull()
        answer.headers.filter { it.first == "X-Choice" }.map { it.second } shouldContainExactly
            listOf("first", "second")
        answer.header("X-Global") shouldBe "yes"
        events shouldContainExactly listOf("admission")
    }

    @Test
    fun `the selected description and every header line reach the immutable request view`() {
        var seen = emptyList<String>()
        val fields = (headers + listOf("Host" to "one", "hOsT" to "two")).toMutableList()
        val check = OperationAdmission { request ->
            events += "admission"
            request.endpoint shouldBeSameInstanceAs operation
            request.header("host") shouldBe "one"
            seen = request.headers("HOST")
            AdmissionDecision.Refuse(403)
        }
        send(service(check), fields = fields).text() shouldBe ""
        fields += "Host" to "later"
        seen shouldContainExactly listOf("one", "two")
        shouldThrow<UnsupportedOperationException> { (seen as MutableList<String>).add("changed") }
        events shouldContainExactly listOf("admission")
    }

    @ParameterizedTest
    @ValueSource(strings = ["unmatched", "method"])
    fun `unmatched requests retain native routing without admission`(kind: String) {
        val answer = send(
            service(hook()), if (kind == "unmatched") "http://unit/missing" else valid,
            method = if (kind == "method") Method.GET else Method.POST,
        )
        answer.status shouldBe if (kind == "method") 405 else 404
        answer.text()
        events.contains("admission") shouldBe false
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Continue retains unsupported raw calls and Refuse never opens their body`(frames: Boolean) {
        val raw = if (frames) endpoint(ndjsonIn<String>()) { post("raw"); empty() }
        else endpoint(rawBody()) { post("raw"); empty() }
        val body = ClientRequest.Body.Streaming { error("raw body opened") }
        shouldThrow<UnsupportedInMemoryCall> { send(service(hook(), raw), "http://unit/raw", body = body) }
        events shouldContainExactly listOf("admission")
        events.clear()
        val refusal = send(service(hook(AdmissionDecision.Refuse(403)), raw), "http://unit/raw", body = body)
        refusal.status shouldBe 403
        refusal.text() shouldBe ""
        events shouldContainExactly listOf("admission")
    }

    @Test
    fun `Continue preserves native body limits and measured stream ownership`() {
        val observed = listOf(false, true).map { admitted ->
            events.clear()
            var closed = false
            var opened = 0
            val stream = object : ByteArrayInputStream("oversized".toByteArray()) {
                override fun close() { closed = true; super.close() }
            }
            try {
                val api = service(if (admitted) hook() else null) { maxBodyBytes = 1 }
                val answer = send(api, body = ClientRequest.Body.Streaming { opened++; stream })
                answer.status shouldBe 413
                answer.text()
                opened shouldBe 1
                events.count { it == "admission" } shouldBe if (admitted) 1 else 0
                events.contains("handler") shouldBe false
                closed
            } finally {
                stream.close()
            }
        }
        observed[1] shouldBe observed[0]
    }

    @ParameterizedTest
    @ValueSource(strings = ["cancel", "interrupt", "defect"])
    fun `callback failures use the native failure boundary and report once`(kind: String) {
        val failure = when (kind) {
            "cancel" -> CancellationException("private-marker")
            "interrupt" -> InterruptedException("private-marker")
            else -> AssertionError("private-marker")
        }
        listOf(false, true).forEach { admitted ->
            var checked = 0
            var ran = 0
            val reported = mutableListOf<Throwable>()
            val endpoint = endpoint { get("failure"); text() }
            val api = api(listOf(ServerEndpoint(endpoint) { ran++; throw failure })) {
                if (admitted) admission(OperationAdmission { checked++; throw failure })
                onError { _, _, thrown -> reported += thrown }
            }
            val answer = send(api, "http://unit/failure", method = Method.GET)
            answer.status shouldBe 500
            answer.text().shouldNotContain("private-marker")
            reported.single() shouldBeSameInstanceAs failure
            checked shouldBe if (admitted) 1 else 0
            ran shouldBe if (admitted) 0 else 1
        }
    }

    private fun mark(name: String) { events += name; check(!poison) { "downstream $name" } }
    private fun hook(decision: AdmissionDecision = AdmissionDecision.Continue) = OperationAdmission {
        events += "admission"; decision
    }
    private fun service(
        check: OperationAdmission?, endpoint: Endpoint<*, *> = operation,
        configure: ApiBuilder.() -> Unit = {},
    ): Api {
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        val memory = InMemoryIdempotencyStore(clock)
        return api(
            listOf(
                ServerEndpoint(endpoint) { p ->
                    mark("handler")
                    CompletableFuture.completedStage(
                        if (endpoint ===
                            operation
                        ) "${p[id]}/${p[number]}/${p[payload]}" else "raw",
                    )
                },
            ),
            object : Codecs by NoCodecs {
                /** This fixture declares only String JSON bodies. */
                @Suppress("UNCHECKED_CAST")
                override fun <T> codec(type: KType): BodyCodec<T> = object : BodyCodec<String> {
                    override fun encodeToString(value: String) = value
                    override fun decodeFromString(text: String): String { mark("body"); return text }
                } as BodyCodec<T>
            },
        ) {
            if (check != null) admission(check)
            authenticate(scheme, Authenticator { mark("auth"); identity })
            tenants(tenant, TenantDirectory { mark("directory"); true }, claim = null)
            rateLimits(RateLimitStore { _, _, _ -> mark("quota"); Taken.Allowed(1) })
            idempotency(object : IdempotencyStore by memory {
                override fun reserve(scope: IdempotencyScope, fingerprint: String, lease: Duration): Reservation {
                    mark("reservation"); return memory.reserve(scope, fingerprint, lease)
                }
            })
            filter(before { mark("filter") })
            onRefusal { _, _, _ -> events += "observer" }
            responseHeaders { add("X-Global", "yes"); add("X-Choice", "global") }
            configure()
        }
    }
    private fun send(
        api: Api, url: String = valid, fields: List<Pair<String, String>> = headers,
        body: ClientRequest.Body = ClientRequest.Body.Text("body"), method: Method = Method.POST,
    ) =
        InMemoryClientTransport(api).send(ClientRequest(method, url, fields, body)).toCompletableFuture().join()
}
