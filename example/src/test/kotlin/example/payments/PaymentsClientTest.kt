package example.payments

import example.generated.payments.ApiCallFailed
import example.generated.payments.ChargeStatus
import example.generated.payments.GetChargeFailure
import example.generated.payments.Outcome
import example.generated.payments.PaymentsClient
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.client.jetty.JettyHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.start
import io.github.matthewjones372.pelican.retryPolicy
import io.github.matthewjones372.pelican.retrying
import io.github.matthewjones372.pelican.standardWebhooks
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import example.generated.payments.NewCharge as SentCharge
import example.generated.payments.ProcessorEvent as SentEvent

/** Both sides through the generated client: a merchant whose answer is lost retries, and the processor signs. */
class PaymentsClientTest {

    private val ledger = Ledger()
    private val server = paymentsApi(ledger).start(port = 0)
    private val http = JettyHttpTransport()

    @AfterEach
    fun stop() {
        http.close()
        server.stop()
    }

    private fun merchant(key: String, transport: ClientTransport = http) =
        PaymentsClient(server.baseUrl, JacksonCodecs, transport) { mapOf("Authorization" to "Bearer $key") }

    private val theProcessor = PaymentsClient(server.baseUrl, JacksonCodecs, http)

    /** The first answer is lost on the way back, after the server has acted on the request. */
    private val lost = AtomicBoolean()

    private val losesTheFirstAnswer = ClientTransport { request ->
        http.send(request).thenApply { answer ->
            if (lost.compareAndSet(false, true)) {
                answer.body.close()
                ClientResponse(503, emptyList(), ByteArrayInputStream(ByteArray(0)))
            } else {
                answer
            }
        }
    }

    @Test
    fun `a charge whose answer was lost is retried with its key, and charged once`() {
        val impatient = retryPolicy { jitter = 0.0; initialBackoff = Duration.ZERO; statuses = setOf(503) }
        val retrying = merchant(ADA_KEY, losesTheFirstAnswer.retrying(impatient))

        val charge = retrying.createCharge(SentCharge(500, "GBP"))

        charge.status shouldBe ChargeStatus.PENDING
        ledger.created shouldBe 1
    }

    @Test
    fun `a key that may only read is refused with the 403, as an ApiCallFailed`() {
        shouldThrow<ApiCallFailed> { merchant(ADA_VIEWER_KEY).createCharge(SentCharge(500, "GBP")) }.status shouldBe 403
        ledger.created shouldBe 0
    }

    @Test
    fun `another merchant's charge is the declared 404`() {
        val id = merchant(ADA_KEY).createCharge(SentCharge(500, "GBP")).id

        merchant(GRACE_KEY).getCharge(id).shouldBeInstanceOf<Outcome.Err<GetChargeFailure.NotFound>>()
    }

    @Test
    fun `the processor signs a settlement with the client's signer, and the merchant sees it settled`() {
        val ada = merchant(ADA_KEY)
        val id = ada.createCharge(SentCharge(500, "GBP")).id

        theProcessor.processorEvent(SentEvent(id, settled = true), signer = processor.signer(PROCESSOR_SECRET))

        (ada.getCharge(id) as Outcome.Ok).value.status shouldBe ChargeStatus.SETTLED
    }

    @Test
    fun `a settlement signed with another secret is refused, and the charge stays pending`() {
        val ada = merchant(ADA_KEY)
        val id = ada.createCharge(SentCharge(500, "GBP")).id
        val impostor = standardWebhooks { emptyList() }.signer("whsec_bm90IHRoZSBwcm9jZXNzb3I=")

        shouldThrow<ApiCallFailed> { theProcessor.processorEvent(SentEvent(id, settled = true), impostor) }
            .status shouldBe 400
        (ada.getCharge(id) as Outcome.Ok).value.status shouldBe ChargeStatus.PENDING
    }
}
