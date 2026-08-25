package io.github.matthewjones372.pelican

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What an `Accept` header means, decided once in core.
 *
 * Pelican does not go through its backends' marshalling layers, so nothing
 * underneath it negotiates on its behalf — see [acceptable]. That makes this
 * the only place the question is answered, and the only place it can be got
 * wrong for all three interpreters at once.
 *
 * The cases below are the ones that separate a real implementation from a
 * `contains` call: quality values, wildcards, and the precedence rule that
 * lets a caller say "anything, but JSON for preference" or, worse, "anything
 * *except* JSON".
 */
class NegotiationTest {

    private val json = setOf("application/json")

    private fun accepting(header: String) = acceptable(listOf(header), json)

    @Test
    fun `a caller that names the type takes it`() {
        accepting("application/json") shouldBe true
    }

    @Test
    fun `a caller that names something else does not`() {
        accepting("application/xml") shouldBe false
    }

    @Test
    fun `wildcards match`() {
        withClue("*/* takes anything") { accepting("*/*") shouldBe true }
        withClue("application/* takes any application type") { accepting("application/*") shouldBe true }
        withClue("text/* does not take an application type") { accepting("text/*") shouldBe false }
    }

    @Test
    fun `a browser's header takes JSON, because it ends in a wildcard`() {
        accepting("text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8") shouldBe true
    }

    @Test
    fun `q=0 refuses the type it names`() {
        withClue("naming the type and refusing it is still a refusal") {
            accepting("application/json;q=0") shouldBe false
        }
        withClue("a less specific range does not override the refusal of a more specific one") {
            accepting("application/json;q=0, application/*") shouldBe false
        }
    }

    /**
     * The precedence rule, which is the whole reason a quality is looked up
     * through the *most specific* matching range rather than the best one.
     *
     * A header of `&#42;/&#42;;q=0` followed by `application/json` reads as "nothing, except JSON". Taking the
     * highest quality of any match would read it as "anything", and taking the
     * first would read it as "nothing" — both wrong, in opposite directions.
     */
    @Test
    fun `a more specific range beats a wildcard, in both directions`() {
        withClue("nothing, except JSON") {
            accepting("*/*;q=0, application/json") shouldBe true
        }
        withClue("anything, except JSON") {
            accepting("*/*, application/json;q=0") shouldBe false
        }
    }

    @Test
    fun `nothing to negotiate is always acceptable`() {
        withClue("a 204 has no representation to refuse") {
            acceptable(listOf("application/xml"), emptySet()) shouldBe true
        }
        withClue("no Accept header at all") {
            acceptable(emptyList(), json) shouldBe true
        }
    }

    /**
     * An `Accept` a caller very likely did not write — a proxy's, an SDK's, a
     * copy-paste — is not grounds for refusing them. Nothing parseable means
     * nothing was asked for.
     */
    @Test
    fun `an unparseable header is not a refusal`() {
        withClue("no slash, so not a media range, so nothing was asked for") {
            accepting("garbage") shouldBe true
        }
        withClue("one readable entry among the noise still decides it") {
            accepting("garbage, application/xml") shouldBe false
        }
        withClue("empty entries") { accepting(",,,") shouldBe true }
        withClue("an unreadable q falls back to 1") { accepting("application/json;q=banana") shouldBe true }
    }

    @Test
    fun `case does not matter, and neither does whitespace`() {
        accepting("  APPLICATION/JSON ;  q=1.0  ") shouldBe true
    }

    @Test
    fun `several field lines are read as one header`() {
        withClue("RFC 9110: two lines of the same name are the one joined field") {
            acceptable(listOf("application/xml", "application/json"), json) shouldBe true
        }
    }

    @Test
    fun `an output declaring several successes is acceptable if any of them is`() {
        acceptable(listOf("text/plain"), setOf("application/json", "text/plain")) shouldBe true
    }
}
