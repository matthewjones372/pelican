package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OperationAdmissionTest {
    private val selected = endpoint { get("hello"); text() }

    @Test
    fun `a refusal accepts only client or server failure statuses`() {
        listOf(400, 421, 499, 500, 599).forEach { AdmissionDecision.Refuse(it).status shouldBe it }
        listOf(0, 199, 200, 302, 399, 600).forEach {
            shouldThrow<IllegalArgumentException> { AdmissionDecision.Refuse(it) }
        }
    }

    @Test
    fun `a refusal has no body unless one was supplied`() {
        AdmissionDecision.Refuse(404).body.shouldBeNull()
        val body = RefusalBody("text/plain", byteArrayOf(42))
        AdmissionDecision.Refuse(421, body).body shouldBe body
    }

    @Test
    fun `a refusal rejects invalid or server owned response headers`() {
        listOf("", " ", "X Bad", "X:Bad", "X\rBad", "Content-Type", "cOnTeNt-LeNgTh", "Connection").forEach {
            shouldThrow<IllegalArgumentException> { AdmissionDecision.Refuse(404, headers = listOf(it to "ok")) }
        }
        listOf("bad\rvalue", "bad\nvalue", "bad\u0000value", "bad\u007fvalue").forEach {
            shouldThrow<IllegalArgumentException> { AdmissionDecision.Refuse(404, headers = listOf("X-Test" to it)) }
        }
    }

    @Test
    fun `a refusal snapshots repeated response fields without joining them`() {
        val fields = mutableListOf("Set-Cookie" to "a=1", "Set-Cookie" to "b=2")
        val refusal = AdmissionDecision.Refuse(401, headers = fields)
        fields.clear()
        refusal.headers shouldContainExactly listOf("Set-Cookie" to "a=1", "Set-Cookie" to "b=2")
        shouldThrow<UnsupportedOperationException> { (refusal.headers as MutableList<*>).clear() }
    }

    @Test
    fun `all request field lines remain separate immutable snapshots`() {
        val fields = mutableListOf("admin.example", "foreign.example")
        val request = AdmissionRequest(selected) { fields }
        val snapshot = request.headers("Host")
        fields += "third.example"
        snapshot shouldContainExactly listOf("admin.example", "foreign.example")
        shouldThrow<UnsupportedOperationException> { (snapshot as MutableList<*>).clear() }
        request.header("Host") shouldBe "admin.example"
        request.headers("Host") shouldContainExactly fields
    }

    @Test
    fun `missing field lines have no first value`() {
        val request = AdmissionRequest(selected) { emptyList() }
        request.headers("Host") shouldBe emptyList()
        request.header("Host").shouldBeNull()
    }

    @Test
    fun `the admission callback sees the library selected endpoint`() {
        val check = OperationAdmission { request ->
            request.endpoint shouldBe selected
            AdmissionDecision.Continue
        }
        check.check(AdmissionRequest(selected) { emptyList() }) shouldBe AdmissionDecision.Continue
    }
}
