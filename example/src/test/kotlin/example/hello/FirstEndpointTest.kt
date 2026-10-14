package example.hello

import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.test.jetty.inMemory
import io.github.matthewjones372.pelican.test.shouldBuild
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import io.github.matthewjones372.pelican.test.pekko.inMemory as inMemoryOnPekko

class FirstEndpointTest {

    private val app = greetings().inMemory(Handlers.onVirtualThreads)

    private val onPekko = greetingsOnPekko().inMemoryOnPekko("first-endpoint-pekko")

    @Test
    fun `answers the greeting, by endpoint value`() {
        app.call(greet, "world") shouldBe Greeting("Hello, world!")
    }

    @Test
    fun `is served at the URL its callers were given`() {
        app.request(greet, "world") shouldBuild "GET /hello/world"
    }

    @Test
    fun `answers the same greeting on Pekko`() {
        onPekko.call(greet, "world") shouldBe Greeting("Hello, world!")
    }
}
