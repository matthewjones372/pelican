package io.github.matthewjones372.pelican.health.redis

import io.github.matthewjones372.pelican.health.Status
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.lettuce.core.RedisClient
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import kotlin.time.Duration.Companion.seconds

/** Against a real Redis in Docker; `containerTests` runs it, `build` does not. */
@Tag("containers")
class RedisCheckTest {

    @Test
    fun `a running Redis passes, and the same connection fails within the timeout once it stops`() {
        val server = GenericContainer("redis:7.4-alpine").withExposedPorts(6379).also { it.start() }
        val client = RedisClient.create("redis://${server.host}:${server.getMappedPort(6379)}")
        try {
            client.connect().use { connection ->
                redis(connection, timeout = 5.seconds) shouldBe Status.Pass

                server.stop()

                val started = System.nanoTime()
                redis(connection, timeout = 2.seconds).shouldBeInstanceOf<Status.Fail>()
                ((System.nanoTime() - started) / 1_000_000) shouldBeLessThan 5_000
            }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `a connection the service has closed fails`() {
        val server = GenericContainer("redis:7.4-alpine").withExposedPorts(6379).also { it.start() }
        val client = RedisClient.create("redis://${server.host}:${server.getMappedPort(6379)}")
        try {
            val connection = client.connect()
            connection.close()

            redis(connection).shouldBeInstanceOf<Status.Fail>().output shouldContain "closed"
        } finally {
            client.shutdown()
            server.stop()
        }
    }
}
