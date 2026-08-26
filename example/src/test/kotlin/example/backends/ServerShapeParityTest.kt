package example.backends

import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionStage
import kotlin.concurrent.thread
import io.github.matthewjones372.pelican.pekko.PelicanServer as PekkoServer
import io.github.matthewjones372.pelican.pekko.start as startOnPekko

/**
 * The handle a backend hands back, pinned to one shape.
 *
 * `StartParityTest` pins how a server is started; this pins what comes back.
 * Pekko's was neither `AutoCloseable` nor blockable and its `stop()` returned a
 * `CompletionStage` where the others returned nothing, so the `use { }` block a
 * reader learned on one backend did not compile on the next. The shape is
 * pinned by reflection rather than by a supertype, so a returning backend is
 * held to it by adding a row to [handles].
 */
class ServerShapeParityTest {

    private val handles = listOf(
        "pekko" to PekkoServer::class.java,
    )

    @Test
    fun `every server handle closes, blocks and stops the same way`() {
        handles.forEach { (name, type) ->
            withClue(name) {
                AutoCloseable::class.java.isAssignableFrom(type) shouldBe true
                type.getMethod("block").returnType shouldBe Void.TYPE
                type.getMethod("stop").returnType shouldBe Void.TYPE
                type.getMethod("stopAsync").returnType shouldBe CompletionStage::class.java
            }
        }
    }

    // The same four lines per backend. The duplication is the claim: the
    // `PelicanServer` types share no supertype but `AutoCloseable`, so nothing
    // but their carrying the same members makes one block compile against all
    // of them.

    @Test
    fun `pekko serves inside a use block, and closing releases block`() {
        val parked = pekkoApi().startOnPekko(port = 0, systemName = "shape-parity-pekko").use { server ->
            answersOn(server.baseUrl)
            parkedIn(server::block)
        }

        parked.releasedWithin()
    }

    @Test
    fun `and stopAsync completes once the server is down, on every backend`() {
        val pekko = pekkoApi().startOnPekko(port = 0, systemName = "shape-parity-async")

        val parked = listOf("pekko" to parkedIn(pekko::block))

        listOf(pekko::stopAsync).forEach { it().toCompletableFuture().join() }

        parked.forEach { (name, thread) -> withClue(name) { thread.releasedWithin() } }
    }

    private fun answersOn(baseUrl: String) {
        apiClient(baseUrl, JacksonCodecs).use { it.response(motd, Unit) shouldHaveStatus 200 }
    }

    /** A thread already inside `block()`, so that stopping has something to release. */
    private fun parkedIn(block: () -> Unit): Thread = thread(name = "parked-in-block") { block() }

    private fun Thread.releasedWithin() {
        join(RELEASE_MILLIS)
        withClue("block() was still parked ${RELEASE_MILLIS}ms after the server stopped") {
            isAlive shouldBe false
        }
    }

    private companion object {
        const val RELEASE_MILLIS = 10_000L
    }
}
