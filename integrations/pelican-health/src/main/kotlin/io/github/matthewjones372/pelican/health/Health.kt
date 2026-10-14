package io.github.matthewjones372.pelican.health

import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.MediaOutput
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorMedia
import io.github.matthewjones372.pelican.media
import io.github.matthewjones372.pelican.orFail
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The media type both probes answer in. */
const val HEALTH_JSON: String = "application/health+json"

private val DEFAULT_TIMEOUT = 2.seconds
private val NAME = Regex("[a-z0-9-]+")

/**
 * Live and ready probes from the checks declared in [block].
 *
 * ```kotlin
 * val health = health {
 *     live("threads") { noDeadlockedThreads() }
 *     ready("orders-db") { jdbc(dataSource) }
 * }
 * api(endpoints = health.endpoints + routes, codecs = JacksonCodecs)
 * ```
 *
 * @param prefix mounts the probes under it, for a service served below the root.
 * @param detail puts each check's `output` in the body. Off by default: a driver's
 *   error names hosts, and the probes are public.
 */
fun health(prefix: String? = null, detail: Boolean = false, block: HealthBuilder.() -> Unit): Health {
    val builder = HealthBuilder().apply(block)
    return Health(prefix, detail, builder.live.toList(), builder.ready.toList())
}

/** Where [health]'s checks are declared. A name is `[a-z0-9-]+` and used once across both probes. */
class HealthBuilder internal constructor() {
    // Accumulators inside a builder that [health] freezes before returning.
    internal val live = mutableListOf<Registered>()
    internal val ready = mutableListOf<Registered>()

    /** A check the process itself answers for. A failure here gets the process restarted. */
    fun live(
        name: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        critical: Boolean = true,
        componentType: String? = null,
        check: Check,
    ) { live += register(name, timeout, critical, componentType, check) }

    /** A check on something the service needs to take traffic. A failure takes it out of rotation. */
    fun ready(
        name: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        critical: Boolean = true,
        componentType: String? = null,
        check: Check,
    ) { ready += register(name, timeout, critical, componentType, check) }

    private fun register(
        name: String,
        timeout: Duration,
        critical: Boolean,
        componentType: String?,
        check: Check,
    ): Registered {
        require(NAME.matches(name)) {
            "'$name' is not a check name: the body keys each check as name:responseTime, so a name is " +
                "lower-case letters, digits and hyphens."
        }
        require((live + ready).none { it.name == name }) {
            "Two checks are named '$name', and the body has one key per name — the second would hide the first."
        }
        require(timeout.isPositive()) { "The check '$name' needs a timeout above zero, not $timeout." }
        return Registered(name, timeout, critical, componentType, check)
    }
}

internal class Registered(
    val name: String,
    val timeout: Duration,
    val critical: Boolean,
    val componentType: String?,
    val check: Check,
)

/** The two probes. Add [endpoints] to the API; [live] and [ready] are there for a typed client to call. */
class Health internal constructor(
    prefix: String?,
    private val detail: Boolean,
    private val liveChecks: List<Registered>,
    readyChecks: List<Registered>,
) {
    private val liveProbe = Probe(prefix, "live")
    private val readyProbe = Probe(prefix, "ready")

    // Not alive is never ready, so ready runs the live checks as well.
    private val readyAndLive = liveChecks + readyChecks

    val live: Endpoint<Unit, Outcome<HealthReport, HealthReport>> = liveProbe.endpoint
    val ready: Endpoint<Unit, Outcome<HealthReport, HealthReport>> = readyProbe.endpoint

    val endpoints: List<ServerEndpoint> = listOf(
        ServerEndpoint(live) { _ -> liveProbe.answer(liveChecks) },
        ServerEndpoint(ready) { _ -> readyProbe.answer(readyAndLive) },
    )

    private fun Probe.answer(checks: List<Registered>): CompletionStage<Any?> {
        val all = checks.map { it.start() }
            .fold(CompletableFuture.completedFuture(emptyList<Answered>())) { done, next ->
                done.thenCombine(next) { answered, one -> answered + one }
            }
        return all.thenApply { results ->
            val overall = results.maxOfOrNull { (_, level) -> level } ?: Level.PASS
            val report = HealthReport(overall.wire, results.associate { (entry, _) -> entry })
            if (overall == Level.FAIL) unavailable(report) else passing(report)
        }
    }

    private fun Registered.start(): CompletableFuture<Answered> {
        val time = Instant.now()
        val started = System.nanoTime()
        val status = CompletableFuture<Status>()
        val thread = Thread.ofVirtual().name("pelican-health-$name").start { status.complete(attempt(check)) }
        return status
            .completeOnTimeout(
                Status.Fail("timed out after $timeout"),
                timeout.inWholeNanoseconds,
                TimeUnit.NANOSECONDS,
            )
            // A check still running past its timeout is told to stop, so a hung
            // dependency does not leave one thread behind per probe.
            .whenComplete { _, _ -> thread.interrupt() }
            .thenApply { found ->
                val level = levelOf(found, critical)
                val result = CheckResult(
                    status = level.wire,
                    observedValue = (System.nanoTime() - started) / NANOS_PER_MILLI,
                    observedUnit = "ms",
                    time = time.toString(),
                    componentType = componentType,
                    output = if (detail) outputOf(found) else null,
                )
                ("$name:responseTime" to listOf(result)) to level
            }
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

/** A check's entry in the body, and the level it counts as towards the overall status. */
private typealias Answered = Pair<Pair<String, List<CheckResult>>, Level>

/** Worst last, so the overall status is the maximum. */
private enum class Level(val wire: String) { PASS("pass"), WARN("warn"), FAIL("fail") }

private fun levelOf(status: Status, critical: Boolean): Level = when (status) {
    Status.Pass -> Level.PASS
    is Status.Warn -> Level.WARN
    is Status.Fail -> if (critical) Level.FAIL else Level.WARN
}

private fun outputOf(status: Status): String? = when (status) {
    Status.Pass -> null
    is Status.Warn -> status.output
    is Status.Fail -> status.output
}

// A check is the service's own code against a dependency, and whatever it
// throws means the dependency is not healthy. `Error` still propagates.
@Suppress("TooGenericExceptionCaught")
private fun attempt(check: Check): Status =
    try {
        check.check()
    } catch (e: Exception) {
        Status.Fail(e::class.java.simpleName + e.message?.let { ": $it" }.orEmpty())
    }

private class Probe(prefix: String?, name: String) {
    val passing: MediaOutput<HealthReport> = media(HEALTH_JSON, status = 200)

    // A failure, not a second success: every client, gateway and load balancer reads a 503 as one, so a
    // Pelican client hands it back as `Err(report)` rather than throwing. Declared here rather than in the
    // endpoint block, whose own `errorJson` would register it as it was called. See spec 0070.
    val unavailable: ErrorOutput<HealthReport> =
        errorMedia(HEALTH_JSON, 503, "A check failed; take this out of rotation")

    val endpoint: Endpoint<Unit, Outcome<HealthReport, HealthReport>> = endpoint {
        get(listOfNotNull(prefix, "health", name).joinToString("/"))
        noSecurity()
        tag("health")
        passing orFail unavailable
    }
}
