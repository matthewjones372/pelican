package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
 * A service is usually more than its HTTP layer. It has an `ActorSystem`
 * before it has a route — for its cluster, its persistence, its streams — and
 * `start()` creating a second one means two of everything an actor system
 * carries, on a machine that was sized for one.
 *
 * So `start(system)` binds onto the system it is handed, and the rule that
 * follows is the one this test is here for: whoever created a system is who
 * gets to end it. `stop()` unbinds the port and leaves a borrowed system
 * running, because terminating it would take the caller's cluster down with
 * their HTTP port — and the caller has no way to put it back.
 */
@ExtendWith(TestKitJUnit5Extension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BorrowedSystemTest {

    /** Found by reflection, so the field has to be a real one: `@JvmField`. */
    @JUnit5TestKit
    @JvmField
    val testKit: ActorTestKit = JUnit5TestKitBuilder().withName("borrowed-system-test").build()

    private val hello = endpoint {
        get("hello")
        text()
    }

    private val api = api(
        endpoints = listOf(hello handledNow { "hello" }),
        codecs = JacksonCodecs,
    )

    /** The caller's system: the testkit's, and the testkit's to terminate. */
    private val system get() = testKit.system()

    private fun get(url: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `an api binds on a system it was handed`() {
        val server = api.start(system, port = 0)

        try {
            server.system shouldBe system
            server.ownsSystem shouldBe false
            get("${server.baseUrl}/hello").body() shouldBe "hello"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stopping a server does not terminate a system it borrowed`() {
        val server = api.start(system, port = 0)
        server.stop()

        // `stop` has completed, so if it were going to take the system with it
        // the system would be down by now rather than on its way.
        system.whenTerminated.toCompletableFuture().isDone shouldBe false

        // Still a system, not just an un-terminated one: it binds again.
        val second = api.start(system, port = 0)
        try {
            get("${second.baseUrl}/hello").body() shouldBe "hello"
        } finally {
            second.stop()
        }
    }

    @Test
    fun `the port is unbound even though the system lives on`() {
        val server = api.start(system, port = 0)
        val url = "${server.baseUrl}/hello"
        server.stop()

        val refused = runCatching { get(url) }.exceptionOrNull()

        refused shouldNotBe null
    }

    @Test
    fun `a server that made its own system still owns it`() {
        // The other half of the rule, so that adding the overload cannot
        // quietly turn the default into a borrow: `start()` with no system
        // creates one, and terminates it on `stop()`.
        val server = api.start(port = 0, systemName = "owned-system-test")
        val its = server.system

        server.ownsSystem shouldBe true
        server.stop()

        its.whenTerminated.toCompletableFuture().isDone shouldBe true
    }
}
