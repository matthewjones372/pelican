package io.github.matthewjones372.pelican.health.kafka

import io.github.matthewjones372.pelican.health.Status
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.DescribeClusterOptions
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Passes when the cluster behind [admin] names a controller and at least one broker within [timeout].
 * The service's own client, so the check sees its bootstrap servers, credentials and TLS.
 */
fun kafka(admin: Admin, timeout: Duration = 1.seconds): Status {
    val millis = timeout.inWholeMilliseconds
    val cluster = admin.describeCluster(DescribeClusterOptions().timeoutMs(millis.toInt()))
    return try {
        val controller = cluster.controller().get(millis, TimeUnit.MILLISECONDS)
        val brokers = cluster.nodes().get(millis, TimeUnit.MILLISECONDS)
        when {
            controller == null || controller.isEmpty -> Status.Fail("The cluster names no controller")
            brokers.isEmpty() -> Status.Fail("The cluster names no brokers")
            else -> Status.Pass
        }
    } catch (e: ExecutionException) {
        val cause = e.cause ?: e
        Status.Fail("${cause::class.java.simpleName}: ${cause.message}")
    } catch (_: TimeoutException) {
        Status.Fail("The cluster did not answer within $timeout")
    }
}
