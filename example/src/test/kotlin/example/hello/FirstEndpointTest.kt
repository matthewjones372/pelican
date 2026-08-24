package example.hello

import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.shouldBuild
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The two assertions the README's "Your first endpoint" section shows, so that
 * the smallest example on the front page is a thing that runs rather than a
 * thing that reads well.
 */
class FirstEndpointTest {

    private val app = greetings().inMemory("first-endpoint")

    @Test
    fun `answers the greeting, by endpoint value`() {
        app.call(greet, "world") shouldBe Greeting("Hello, world!")
    }

    @Test
    fun `is served at the URL its callers were given`() {
        app.request(greet, "world") shouldBuild "GET /hello/world"
    }
}
