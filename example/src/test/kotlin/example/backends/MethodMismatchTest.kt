package example.backends

import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import io.github.matthewjones372.pelican.pekko.handledNow as handledOnPekko
import io.github.matthewjones372.pelican.pekko.handledWith as handledWithOnPekko

/**
 * Where an interpreter's answer is its router's and not Pelican's, made
 * concrete.
 *
 * [AllBackendsTest] holds what a backend is held to. This holds the one thing
 * it is not, so the number is documented as a router's choice rather than
 * discovered as a surprise: a request whose path matches an endpoint but whose
 * method belongs to a *different* endpoint. Pekko answers 405 only when no
 * endpoint declares the offered method; here the second endpoint does, and its
 * path rejection swallows the first's method rejection, so 404.
 *
 * Not Pelican's choice — the router decides it — which is why no assertion
 * anywhere else in the repository holds a backend to one number for this.
 */
class MethodMismatchTest {

    private val outbox = endpoint {
        post("outbox")
        operationId = "send"
        empty(status = 202)
    }

    private val postToHello =
        RequestSpec(Method.POST, "/hello/ada", emptyList(), emptyList(), null)

    @Test
    fun `pekko answers 404, because another endpoint declares POST`() {
        greetingsApi(
            listOf(
                greet handledOnPekko { (who, shout) -> greetingOf(who, shout) },
                outbox handledWithOnPekko { },
            ),
            // A deliberate subset: this suite is about the router, not about
            // the service being complete.
            covers = emptyList(),
        ).inMemory("greetings-method-mismatch").use {
            it.transport.send(postToHello).status shouldBe 404
        }
    }
}
