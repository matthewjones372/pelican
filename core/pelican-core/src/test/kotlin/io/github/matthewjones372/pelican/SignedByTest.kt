package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KType
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** An endpoint that reads a body only once its signature verifies over the bytes received. Spec 0097. */
class SignedByTest {

    private object Strings : Codecs {
        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: KType): BodyCodec<T> = object : BodyCodec<String> {
            override fun encodeToString(value: String) = value
            override fun decodeFromString(text: String) = text
        } as BodyCodec<T>

        override fun schema(type: KType, components: SchemaComponents): JsonObj = jsonObj { "type" to "string" }
    }

    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val secret = "whsec_stripe_test"
    private val stripe = stripeSignature(clock = clock) { listOf(secret) }

    private val event = jsonBody<String>()
    private val stripeEvent = endpoint(event) {
        post("hooks" / "stripe")
        signedBy(stripe)
        text()
    }

    private val ran = AtomicInteger()
    private val observed = mutableListOf<String>()

    private val api = api(
        listOf(
            ServerEndpoint(stripeEvent) { p ->
                ran.incrementAndGet()
                CompletableFuture.completedStage("got ${p[event]}" as Any?)
            },
        ),
        codecs = Strings,
    ) { onRefusal { reason, status, _ -> observed += "${reason.label} $status" } }

    private fun deliver(body: String, headers: List<Pair<String, String>>): ClientResponse =
        InMemoryClientTransport(api)
            .send(ClientRequest(Method.POST, "$HOOK", headers, ClientRequest.Body.Text(body)))
            .toCompletableFuture().join()

    private val payload = """{"id":"evt_1"}"""

    @Test
    fun `a signed delivery reaches the handler, decoded as usual`() {
        val answered = deliver(payload, stripe.sign(secret, payload.toByteArray(), now))

        answered.status shouldBe 200
        answered.text() shouldBe "got $payload"
    }

    @Test
    fun `a forged, stale or unsigned delivery is a 400, heard as a signature refusal, and the handler never runs`() {
        val signed = stripe.sign(secret, payload.toByteArray(), now)
        val stale = stripe.sign(secret, payload.toByteArray(), now.minus(6.minutes.toJavaDuration()))

        deliver("""{"id":"evt_2"}""", signed).status shouldBe 400
        deliver(payload, stale).status shouldBe 400
        deliver(payload, emptyList()).status shouldBe 400

        ran.get() shouldBe 0
        observed shouldContainExactly List(3) { "signature 400" }
    }

    @Test
    fun `the refusal names the reason class, and not which check failed`() {
        val refused = deliver(payload, emptyList()).text()

        refused shouldContain "signature_invalid"
        refused.contains("MISSING", ignoreCase = true) shouldBe false
    }

    @Test
    fun `the 400 is declared, tagged, so a 400 of the endpoint's own can sit beside it`() {
        val declared = stripeEvent.errors.single { it.status == 400 }

        declared.tag shouldBe "signature_invalid"
        stripeEvent.signedBy shouldBeSameInstanceAs stripe
    }

    @Test
    fun `a streamed body cannot be signed, since nothing holds its bytes to check`() {
        shouldThrow<IllegalArgumentException> {
            endpoint(rawBody()) {
                post("hooks" / "raw")
                signedBy(stripe)
                text()
            }
        }.message shouldContain "signedBy"
    }

    @Test
    fun `an endpoint with no body has nothing to sign`() {
        shouldThrow<IllegalArgumentException> {
            endpoint {
                post("hooks" / "empty")
                signedBy(stripe)
                text()
            }
        }.message shouldContain "signedBy"
    }
}

private const val HOOK = "http://hooks.example/hooks/stripe"
