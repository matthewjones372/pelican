package example.payments

import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.start
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.github.matthewjones372.pelican.test.signedWith
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A merchant's key, a charge safe to retry, and a processor believed once it signs: spec 0102, on Jetty. */
class PaymentsTest {

    private val ledger = Ledger()
    private val server = paymentsApi(ledger).start(port = 0)
    private val client: ApiClient = apiClient(server.baseUrl, JacksonCodecs)

    @AfterEach
    fun stop() {
        client.close()
        server.stop()
    }

    private fun RequestSpec.by(key: String) = withHeader("Authorization", "Bearer $key")

    private fun charge(amount: Int = 500, key: String = "order-1") =
        client.request(createCharge, NewCharge(amount, "GBP")).withHeader("Idempotency-Key", key)

    private fun send(request: RequestSpec) = client.transport.send(request)

    private fun created(request: RequestSpec): String =
        Regex("\"id\":\"(ch_[0-9]+)\"").find(send(request).shouldHaveStatus(201).body)!!.groupValues[1]

    private fun status(id: String) = send(client.request(getCharge, id).by(ADA_KEY)).shouldHaveStatus(200).body

    @Test
    fun `a charge retried with the same key is answered with the first, and the ledger has one`() {
        val first = send(charge().by(ADA_KEY)).shouldHaveStatus(201).body
        val retried = send(charge().by(ADA_KEY)).shouldHaveStatus(201).body

        retried shouldBe first
        ledger.created shouldBe 1
    }

    @Test
    fun `the same key with another amount is a 422, and charges nothing`() {
        send(charge(500).by(ADA_KEY)).shouldHaveStatus(201)
        send(charge(900).by(ADA_KEY)).shouldHaveStatus(422)

        ledger.created shouldBe 1
    }

    @Test
    fun `a key is the merchant's own, so two merchants using one key make two charges`() {
        send(charge().by(ADA_KEY)).shouldHaveStatus(201)
        send(charge().by(GRACE_KEY)).shouldHaveStatus(201)

        ledger.created shouldBe 2
    }

    @Test
    fun `no key is a 401, and a key this service did not issue is a 401 that does not repeat it`() {
        send(charge()).shouldHaveStatus(401)

        send(charge().by("sk_live_mallory")).shouldHaveStatus(401).body shouldNotContain "mallory"
    }

    @Test
    fun `a key that may only read is a 403, and spends no idempotency key`() {
        send(charge().by(ADA_VIEWER_KEY)).shouldHaveStatus(403)

        // Had the refusal been kept under the key, this would be answered with the 403 again.
        send(charge().by(ADA_KEY)).shouldHaveStatus(201)
        ledger.created shouldBe 1
    }

    @Test
    fun `another merchant's charge is not found`() {
        val id = created(charge().by(ADA_KEY))

        send(client.request(getCharge, id).by(GRACE_KEY)).shouldHaveStatus(404)
        send(client.request(getCharge, id).by(ADA_VIEWER_KEY)).shouldHaveStatus(200)
    }

    @Test
    fun `a settlement signed by the processor settles the charge`() {
        val id = created(charge().by(ADA_KEY))

        send(client.request(processorEvent, ProcessorEvent(id, settled = true)).signedWith(processor, PROCESSOR_SECRET))
            .shouldHaveStatus(204)

        status(id) shouldContain "\"status\":\"SETTLED\""
    }

    @Test
    fun `a forged, a stale or an unsigned settlement is a 400, and the charge stays pending`() {
        val id = created(charge().by(ADA_KEY))
        val event = client.request(processorEvent, ProcessorEvent(id, settled = true))
        val forged = event.signedWith(processor, "whsec_bm90IHRoZSBwcm9jZXNzb3I=")
        val stale = event.signedWith(processor, PROCESSOR_SECRET, Instant.now().minus(6, ChronoUnit.MINUTES))

        listOf(forged, stale, event).forEach { delivery ->
            send(delivery).shouldHaveStatus(400).body shouldContain "\"error\":\"signature_invalid\""
        }
        status(id) shouldContain "\"status\":\"PENDING\""
    }

    @Test
    fun `a merchant's key is no use on the processor's endpoint`() {
        val id = created(charge().by(ADA_KEY))

        send(client.request(processorEvent, ProcessorEvent(id, settled = true)).by(ADA_KEY)).shouldHaveStatus(400)
        status(id) shouldContain "\"status\":\"PENDING\""
    }
}
