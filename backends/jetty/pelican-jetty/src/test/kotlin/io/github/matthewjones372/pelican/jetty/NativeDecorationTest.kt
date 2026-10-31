package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.AdmissionDecision
import io.github.matthewjones372.pelican.AdmissionRequest
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.OperationAdmission
import io.github.matthewjones372.pelican.RefusalBody
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.eclipse.jetty.http.HttpTester
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.ServerSocket
import java.net.Socket
import kotlin.reflect.KType

class NativeDecorationTest {
    @Test
    fun `native construction and decoration happen once before binding`() {
        var resolutions = 0
        val codecs = object : Codecs by JacksonCodecs {
            override fun <T> codec(type: KType): BodyCodec<T> {
                resolutions++
                return JacksonCodecs.codec(type)
            }
        }
        val operation = endpoint {
            get("construction")
            json<String>()
        }
        val service = api(listOf(operation handledNow { "ok" }), codecs) {
            admission(OperationAdmission { AdmissionDecision.Continue })
        }
        service.toHandler()
        val expected = resolutions
        resolutions = 0
        var decorations = 0
        val failure = AssertionError("prebind")
        ServerSocket(0).use { reserved ->
            shouldThrow<AssertionError> {
                service.startDecorated(port = reserved.localPort) { native ->
                    decorations++
                    native.server.shouldBeNull()
                    throw failure
                }
            } shouldBeSameInstanceAs failure
        }
        (expected > 0) shouldBe true
        resolutions shouldBe expected
        decorations shouldBe 1
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `decorated refusals retain native bodies headers and gate exclusion`(hasBody: Boolean) {
        val world = AdmissionWorld().apply { poison = true }
        val body = if (hasBody) RefusalBody("application/problem+json", "{}".toByteArray()) else null
        val decision = AdmissionDecision.Refuse(421, body, listOf("X-Decision" to "yes"))
        val answer = world.decorated(world.decide { decision }).use { server ->
            Asking(listOf("Content-Type", "X-Global", "X-Outer", "X-Decision")).use { asking ->
                asking.guarded(server, "/admission/not-a-uuid?number=bad", "{")
            }
        }
        answer.status shouldBe 421
        answer.body shouldBe if (hasBody) "{}" else ""
        answer.headers["Content-Type"] shouldBe if (hasBody) "application/problem+json" else null
        answer.headers["X-Global"] shouldBe "yes"
        answer.headers["X-Outer"] shouldBe "yes"
        answer.headers["X-Decision"] shouldBe "yes"
        world.events shouldBe listOf("decoration", "admission")
    }

    @Test
    fun `Continue retains the equivalent native keyed call`() {
        val baseline = AdmissionWorld()
        val expected = nativeAnswer(baseline)
        val world = AdmissionWorld()
        val answer = world.decorated(world.decide { AdmissionDecision.Continue }).use { server ->
            Asking(emptyList()).use { it.guarded(server, world.valid) }
        }
        answer shouldBe expected
        world.events shouldBe listOf("decoration", "admission") + baseline.events
    }

    @Test
    fun `early refusal drains its body before another request on the same socket`() {
        val baseline = AdmissionWorld()
        val expected = nativeAnswer(baseline)
        val world = AdmissionWorld()
        val hook = world.decide { request ->
            val continuing = request.header("X-Continue") == "yes"
            world.events += if (continuing) "continue" else "refuse"
            if (continuing) AdmissionDecision.Continue else AdmissionDecision.Refuse(421)
        }
        world.decorated(hook).use { server ->
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 10_000
                val input = HttpTester.from(socket.getInputStream())
                val output = socket.getOutputStream()
                output.write("POST ${world.valid} HTTP/1.1\r\nHost: unit\r\nContent-Length: 3\r\n\r\n".toByteArray())
                val first = HttpTester.parseResponse(input)
                first.status shouldBe 421
                first.content.orEmpty() shouldBe ""
                first.get("X-Outer") shouldBe "yes"
                output.write(
                    (
                        "badPOST ${world.valid} HTTP/1.1\r\nHost: unit\r\nContent-Length: 4\r\n" +
                            "X-Continue: yes\r\nContent-Type: application/json\r\nAuthorization: Bearer unit\r\n" +
                            "Idempotency-Key: second\r\n\r\n\"ok\""
                        ).toByteArray(),
                )
                val second = HttpTester.parseResponse(input)
                second.status shouldBe expected.status
                second.content shouldBe expected.body
                second.get("X-Outer") shouldBe "yes"
            }
        }
        world.events shouldBe listOf("decoration", "refuse", "admission", "continue", "admission") + baseline.events
    }
}

private fun AdmissionWorld.decide(decision: (AdmissionRequest) -> AdmissionDecision) = OperationAdmission { request ->
    request.endpoint shouldBeSameInstanceAs operation
    hook(decision(request)).check(request)
}

private fun AdmissionWorld.decorated(hook: OperationAdmission) = service(hook).startDecorated(port = 0) { native ->
    events += "decoration"
    object : Handler.Wrapper(native) {
        override fun handle(request: Request, response: Response, callback: Callback): Boolean {
            response.headers.add("X-Outer", "yes")
            return super.handle(request, response, callback)
        }
    }
}

private fun Asking.guarded(server: PelicanServer, path: String, body: String = "\"ok\"") = ask(
    server.baseUrl, "POST", path,
    mapOf("Content-Type" to "application/json", "Authorization" to "Bearer unit", "Idempotency-Key" to "unit"), body,
)

private fun nativeAnswer(world: AdmissionWorld) = world.service(null).start(port = 0).use { server ->
    Asking(emptyList()).use { it.guarded(server, world.valid) }
}
