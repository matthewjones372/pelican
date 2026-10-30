package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.AdmissionDecision
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.OperationAdmission
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.matchers.shouldBe
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.NetworkConnector
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AdmissionJettyExecutionTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `admission alone moves an asynchronous endpoint to the requested blocking policy`(virtual: Boolean) {
        Executors.newSingleThreadExecutor(Thread.ofPlatform().name("admission-owned").factory()).use { executor ->
            val handlers = if (virtual) Handlers.onVirtualThreads else Handlers.on(executor)
            val operation = endpoint { get("plain"); text() }
            val checked = AtomicInteger()
            val service = api(listOf(operation handledBy { CompletableFuture.completedFuture("ok") }), JacksonCodecs) {
                admission(
                    OperationAdmission {
                        checked.incrementAndGet()
                        if (virtual) Thread.currentThread().isVirtual shouldBe true
                        else Thread.currentThread().name shouldBe "admission-owned"
                        AdmissionDecision.Continue
                    },
                )
            }
            service.admissionOverJetty(handlers) { connector ->
                connector.admissionExchange("/plain", "GET", body = null).status shouldBe 200
                checked.get() shouldBe 1
            }
        }
    }

    @ParameterizedTest
    @CsvSource("DISPATCHER,true", "DISPATCHER,false", "VIRTUAL,true", "VIRTUAL,false", "OWNED,true", "OWNED,false")
    fun `admission uses the native caller execution policy for synchronous and asynchronous handlers`(
        policy: String,
        synchronous: Boolean,
    ) {
        Executors.newSingleThreadExecutor(Thread.ofPlatform().name("admission-owned").factory()).use { executor ->
            val handlers = when (policy) {
                "DISPATCHER" -> Handlers.onDispatcher
                "VIRTUAL" -> Handlers.onVirtualThreads
                else -> Handlers.on(executor)
            }
            fun context(): String = when {
                Thread.currentThread().isVirtual -> "virtual"
                Thread.currentThread().name == "admission-owned" -> "owned"
                else -> "dispatcher"
            }
            fun run(admitted: Boolean): List<String> {
                val events = mutableListOf<String>()
                val checked = AtomicInteger()
                val scheme = bearerAuth()
                val caller = authenticated(scheme) { it.subject }
                val operation = endpoint { get("execution"); authenticatedBy(caller); text() }
                val binding = if (synchronous) operation handledNow {
                    events += "handler:${context()}"; "ok"
                } else operation handledBy {
                    events += "handler:${context()}"; CompletableFuture.completedFuture("ok")
                }
                val service = api(listOf(binding), JacksonCodecs) {
                    authenticate(scheme, Authenticator { events += "caller:${context()}"; Identity("synthetic") })
                    if (admitted) admission(
                        OperationAdmission {
                            checked.incrementAndGet(); events += "admission:${context()}"; AdmissionDecision.Continue
                        },
                    )
                }
                service.admissionOverJetty(handlers) { connector ->
                    val answer = connector.admissionExchange(
                        "/execution", "GET", listOf("Authorization" to "Bearer synthetic"), null,
                    )
                    answer.status shouldBe 200
                    checked.get() shouldBe if (admitted) 1 else 0
                }
                return events
            }
            val native = run(false)
            val admitted = run(true)
            admitted.drop(1) shouldBe native
            admitted.first().substringAfter(':') shouldBe native.first().substringAfter(':')
            admitted.first().substringAfter(':') shouldBe when (policy) {
                "VIRTUAL" -> "virtual"
                "OWNED" -> "owned"
                else -> "dispatcher"
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["QUEUED", "VIRTUAL"])
    fun `while admissions block a 404 and a separate Continue asynchronous handler are still answered`(pool: String) {
        val blocked = endpoint { get("blocked"); text() }
        val later = endpoint { get("later"); text() }
        val entered = CountDownLatch(BLOCKED_ADMISSIONS)
        val release = CountDownLatch(1)
        val checked = AtomicInteger()
        val service = api(
            listOf(blocked handledNow { "released" }, later handledBy { CompletableFuture.completedFuture("later") }),
            JacksonCodecs,
        ) {
            admission(
                OperationAdmission { request ->
                    checked.incrementAndGet()
                    if (request.endpoint === blocked) {
                        entered.countDown()
                        check(release.await(EXECUTION_GATE_SECONDS, TimeUnit.SECONDS)) { "admission gate not released" }
                    }
                    AdmissionDecision.Continue
                },
            )
        }
        val threads = when (JettyPool.valueOf(pool)) {
            JettyPool.QUEUED -> pelicanThreads(Handlers.onVirtualThreads, 4).apply { reservedThreads = 0 }
            JettyPool.VIRTUAL -> pelicanPool(Handlers.onVirtualThreads, JettyPool.VIRTUAL)
        }
        val server = Server(threads)
        server.addConnector(
            ServerConnector(
                server, 1, 1,
                HttpConnectionFactory(
                    HttpConfiguration().apply {
                        uriCompliance = PELICAN_URI_COMPLIANCE
                    },
                ),
            ),
        )
        server.handler = service.toHandler(Handlers.onVirtualThreads)
        server.defaultHandler = Unanswered()
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        try {
            server.start()
            val base = "http://127.0.0.1:${(server.connectors.single() as NetworkConnector).localPort}"
            fun get(path: String) = client.sendAsync(
                HttpRequest.newBuilder(URI.create("$base/$path")).GET().build(), HttpResponse.BodyHandlers.ofString(),
            )
            val held = (1..BLOCKED_ADMISSIONS).map { get("blocked") }
            entered.await(EXECUTION_GATE_SECONDS, TimeUnit.SECONDS) shouldBe true
            get("nowhere").get(EXECUTION_GATE_SECONDS, TimeUnit.SECONDS).statusCode() shouldBe 404
            get("later").get(EXECUTION_GATE_SECONDS, TimeUnit.SECONDS).body() shouldBe "later"
            checked.get() shouldBe BLOCKED_ADMISSIONS + 1
            held.none { it.isDone } shouldBe true
            release.countDown()
            held.map { it.get(EXECUTION_GATE_SECONDS, TimeUnit.SECONDS).body() }.distinct() shouldBe listOf("released")
        } finally {
            release.countDown()
            client.close()
            server.stop()
        }
    }
}

private const val BLOCKED_ADMISSIONS = 8
private const val EXECUTION_GATE_SECONDS = 30L
