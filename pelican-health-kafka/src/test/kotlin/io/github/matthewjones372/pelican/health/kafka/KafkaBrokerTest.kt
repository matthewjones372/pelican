package io.github.matthewjones372.pelican.health.kafka

import io.github.matthewjones372.pelican.health.Status
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.kafka.KafkaContainer
import kotlin.time.Duration.Companion.seconds

/** Against a real broker in Docker; `containerTests` runs it, `build` does not. */
@Tag("containers")
class KafkaBrokerTest {

    @Test
    fun `a running broker passes, and the same admin fails within the timeout once it stops`() {
        val broker = KafkaContainer("apache/kafka:3.9.1").also { it.start() }
        Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to broker.bootstrapServers)).use { admin ->
            kafka(admin, timeout = 10.seconds) shouldBe Status.Pass

            broker.stop()

            val started = System.nanoTime()
            kafka(admin, timeout = 2.seconds).shouldBeInstanceOf<Status.Fail>()
            ((System.nanoTime() - started) / 1_000_000) shouldBeLessThan 5_000
        }
    }
}
