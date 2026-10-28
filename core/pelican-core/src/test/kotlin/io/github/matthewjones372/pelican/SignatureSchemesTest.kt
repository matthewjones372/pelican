package io.github.matthewjones372.pelican

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Stripe's and Standard Webhooks' HMAC-SHA256 signatures, verified and made, as values. Spec 0097. */
class SignatureSchemesTest {

    private val at = Instant.ofEpochSecond(1_614_265_330)

    private fun headers(vararg pairs: Pair<String, String>): (String) -> String? =
        { name -> pairs.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second }

    // ------------------------------------------------------------- Standard Webhooks

    private val whsec = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
    private val hooks = standardWebhooks { listOf(whsec) }
    private val event = """{"test": 2432232314}""".toByteArray()

    /** The example in the Standard Webhooks spec, byte for byte. */
    private val published = headers(
        "webhook-id" to "msg_p5jXN8AQM9LWM0D4loKWxJek",
        "webhook-timestamp" to "1614265330",
        "webhook-signature" to "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=",
    )

    @Test
    fun `the Standard Webhooks spec's own example verifies`() {
        hooks.verify(published, event, at) shouldBe SignatureCheck.Valid
    }

    @Test
    fun `an altered body, a stale or early timestamp, and a missing or malformed header each fail`() {
        hooks.verify(published, """{"test": 1}""".toByteArray(), at) shouldBe
            SignatureCheck.Failed(SignatureFailure.MISMATCH)
        hooks.verify(published, event, at.plus(6.minutes.toJavaDuration())) shouldBe
            SignatureCheck.Failed(SignatureFailure.STALE)
        hooks.verify(published, event, at.minus(6.minutes.toJavaDuration())) shouldBe
            SignatureCheck.Failed(SignatureFailure.STALE)
        hooks.verify(headers("webhook-id" to "msg_1"), event, at) shouldBe
            SignatureCheck.Failed(SignatureFailure.MISSING)
        hooks.verify(
            headers("webhook-id" to "msg_1", "webhook-timestamp" to "soon", "webhook-signature" to "v1,abc"),
            event,
            at,
        ) shouldBe SignatureCheck.Failed(SignatureFailure.MALFORMED)
    }

    @Test
    fun `within the tolerance either way is fresh`() {
        hooks.verify(published, event, at.plus(299.seconds.toJavaDuration())) shouldBe SignatureCheck.Valid
        hooks.verify(published, event, at.minus(299.seconds.toJavaDuration())) shouldBe SignatureCheck.Valid
    }

    @Test
    fun `any secret matching any signature passes, so an old and a new one overlap`() {
        val rotating = standardWebhooks { listOf("whsec_bmV3IHNlY3JldCBuZXcgc2VjcmV0", whsec) }

        rotating.verify(published, event, at) shouldBe SignatureCheck.Valid
    }

    @Test
    fun `what it signs, it verifies`() {
        val sent = hooks.sign(whsec, event, at, id = "msg_p5jXN8AQM9LWM0D4loKWxJek")

        sent shouldContainExactly listOf(
            "webhook-id" to "msg_p5jXN8AQM9LWM0D4loKWxJek",
            "webhook-timestamp" to "1614265330",
            "webhook-signature" to "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=",
        )
    }

    // ------------------------------------------------------------------------- Stripe

    private val stripeSecret = "whsec_test_secret"
    private val stripe = stripeSignature { listOf(stripeSecret) }
    private val charge = """{"id":"evt_1"}""".toByteArray()

    /** Computed independently with Python's hmac, since Stripe publishes no fixed example. */
    private val stripeHex = "e135cdbed3dda004bf8a2fb350f2541d299393c770e0b9e6e319dc595e9072aa"

    @Test
    fun `a Stripe signature verifies over the timestamp and the bytes received`() {
        stripe.verify(headers("Stripe-Signature" to "t=1614265330,v1=$stripeHex"), charge, at) shouldBe
            SignatureCheck.Valid
    }

    @Test
    fun `one v1 per secret, and any of them matching passes`() {
        val two = headers("Stripe-Signature" to "t=1614265330,v1=${"0".repeat(64)},v1=$stripeHex,v0=ignored")

        stripe.verify(two, charge, at) shouldBe SignatureCheck.Valid
    }

    @Test
    fun `a Stripe header with no timestamp or no v1 is malformed, and none at all is missing`() {
        stripe.verify(headers("Stripe-Signature" to "v1=$stripeHex"), charge, at) shouldBe
            SignatureCheck.Failed(SignatureFailure.MALFORMED)
        stripe.verify(headers("Stripe-Signature" to "t=1614265330"), charge, at) shouldBe
            SignatureCheck.Failed(SignatureFailure.MALFORMED)
        stripe.verify(headers(), charge, at) shouldBe SignatureCheck.Failed(SignatureFailure.MISSING)
    }

    @Test
    fun `a Stripe signature it makes is the one Stripe would send`() {
        stripe.sign(stripeSecret, charge, at) shouldContainExactly
            listOf("Stripe-Signature" to "t=1614265330,v1=$stripeHex")
    }

    @Test
    fun `no secrets at all is a mismatch, never a pass`() {
        stripeSignature { emptyList() }
            .verify(headers("Stripe-Signature" to "t=1614265330,v1=$stripeHex"), charge, at) shouldBe
            SignatureCheck.Failed(SignatureFailure.MISMATCH)
    }

    @Test
    fun `each scheme names the headers it reads`() {
        stripe.headers shouldContainExactly listOf("Stripe-Signature")
        hooks.headers shouldContainExactly listOf("webhook-id", "webhook-timestamp", "webhook-signature")
    }
}
