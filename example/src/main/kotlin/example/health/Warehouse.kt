package example.health

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // JacksonCodecs, which writes the health+json body
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC3")
 *         // health { }, the probes and the built-in checks. On `main` only:
 *         // not yet on Maven Central, so build it from source until the next release
 *         // implementation("io.github.matthewjones372:pelican-health:<next release>")
 *     }
 */

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.health.Health
import io.github.matthewjones372.pelican.health.Status
import io.github.matthewjones372.pelican.health.diskSpace
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.health.heapHeadroom
import io.github.matthewjones372.pelican.health.noDeadlockedThreads
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/** Stands in for the service's own dependency; a real one is `jdbc(dataSource)` or `http(url)`. */
class Warehouse {
    private val answering = AtomicBoolean(true)

    fun isAnswering(): Boolean = answering.get()

    fun goQuiet() = answering.set(false)
}

private const val MIB = 1024L * 1024L

/** The service's probes: built-in checks on the JVM, and one on its own dependency. */
fun warehouseHealth(warehouse: Warehouse): Health = health {
    live("threads") { noDeadlockedThreads() }
    ready("heap") { heapHeadroom(minFreeBytes = 32 * MIB) }
    // A full disk is worth a look but not worth draining traffic for.
    ready("disk", critical = false) { diskSpace(".", minFreeBytes = 256 * MIB) }
    ready("warehouse", timeout = 500.milliseconds, componentType = "component") {
        if (warehouse.isAnswering()) Status.Pass else Status.Fail("The warehouse is not answering")
    }
}

/** Probes are endpoints like any other: add them beside the service's own routes. */
fun warehouseApi(health: Health): Api = api(endpoints = health.endpoints, codecs = JacksonCodecs)
