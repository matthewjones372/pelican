package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.RequestView
import io.github.matthewjones372.pelican.spi.check
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
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

    @Test
    fun `the backend bridge preserves the selected endpoint complete headers and exact decision once`() {
        listOf(AdmissionDecision.Continue, AdmissionDecision.Refuse(421)).forEach { decision ->
            var calls = 0
            val fields = mutableListOf("X-Audience" to "admin.example", "x-audience" to "partner.example")
            val view = admissionView { name ->
                fields.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
            }
            val admission = OperationAdmission { request ->
                calls += 1
                request.endpoint shouldBeSameInstanceAs selected
                request.header("x-AUDIENCE") shouldBe "admin.example"
                val snapshot = request.headers("X-Audience")
                snapshot shouldContainExactly listOf("admin.example", "partner.example")
                request.headers("missing") shouldBe emptyList()
                fields += "x-audience" to "third.example"
                snapshot shouldContainExactly listOf("admin.example", "partner.example")
                shouldThrow<UnsupportedOperationException> { (snapshot as MutableList<*>).clear() }
                request.headers("X-Audience") shouldContainExactly
                    listOf("admin.example", "partner.example", "third.example")
                decision
            }
            admission.check(selected, view) shouldBeSameInstanceAs decision
            calls shouldBe 1
        }
    }

    @Test
    fun `the backend bridge preserves callback defects without consulting headers eagerly`() {
        val defect = IllegalStateException("synthetic callback defect")
        val admission = OperationAdmission { throw defect }
        val view = admissionView { error("headers consulted before callback") }
        shouldThrow<IllegalStateException> { admission.check(selected, view) } shouldBeSameInstanceAs defect
    }

    @Test
    fun `the backend bridge preserves a native header lookup defect`() {
        val defect = UnsupportedOperationException("synthetic native lookup defect")
        val view = admissionView { throw defect }
        val admission = OperationAdmission { request ->
            request.headers("X-Audience")
            AdmissionDecision.Continue
        }
        shouldThrow<UnsupportedOperationException> { admission.check(selected, view) } shouldBeSameInstanceAs defect
    }
}

private fun admissionView(fields: (String) -> List<String>): RequestView = object : RequestView {
    override val method: String get() = error("method consulted")
    override val rawPath: String get() = error("raw path consulted")
    override val rawQuery: String? get() = error("raw query consulted")
    override fun header(name: String): String? = error("first header consulted")
    override fun cookie(name: String): String? = error("cookie consulted")
    override fun headers(name: String): List<String> = fields(name)
}
