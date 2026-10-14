package io.github.matthewjones372.pelican.openapi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * What the comparison looks like at the moment somebody has to read it.
 *
 * The rules are [CompatibilityTest]'s subject; this is the other half of being
 * useful. A build goes red, a developer reads three lines and decides — so the
 * count comes first, the operations group what follows, and the changes nobody
 * has to act on are counted rather than listed, because a report that buries
 * one breaking change under nine harmless ones has answered nothing.
 */
class ReportTest {

    private val breaking = ApiChange(
        Compatibility.BREAKING,
        "POST /orders",
        "`currency` in the request body is new and required",
        "every caller that is not sending it is refused",
    )

    private val gone = ApiChange(
        Compatibility.BREAKING,
        "GET /orders/{id}",
        "the operation is gone",
        "every caller still holding it gets a 404",
    )

    private val safe = ApiChange(
        Compatibility.COMPATIBLE,
        "POST /orders",
        "the `tag` parameter is new and optional",
    )

    private val prose = ApiChange(Compatibility.COSMETIC, "POST /orders", "the summary changed")

    private fun report(vararg changes: ApiChange) = changes.toList().report("Orders 1.0.0", colour = false)

    @Test
    fun `the count comes first, because it is the decision`() {
        report(breaking, gone, safe).lines().first() shouldBe "Orders 1.0.0 — 2 changes break callers."
    }

    @Test
    fun `one change is one change`() {
        report(breaking, safe).lines().first() shouldBe "Orders 1.0.0 — 1 change breaks callers."
    }

    @Test
    fun `the changes are grouped under the operation they happened in`() {
        val lines = report(breaking, gone).lines().filter { it.isNotBlank() }

        lines[1] shouldBe "  POST /orders"
        lines[2] shouldContain "`currency` in the request body is new and required"
        lines[3] shouldContain "every caller that is not sending it is refused"
        lines[4] shouldBe "  GET /orders/{id}"
    }

    @Test
    fun `the harmless ones are counted, not listed under the ones that matter`() {
        val text = report(breaking, safe, prose)

        text shouldContain "and 2 changes nothing has to act on."
        text shouldNotContain "the `tag` parameter"
    }

    @Test
    fun `a release that breaks nobody says so, and then shows its work`() {
        val text = report(safe, prose)

        text.lines().first() shouldBe "Orders 1.0.0 — 2 changes, none of them breaking."
        text shouldContain "the `tag` parameter is new and optional"
    }

    @Test
    fun `nothing at all is one line`() {
        emptyList<ApiChange>().report("Orders 1.0.0") shouldBe "Orders 1.0.0 — nothing changed."
    }

    @Test
    fun `colour is escapes around the same text, and is off where nothing asked for it`() {
        val plain = report(breaking)
        val coloured = listOf(breaking).report("Orders 1.0.0", colour = true)

        plain shouldNotContain "["
        coloured shouldContain "[31m"
        coloured.replace(Regex("\\[[0-9]+m"), "") shouldBe plain
    }

    @Test
    fun `the two documents can be handed over as text, for a caller that has files`() {
        val before = """{"paths":{"/orders":{"get":{"operationId":"listOrders","responses":{"200":{}}}}}}"""
        val after = """{"paths":{}}"""

        compatibilityReport(before, after, "openapi.json", colour = false) shouldContain "the operation is gone"
        breakingChanges(before, after) shouldBe 1
        breakingChanges(before, before) shouldBe 0
    }
}
