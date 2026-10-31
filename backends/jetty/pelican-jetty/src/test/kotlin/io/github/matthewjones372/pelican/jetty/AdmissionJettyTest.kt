package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.AdmissionDecision
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiBuilder
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.IDEMPOTENCY_KEY_HEADER
import io.github.matthewjones372.pelican.IdempotencyScope
import io.github.matthewjones372.pelican.IdempotencyStore
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.InMemoryIdempotencyStore
import io.github.matthewjones372.pelican.NoCodecs
import io.github.matthewjones372.pelican.OperationAdmission
import io.github.matthewjones372.pelican.RateLimitStore
import io.github.matthewjones372.pelican.RefusalBody
import io.github.matthewjones372.pelican.Reservation
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.Taken
import io.github.matthewjones372.pelican.TenantDirectory
import io.github.matthewjones372.pelican.TenantResolver
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.cors
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.ndjsonIn
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.rateLimit
import io.github.matthewjones372.pelican.rawBody
import io.github.matthewjones372.pelican.tenantFrom
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.eclipse.jetty.http.HttpTester
import org.eclipse.jetty.http.HttpVersion
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.LocalConnector
import org.eclipse.jetty.server.Server
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.minutes

class AdmissionJettyTest {
    @ParameterizedTest
    @ValueSource(strings = ["capture", "query", "json", "credentials", "content-type", "accept"])
    fun `refusal precedes malformed inputs and every downstream gate`(bad: String) {
        val world = AdmissionWorld().apply { poison = true }
        val path = when (bad) {
            "capture" -> world.valid.replace(ADMISSION_ID, "not-a-uuid")
            "query" -> world.valid.replace("number=1", "number=bad")
            else -> world.valid
        }
        val headers = when (bad) {
            "content-type" -> listOf("Content-Type" to "application/unknown")
            "accept" -> ADMISSION_HEADERS + ("Accept" to "application/unknown")
            "credentials" -> ADMISSION_HEADERS + ("Authorization" to "Bearer invalid")
            else -> ADMISSION_HEADERS
        }
        world.service(world.hook(AdmissionDecision.Refuse(421))).admissionOverJetty { connector ->
            val answer = connector.admissionExchange(path, headers = headers, body = "{")
            answer.status shouldBe 421
            (answer.content ?: "") shouldBe ""
            world.events shouldContainExactly listOf("admission")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Continue checks access before captures while absent admission retains its order`(admitted: Boolean) {
        val world = AdmissionWorld().apply { identity = null }
        world.service(if (admitted) world.hook() else null).admissionOverJetty { connector ->
            val answer = connector.admissionExchange(world.valid.replace(ADMISSION_ID, "bad"))
            answer.status shouldBe if (admitted) 401 else 400
            world.events.count { it == "admission" } shouldBe if (admitted) 1 else 0
            world.events.count { it == "auth" } shouldBe if (admitted) 1 else 0
            world.events.contains("body") shouldBe false
        }
    }

    @Test
    fun `Continue preserves the native keyed sequence and authentication count`() {
        val world = AdmissionWorld()
        val expected = world.service(null).admissionOverJetty { connector ->
            connector.admissionExchange(world.valid).also { it.status shouldBe 200 }.content
        }
        val native = world.events.toList()
        world.events.clear()
        world.service(world.hook()).admissionOverJetty { connector ->
            val answer = connector.admissionExchange(world.valid)
            answer.status shouldBe 200
            answer.content shouldBe expected
            world.events.first() shouldBe "admission"
            world.events.drop(1) shouldContainExactly native
            world.events.count { it == "admission" } shouldBe 1
            world.events.count { it == "auth" } shouldBe native.count { it == "auth" }
            world.events.count { it == "handler" } shouldBe 1
            (world.events.indexOf("auth") < world.events.indexOf("body")) shouldBe true
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `supplied refusals preserve exact bytes media parameters headers and the observer boundary`(hasBody: Boolean) {
        val world = AdmissionWorld().apply { poison = true }
        val body = if (hasBody) RefusalBody("application/problem+json; charset=utf-8", byteArrayOf(0, 1, 42)) else null
        val decision = AdmissionDecision.Refuse(421, body, listOf("X-Choice" to "one", "X-Choice" to "two"))
        world.service(world.hook(decision)).admissionOverJetty { connector ->
            val answer = connector.admissionExchange(world.valid)
            answer.status shouldBe 421
            (answer.contentBytes ?: ByteArray(0)).toList() shouldBe (body?.bytes ?: ByteArray(0)).toList()
            if (hasBody) {
                answer.get("Content-Type") shouldBe body?.mediaType
            } else answer.get("Content-Type").shouldBeNull()
            answer.getValuesList("X-Choice") shouldContainExactly listOf("one", "two")
            answer.get("X-Global") shouldBe "yes"
            world.events shouldContainExactly listOf("admission")
        }
    }

    @Test
    fun `the selected description and complete field lines reach immutable snapshots`() {
        val world = AdmissionWorld()
        val seen = AtomicReference<List<String>>()
        val hook = OperationAdmission { request ->
            world.events += "admission"
            request.endpoint shouldBeSameInstanceAs world.operation
            request.header("x-audience") shouldBe "one"
            seen.set(request.headers("X-AUDIENCE"))
            AdmissionDecision.Refuse(403)
        }
        world.service(hook).admissionOverJetty { connector ->
            connector.admissionExchange(
                world.valid, headers = ADMISSION_HEADERS + listOf("X-Audience" to "one", "x-audience" to "two"),
            ).status shouldBe 403
        }
        seen.get() shouldContainExactly listOf("one", "two")
        shouldThrow<UnsupportedOperationException> { (seen.get() as MutableList<*>).clear() }
        world.events shouldContainExactly listOf("admission")
    }

    @ParameterizedTest
    @ValueSource(strings = ["unmatched", "method"])
    fun `unmatched requests keep native routing and never invoke admission`(kind: String) {
        val world = AdmissionWorld()
        world.service(world.hook()).admissionOverJetty { connector ->
            val answer = connector.admissionExchange(
                if (kind == "unmatched") "/missing" else world.valid,
                method = if (kind == "method") "GET" else "POST",
            )
            answer.status shouldBe if (kind == "method") 405 else 404
            world.events.contains("admission") shouldBe false
            world.events.contains("auth") shouldBe false
        }
    }

    @Test
    fun `Continue preserves the native strict body limit`() {
        val statuses = listOf(false, true).map { admitted ->
            val world = AdmissionWorld()
            world.service(if (admitted) world.hook() else null) { maxBodyBytes = 1 }.admissionOverJetty { connector ->
                val answer = connector.admissionExchange(world.valid)
                world.events.contains("body") shouldBe false
                world.events.contains("handler") shouldBe false
                world.events.count { it == "admission" } shouldBe if (admitted) 1 else 0
                answer.status
            }
        }
        statuses shouldContainExactly listOf(413, 413)
    }

    @ParameterizedTest
    @ValueSource(strings = ["cancel", "interrupt", "defect"])
    fun `callback failures preserve the native failure path and original report once`(kind: String) {
        val failure = when (kind) {
            "cancel" -> CancellationException("private-marker")
            "interrupt" -> InterruptedException("private-marker")
            else -> AssertionError("private-marker")
        }
        listOf(false, true).forEach { admitted ->
            val checked = AtomicInteger()
            val ran = AtomicInteger()
            val report = AtomicReference<Pair<Any?, Throwable>>()
            val reported = AtomicInteger()
            val scheme = bearerAuth()
            val caller = authenticated(scheme) { it.subject }
            val operation = endpoint { get("failure"); authenticatedBy(caller); text() }
            val service = api(listOf(operation handledNow { ran.incrementAndGet(); "unexpected" }), JacksonCodecs) {
                authenticate(scheme, Authenticator { throw failure })
                if (admitted) admission(OperationAdmission { checked.incrementAndGet(); throw failure })
                onError { _, selected, thrown -> report.set(selected to thrown); reported.incrementAndGet() }
            }
            service.admissionOverJetty { connector ->
                val answer = connector.admissionExchange("/failure", method = "GET", body = null)
                answer.status shouldBe 500
                answer.content.shouldNotContain("private-marker")
                report.get().first shouldBeSameInstanceAs operation
                report.get().second shouldBeSameInstanceAs failure
                reported.get() shouldBe 1
                checked.get() shouldBe if (admitted) 1 else 0
                ran.get() shouldBe 0
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["duplicate-host", "case-host", "authority", "framing"])
    fun `native authority and framing refusals precede admission`(kind: String) {
        val checked = AtomicInteger()
        val operation = endpoint { get("transport"); text() }
        val service = api(listOf(operation handledNow { error("handler ran") })) {
            admission(OperationAdmission { checked.incrementAndGet(); AdmissionDecision.Refuse(403) })
        }
        val target = if (kind == "authority") "http://one.example/transport" else "/transport"
        val fields = when (kind) {
            "duplicate-host" -> "Host: one.example\r\nHost: two.example\r\n"
            "case-host" -> "Host: one.example\r\nhOsT: two.example\r\n"
            "authority" -> "Host: two.example\r\n"
            else -> "Host: unit\r\nContent-Length: 1\r\nContent-Length: 2\r\n"
        }
        service.admissionOverJetty { connector ->
            val request = "GET $target HTTP/1.1\r\n${fields}Connection: close\r\n\r\nx"
            val answer = HttpTester.parseResponse(connector.getResponse(request))
            answer.status shouldBe 400
            checked.get() shouldBe 0
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `matched refusal keeps CORS and global headers while native preflight skips admission`(preflight: Boolean) {
        val checked = AtomicInteger()
        val operation = endpoint { get("cors"); text() }
        val origin = "https://console.example"
        val service = api(listOf(operation handledNow { error("handler ran") })) {
            admission(OperationAdmission { checked.incrementAndGet(); AdmissionDecision.Refuse(403) })
            cors = cors(origin)
            responseHeaders { add("X-Global", "yes") }
        }
        service.admissionOverJetty { connector ->
            val answer = connector.admissionExchange(
                "/cors", if (preflight) "OPTIONS" else "GET",
                listOf("Origin" to origin, "Access-Control-Request-Method" to "GET"), body = null,
            )
            answer.status shouldBe if (preflight) 204 else 403
            answer.get("Access-Control-Allow-Origin") shouldBe origin
            if (!preflight) answer.get("X-Global") shouldBe "yes"
            checked.get() shouldBe if (preflight) 0 else 1
        }
    }

    @ParameterizedTest
    @CsvSource("native,0,3,200", "continue,3,3,200", "refuse,3,0,403")
    fun `hidden raw framed and SSE descriptions retain native Continue or early refusal`(
        mode: String,
        expectedChecks: Int,
        expectedRuns: Int,
        expectedStatus: Int,
    ) {
        val refused = expectedStatus == 403
        val checked = AtomicInteger()
        val ran = AtomicInteger()
        val raw = endpoint(rawBody()) { post("raw"); hidden = true; text() }
        val frames = endpoint(ndjsonIn<String>()) { post("frames"); hidden = true; text() }
        val stream = endpoint { get("events"); hidden = true; sse<String>() }
        val service = api(
            listOf(
                raw handledNow { body ->
                    ran.incrementAndGet()
                    Content.Source.asInputStream(body.toSource()).use { it.readAllBytes().decodeToString() }
                },
                frames handledNow { body ->
                    ran.incrementAndGet()
                    body.reader().use { it.asSequence().joinToString("|") }
                },
                stream streamedNow { ran.incrementAndGet(); sequenceOf("event") },
            ),
            JacksonCodecs,
        ) {
            if (mode != "native") admission(
                OperationAdmission { request ->
                    listOf(raw, frames, stream).any { it === request.endpoint } shouldBe true
                    checked.incrementAndGet()
                    if (refused) AdmissionDecision.Refuse(403) else AdmissionDecision.Continue
                },
            )
            maxBodyBytes = 1
            maxFrameBytes = 64
        }
        service.admissionOverJetty { connector ->
            val rawAnswer = connector.admissionExchange(
                "/raw", headers = listOf("Content-Type" to "application/octet-stream"), body = "payload",
            )
            rawAnswer.status shouldBe expectedStatus
            (rawAnswer.content ?: "") shouldBe if (refused) "" else "payload"
            val frameAnswer = connector.admissionExchange(
                "/frames", headers = listOf("Content-Type" to "application/x-ndjson"), body = "\"one\"\n\"two\"\n",
            )
            frameAnswer.status shouldBe expectedStatus
            (frameAnswer.content ?: "") shouldBe if (refused) "" else "one|two"
            val streamed = connector.admissionExchange("/events", "GET", body = null)
            streamed.status shouldBe expectedStatus
            if (refused) (streamed.content ?: "") shouldBe "" else streamed.content.shouldContain("data: \"event\"")
            checked.get() shouldBe expectedChecks
            ran.get() shouldBe expectedRuns
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Continue preserves explicit and inherited native NDJSON frame limits`(explicit: Boolean) {
        val operation = endpoint(ndjsonIn<String>()) { post("limited"); text() }
        val statuses = listOf(false, true).map { admitted ->
            val checked = AtomicInteger()
            val service = api(
                listOf(operation handledNow { body -> body.reader().use { it.asSequence().joinToString() } }),
                JacksonCodecs,
            ) {
                if (admitted) admission(OperationAdmission { checked.incrementAndGet(); AdmissionDecision.Continue })
                if (explicit) maxFrameBytes = 2 else maxBodyBytes = 1
            }
            service.admissionOverJetty { connector ->
                val answer = connector.admissionExchange(
                    "/limited", headers = listOf("Content-Type" to "application/x-ndjson"), body = "\"long\"\n",
                )
                checked.get() shouldBe if (admitted) 1 else 0
                answer.status
            }
        }
        statuses shouldContainExactly listOf(413, 413)
    }
}

private const val ADMISSION_ID = "019f1285-6500-7000-8000-000000000001"
private val ADMISSION_HEADERS = listOf("Content-Type" to "application/json", IDEMPOTENCY_KEY_HEADER to "synthetic")

internal class AdmissionWorld {
    val events = mutableListOf<String>()
    var poison = false
    var identity: Identity? = Identity("synthetic")
    private val scheme = bearerAuth()
    private val caller = authenticated(scheme) { it.subject }
    private val tenant = tenantFrom(TenantResolver { mark("tenant"); "one" })
    private val id = pathParam<UUID>("id")
    private val number = queryParam<Int>("number")
    private val payload = jsonBody<String>()
    val operation = endpoint(id, number, payload) {
        post("admission" / id)
        authenticatedBy(caller)
        tenanted(tenant)
        rateLimited(rateLimit("admission", 1, 1.minutes))
        idempotencyKey()
        text()
    }
    val valid = "/admission/$ADMISSION_ID?number=1"

    fun hook(decision: AdmissionDecision = AdmissionDecision.Continue) = OperationAdmission {
        events += "admission"; decision
    }

    fun service(hook: OperationAdmission?, configure: ApiBuilder.() -> Unit = {}): Api {
        val memory = InMemoryIdempotencyStore(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val json = JacksonCodecs.codec<String>(typeOf<String>())
        return api(
            listOf(
                ServerEndpoint(operation) { p ->
                    mark("handler")
                    CompletableFuture.completedFuture("${p[id]}/${p[number]}/${p[payload]}")
                },
            ),
            object : Codecs by NoCodecs {
                /** This fixture declares only String JSON inputs. */
                @Suppress("UNCHECKED_CAST")
                override fun <T> codec(type: KType): BodyCodec<T> = object : BodyCodec<String> {
                    override fun encodeToString(value: String) = json.encodeToString(value)
                    override fun decodeFromString(text: String): String {
                        mark("body")
                        return json.decodeFromString(text)
                    }
                } as BodyCodec<T>
            },
        ) {
            if (hook != null) admission(hook)
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

    private fun mark(name: String) { events += name; check(!poison) { "downstream $name" } }
}

internal fun LocalConnector.admissionExchange(
    path: String,
    method: String = "POST",
    headers: List<Pair<String, String>> = ADMISSION_HEADERS,
    body: String? = "\"body\"",
): HttpTester.Response {
    val request = HttpTester.newRequest()
    request.method = method
    request.uri = path
    request.version = HttpVersion.HTTP_1_1
    if (headers.none { it.first.equals("Host", ignoreCase = true) }) request.put("Host", "unit")
    headers.forEach { (name, value) -> request.add(name, value) }
    body?.let { request.setContent(it) }
    return HttpTester.parseResponse(getResponse(request.generate()))
}

internal fun <A> Api.admissionOverJetty(
    handlers: Handlers = Handlers.onDispatcher,
    wrap: (Handler) -> Handler = { it },
    block: (LocalConnector) -> A,
): A {
    val handler = wrap(toHandler(handlers))
    val server = Server()
    val connector = LocalConnector(
        server,
        HttpConnectionFactory(HttpConfiguration().apply { uriCompliance = PELICAN_URI_COMPLIANCE }),
    )
    server.addConnector(connector)
    server.handler = handler
    server.defaultHandler = Unanswered()
    return try {
        server.start()
        block(connector)
    } finally {
        server.stop()
    }
}
