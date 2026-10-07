package io.github.matthewjones372.pelican.health

import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Borrows a connection from [dataSource] and asks the driver whether it is valid, then gives it back.
 * The service's own pool, so the check sees the credentials and limits the code it vouches for does.
 */
fun jdbc(dataSource: DataSource, timeout: Duration = 1.seconds): Status =
    try {
        dataSource.connection.use { connection ->
            // `isValid` takes whole seconds, and 0 means wait forever.
            if (connection.isValid(timeout.inWholeSeconds.coerceAtLeast(1).toInt())) Status.Pass
            else Status.Fail("The connection did not answer within $timeout")
        }
    } catch (e: SQLException) {
        Status.Fail("${e::class.java.simpleName}: ${e.message}")
    } catch (e: IllegalStateException) {
        // How a closed pool refuses on H2's pool and others; Hikari uses SQLException.
        Status.Fail("${e::class.java.simpleName}: ${e.message}")
    }
