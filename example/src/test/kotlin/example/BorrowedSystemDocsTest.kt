package example

import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pekko.actor.testkit.typed.annotations.JUnit5TestKit
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.javadsl.JUnit5TestKitBuilder
import org.apache.pekko.actor.testkit.typed.javadsl.TestKitJUnit5Extension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
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
@ExtendWith(TestKitJUnit5Extension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BorrowedSystemDocsTest {

    /** Found by reflection, so the field has to be a real one: `@JvmField`. */
    @JUnit5TestKit
    @JvmField
    val testKit: ActorTestKit = JUnit5TestKitBuilder().withName("borrowed-docs-test").build()

    private val system get() = testKit.system()

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
