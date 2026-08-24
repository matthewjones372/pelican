package example

import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A service that brought its own `ActorSystem` does not have to give up its
 * documentation to keep it.
 *
 * `startWithDocs(system)` is the pairing worth a test of its own: the borrowed
 * system reaches the *interpreter* through one overload and the document route
 * through another, and a version of this that quietly started a second system
 * for the docs would still serve every page correctly. What says otherwise is
 * the system still being up after `stop()`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BorrowedSystemDocsTest {

    @Suppress("ForbiddenVoid") // Pekko's Java DSL; see config/detekt/detekt.yml.
    private val system = ActorSystem.create(Behaviors.empty<Void>(), "borrowed-docs-test")

    @AfterAll
    fun stop() {
        system.terminate()
        system.whenTerminated.toCompletableFuture().join()
    }

    private fun get(url: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `the endpoints and the document are both served on a borrowed system`() {
        val server = ordersApi().startWithDocs(system, port = 0, docs = ordersDocs)

        try {
            server.ownsSystem shouldBe false

            val spec = get("${server.baseUrl}/openapi.json")
            spec.statusCode() shouldBe 200
            spec.body() shouldContain "\"/users/{userId}\""

            get("${server.baseUrl}/api-docs").statusCode() shouldBe 200
        } finally {
            server.stop().toCompletableFuture().join()
        }

        system.whenTerminated.toCompletableFuture().isDone shouldBe false
    }
}
