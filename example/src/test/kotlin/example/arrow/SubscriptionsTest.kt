package example.arrow

import io.github.matthewjones372.pelican.arrow.toEither
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.shouldBeError
import io.github.matthewjones372.pelican.test.shouldBeFailure
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The three conversion shapes, over the wire.
 *
 * Each assertion names the *declaration* the handler produced rather than a
 * status copied into the test, so a conversion that started answering with the
 * wrong declared failure fails here even though the payload type is the same.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionsTest {

    private lateinit var app: ApiClient

    @BeforeAll fun setUp() { app = subscriptionsApi().inMemory("pelican-arrow-example") }

    @AfterAll fun tearDown() = app.close()

    @Test
    fun `a Right is the success`() {
        app.outcome(getPlan, "team").shouldBeOk().monthlyPence shouldBe 4_900
    }

    @Test
    fun `a Left is the single declared failure, with no declaration named`() {
        app.outcome(getPlan, "gold").shouldBeError().code shouldBe "no_such_plan"
    }

    @Test
    fun `a domain error becomes the declared failure the service mapped it to`() {
        val taken = Signup("ada@example.com", planCode = "team", seats = 1)

        app.outcome(subscribe, taken) shouldBeFailure alreadySubscribed
    }

    @Test
    fun `a different domain error is a different declared response`() {
        val tooMany = Signup("grace@example.com", planCode = "solo", seats = 4)

        app.outcome(subscribe, tooMany) shouldBeFailure seatsExceedPlan
    }

    /** The reason a form gets its own error type: three broken rules, one response. */
    @Test
    fun `an accumulated failure carries every rule the request broke`() {
        val nothingRight = Signup(email = "not-an-address", planCode = "", seats = 0)

        val problems = app.outcome(checkSignup, nothingRight).shouldBeError().problems

        problems shouldContainExactly listOf(
            "email: 'not-an-address' is not an email address",
            "planCode: name the plan to subscribe to",
            "seats: ask for at least one seat",
        )
    }

    /** And the way back, for a caller who would rather hold their own type. */
    @Test
    fun `toEither hands the outcome back as an Either`() {
        app.outcome(getPlan, "solo").toEither().isRight() shouldBe true
        app.outcome(getPlan, "gold").toEither().isLeft() shouldBe true
    }
}
