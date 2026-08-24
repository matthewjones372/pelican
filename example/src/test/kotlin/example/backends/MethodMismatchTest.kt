package example.backends

import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.http4k.toHttpHandler
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.kotest.matchers.shouldBe
import org.http4k.core.Request
import org.junit.jupiter.api.Test
import io.github.matthewjones372.pelican.http4k.handledNow as handledOnHttp4k
import io.github.matthewjones372.pelican.http4k.handledWith as handledWithOnHttp4k
import io.github.matthewjones372.pelican.ktor.handledNow as handledOnKtor
import io.github.matthewjones372.pelican.ktor.handledWith as handledWithOnKtor
import io.github.matthewjones372.pelican.ktor.start as startOnKtor
import io.github.matthewjones372.pelican.pekko.handledNow as handledOnPekko
import io.github.matthewjones372.pelican.pekko.handledWith as handledWithOnPekko

/**
 * Where the three interpreters genuinely part company, made concrete.
 *
 * [AllBackendsTest] holds everything the backends agree on. This holds the one
 * thing they do not, so it is documented as a difference rather than discovered
 * as a surprise: a request whose path matches an endpoint but whose method
 * belongs to a *different* endpoint.
 *
 * - http4k's router separates "no such path" from "not that method" and answers
 *   405.
 * - Ktor's router does not make that distinction at all, and answers 404.
 * - Pekko answers 405 only when no endpoint declares the offered method; here
 *   the second endpoint does, and its path rejection swallows the first's
 *   method rejection, so 404.
 *
 * None of this is Pelican's choice — each router decides it — which is why no
 * assertion anywhere else in the repository asserts one number across backends.
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

    @Test
    fun `http4k answers 405, path matched and method did not`() {
        val handler = greetingsApi(
            listOf(
                greet handledOnHttp4k { (who, shout) -> greetingOf(who, shout) },
                outbox handledWithOnHttp4k { },
            ),
            covers = emptyList(),
        ).toHttpHandler()

        handler(Request(org.http4k.core.Method.POST, "/hello/ada")).status.code shouldBe 405
    }

    @Test
    fun `ktor answers 404, its router drawing no such distinction`() {
        val server = greetingsApi(
            listOf(
                greet handledOnKtor { (who, shout) -> greetingOf(who, shout) },
                outbox handledWithOnKtor { },
            ),
            covers = emptyList(),
        ).startOnKtor(port = 0)

        try {
            apiClient(server.baseUrl, JacksonCodecs).use {
                it.transport.send(postToHello).status shouldBe 404
            }
        } finally {
            server.stop()
        }
    }
}
