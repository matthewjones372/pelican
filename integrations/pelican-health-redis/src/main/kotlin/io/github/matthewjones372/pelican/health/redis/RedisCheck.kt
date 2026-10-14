package io.github.matthewjones372.pelican.health.redis

import io.github.matthewjones372.pelican.health.Status
import io.lettuce.core.api.StatefulRedisConnection
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Passes when `PING` on [connection] answers `PONG` within [timeout].
 * The service's own connection, so the check sees its credentials, TLS and reconnect state.
 */
fun redis(connection: StatefulRedisConnection<*, *>, timeout: Duration = 1.seconds): Status =
    try {
        // While Lettuce reconnects it queues the command rather than refusing it, so the wait is what bounds it.
        val answer = connection.async().ping().get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (answer == "PONG") Status.Pass else Status.Fail("PING answered $answer")
    } catch (e: ExecutionException) {
        val cause = e.cause ?: e
        Status.Fail("${cause::class.java.simpleName}: ${cause.message}")
    } catch (_: TimeoutException) {
        Status.Fail("Redis did not answer within $timeout")
    }
