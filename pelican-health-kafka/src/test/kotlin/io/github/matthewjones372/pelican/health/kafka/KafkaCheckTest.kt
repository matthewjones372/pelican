package io.github.matthewjones372.pelican.health.kafka

import io.github.matthewjones372.pelican.health.Status
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds

/** No broker at all, so it needs no Docker and runs in `build`. */
class KafkaCheckTest {

    /** How long it took is `KafkaBrokerTest`'s claim: it runs alone, and this shares the build's machine. */
    @Test
    fun `a cluster nobody answers for fails`() {
        // A port that was free a moment ago, so nothing is listening on it.
        val port = ServerSocket(0).use { it.localPort }
        Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:$port")).use { admin ->
            kafka(admin, timeout = 500.milliseconds).shouldBeInstanceOf<Status.Fail>()
        }
    }
}
