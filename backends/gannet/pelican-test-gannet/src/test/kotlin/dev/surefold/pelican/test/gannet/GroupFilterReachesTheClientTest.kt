package dev.surefold.pelican.test.gannet

import dev.surefold.pelican.api
import dev.surefold.pelican.before
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.filteredBy
import dev.surefold.pelican.forbidden
import dev.surefold.pelican.gannet.RequiresGannet
import dev.surefold.pelican.gannet.handledNow
import dev.surefold.pelican.jackson.JacksonCodecs
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

/** The loopback transport runs Gannet's interpreter, so the group's filters are the ones a bound server runs. */
@RequiresGannet
class GroupFilterReachesTheClientTest {

    private fun app() = api(
        endpoints = listOf(open handledNow { "ok" }) +
            listOf(closed handledNow { "secret" }).filteredBy(before { forbidden("Not yours") }),
        codecs = JacksonCodecs,
    ).onLoopback()

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
