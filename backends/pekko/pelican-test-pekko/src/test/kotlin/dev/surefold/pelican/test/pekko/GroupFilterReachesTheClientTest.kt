package dev.surefold.pelican.test.pekko

import dev.surefold.pelican.api
import dev.surefold.pelican.before
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.filteredBy
import dev.surefold.pelican.forbidden
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.pekko.handledNow
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

/**
 * `handlerFor` folds the chain, and the in-memory transport shares it with
 * every interpreter — so a suite calling through the typed client exercises
 * the group's filters and not a second copy of the rule.
 */
class GroupFilterReachesTheClientTest {

    private fun app() = api(
        endpoints = listOf(open handledNow { "ok" }) +
            listOf(closed handledNow { "secret" }).filteredBy(before { forbidden("Not yours") }),
        codecs = JacksonCodecs,
    ).inMemory("group-filters")

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
