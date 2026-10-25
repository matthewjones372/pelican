package io.github.matthewjones372.pelican.test.gannet

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.filteredBy
import io.github.matthewjones372.pelican.forbidden
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.shouldBeApiError
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
