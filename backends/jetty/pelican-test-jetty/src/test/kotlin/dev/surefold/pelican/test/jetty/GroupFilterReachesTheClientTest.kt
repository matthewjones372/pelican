package dev.surefold.pelican.test.jetty

import dev.surefold.pelican.api
import dev.surefold.pelican.before
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.filteredBy
import dev.surefold.pelican.forbidden
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.jetty.handledNow
import dev.surefold.pelican.test.shouldBeApiError
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val open = endpoint {
    get("health")
    text()
}

private val closed = endpoint {
    get("reports")
    text()
}

/** The in-memory transport runs Jetty's interpreter, so the group's filters are the ones a bound server runs. */
class GroupFilterReachesTheClientTest {

    private fun app() = api(
        endpoints = listOf(open handledNow { "ok" }) +
            listOf(closed handledNow { "secret" }).filteredBy(before { forbidden("Not yours") }),
        codecs = JacksonCodecs,
    ).inMemory()

    @Test
    fun `the group's filter refuses the call the client makes`() {
        app().use { client ->
            client.shouldBeApiError(client.response(closed, Unit), status = 403, error = "Not yours")
        }
    }

    @Test
    fun `an endpoint outside the group is untouched`() {
        app().use { client ->
            client.call(open, Unit) shouldBe "ok"
        }
    }
}
