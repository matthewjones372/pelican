package io.github.matthewjones372.pelican.arrow

import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.errorJson
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

class EitherOutcomeTest {

    data class Problem(val code: String)
    data class Widget(val id: Long)

    private val missing = errorJson<Problem>(404, "No widget with that id")

    @Test
    fun `a Right is the first declared success`() {
        val outcome = Widget(1).right().toOutcome()

        outcome.shouldBeInstanceOf<Outcome.Ok<Widget>>().value shouldBe Widget(1)
    }

    @Test
    fun `a Left is the single declared failure, resolved where the response is written`() {
        val outcome = Problem("gone").left().toOutcome()

        val failed = outcome.shouldBeInstanceOf<Outcome.Err<Problem>>()
        failed.error shouldBe Problem("gone")
        failed.declared shouldBe null
    }

    @Test
    fun `the naming form carries the declaration, so the status is fixed here`() {
        val outcome = Problem("gone").left().toOutcome(missing)

        val failed = outcome.shouldBeInstanceOf<Outcome.Err<Problem>>()
        failed.declared shouldBeSameInstanceAs missing
        failed.declared?.status shouldBe 404
    }

    @Test
    fun `a Right through the naming form is still the success`() {
        Widget(2).right().toOutcome(missing)
            .shouldBeInstanceOf<Outcome.Ok<Widget>>().value shouldBe Widget(2)
    }

    @Test
    fun `toEither is the way back, on both sides`() {
        missing(Problem("gone")).toEither() shouldBe Problem("gone").left()
        Widget(3).right().toOutcome().toEither() shouldBe Widget(3).right()
    }
}
