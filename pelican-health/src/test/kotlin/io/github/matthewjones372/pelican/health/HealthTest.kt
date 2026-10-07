package io.github.matthewjones372.pelican.health

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.inMemory
import io.github.matthewjones372.pelican.test.shouldBeError
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.milliseconds

class HealthTest {

    private fun clientFor(health: Health): ApiClient =
        api(endpoints = health.endpoints, codecs = JacksonCodecs).inMemory()

    private fun report(body: String): HealthReport =
        JacksonCodecs.codec<HealthReport>(typeOf<HealthReport>()).decodeFromString(body)

    /** Never answers, so only the timeout can end it. */
    private val hangs = Check { CountDownLatch(1).await(); Status.Pass }

    @Test
    fun `every check passing answers 200 in health+json, one entry per check`() {
        val health = health {
            ready("orders-db", componentType = "datastore") { Status.Pass }
            ready("payments") { Status.Pass }
        }

        val response = clientFor(health).response(health.ready, Unit)

        response.status shouldBe 200
        response.contentType.orEmpty() shouldStartWith "application/health+json"
        val body = report(response.body)
        body.status shouldBe "pass"
        body.checks.keys shouldBe setOf("orders-db:responseTime", "payments:responseTime")
        val db = body.checks.getValue("orders-db:responseTime").single()
        db.status shouldBe "pass"
        db.componentType shouldBe "datastore"
        db.observedUnit shouldBe "ms"
    }

    @Test
    fun `a check that hangs past its timeout fails, and the probe answers 503 without waiting for it`() {
        val health = health(detail = true) { ready("slow", timeout = 100.milliseconds, check = hangs) }

        val started = System.nanoTime()
        val response = clientFor(health).response(health.ready, Unit)
        val tookMillis = (System.nanoTime() - started) / 1_000_000

        response.status shouldBe 503
        withClue("a 100ms timeout should answer well inside a second") { tookMillis shouldBeLessThan 1_000 }
        val slow = report(response.body).checks.getValue("slow:responseTime").single()
        slow.status shouldBe "fail"
        slow.output.orEmpty() shouldContain "timed out after 100ms"
    }

    @Test
    fun `a failing check that is not critical is a warn, and the probe still answers 200`() {
        val health = health {
            ready("db") { Status.Pass }
            ready("disk", critical = false) { Status.Fail("2% free") }
        }

        val response = clientFor(health).response(health.ready, Unit)

        response.status shouldBe 200
        val body = report(response.body)
        body.status shouldBe "warn"
        body.checks.getValue("disk:responseTime").single().status shouldBe "warn"
    }

    @Test
    fun `a failing probe is the declared failure, so a client reads the report back as Err`() {
        val health = health { ready("db") { Status.Fail("no connection") } }

        val failing = clientFor(health).outcome(health.ready, Unit).shouldBeError()

        failing.status shouldBe "fail"
        failing.checks.getValue("db:responseTime").single().status shouldBe "fail"
        clientFor(health).outcome(health.live, Unit).shouldBeOk().status shouldBe "pass"
    }

    @Test
    fun `and the document says the 503 is a failure, in health+json`() {
        val health = health { ready("db") { Status.Pass } }

        health.ready.errors.single().let { declared ->
            declared.status shouldBe 503
            declared.mediaType shouldBe HEALTH_JSON
            declared.description shouldBe "A check failed; take this out of rotation"
        }
    }

    @Test
    fun `a check that throws fails with the exception's class name`() {
        val health = health(detail = true) {
            ready("db") { throw IllegalStateException("pool closed") }
        }

        val response = clientFor(health).response(health.ready, Unit)

        response.status shouldBe 503
        report(response.body).checks.getValue("db:responseTime").single().output.orEmpty() shouldContain
            "IllegalStateException"
    }

    @Test
    fun `without detail the body says which check failed and not why`() {
        val health = health { ready("db") { Status.Fail("connection refused to 10.0.0.7:5432") } }

        val response = clientFor(health).response(health.ready, Unit)

        response.status shouldBe 503
        response.body.contains("10.0.0.7") shouldBe false
        report(response.body).checks.getValue("db:responseTime").single().output.shouldBeNull()
    }

    @Test
    fun `live runs only live checks, and ready runs both`() {
        val health = health {
            live("threads") { Status.Pass }
            ready("db") { Status.Fail("down") }
        }
        val client = clientFor(health)

        val live = client.response(health.live, Unit)
        live.status shouldBe 200
        report(live.body).checks shouldNotContainKey "db:responseTime"

        val ready = client.response(health.ready, Unit)
        ready.status shouldBe 503
        report(ready.body).checks shouldContainKey "threads:responseTime"
    }

    @Test
    fun `no checks at all is a pass`() {
        val health = health { }

        val response = clientFor(health).response(health.live, Unit)

        response.status shouldBe 200
        report(response.body).checks.keys.shouldBeEmpty()
    }

    @Test
    fun `the probes are public, tagged, and mounted under a prefix when given one`() {
        val plain = health { }
        plain.live.pathSpec.template shouldBe "/health/live"
        plain.ready.pathSpec.template shouldBe "/health/ready"
        plain.ready.security shouldBe emptyList()
        plain.ready.tags shouldBe listOf("health")

        val mounted = health(prefix = "api") { }
        mounted.ready.pathSpec.template shouldBe "/api/health/ready"
        clientFor(mounted).response(mounted.ready, Unit).status shouldBe 200
    }

    @Test
    fun `a name the body cannot key by, or a name used twice, is refused when the checks are built`() {
        shouldThrow<IllegalArgumentException> { health { ready("Orders DB") { Status.Pass } } }
            .message.orEmpty() shouldContain "Orders DB"

        shouldThrow<IllegalArgumentException> {
            health {
                live("db") { Status.Pass }
                ready("db") { Status.Pass }
            }
        }.message.orEmpty() shouldContain "'db'"
    }
}
