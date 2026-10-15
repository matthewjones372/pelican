package io.github.matthewjones372.pelican.benchmarks.wire

import org.HdrHistogram.Histogram
import org.HdrHistogram.Recorder
import org.eclipse.jetty.client.BufferingResponseListener
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.Result
import org.eclipse.jetty.client.StringRequestContent
import org.eclipse.jetty.http.HttpMethod
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.time.Duration
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** Requests a second, in turn, for each server until it stops keeping up. */
private val RATES =
    listOf(1_000, 5_000, 10_000, 20_000, 30_000, 40_000, 50_000, 60_000, 70_000, 80_000, 100_000, 120_000)

/** Responses a second for the streaming scenario, each one [FRAMES] frames. */
private val STREAM_RATES = listOf(250, 500, 1_000, 2_000, 3_000, 4_000, 6_000, 8_000, 10_000, 15_000, 20_000)

private const val REST_WARMUP_RATE = 5_000
private const val STREAM_WARMUP_RATE = 250

/** `rest` alternates the JSON GET and POST; `stream` alternates the NDJSON and SSE responses of [FRAMES] frames. */
private enum class Scenario(val rates: List<Int>, val warmupRate: Int, val unit: String) {
    REST(RATES, REST_WARMUP_RATE, "req/s"),
    STREAM(STREAM_RATES, STREAM_WARMUP_RATE, "responses/s"),
}

/** How long each phase runs, in seconds; [SMOKE] only checks that the servers start and answer. */
private data class Schedule(val warmup: Int, val settle: Int, val measure: Int, val steps: Int)

private val FULL = Schedule(warmup = 30, settle = 5, measure = 15, steps = Int.MAX_VALUE)
private val SMOKE = Schedule(warmup = 1, settle = 1, measure = 2, steps = 1)
private const val PAUSE_MILLIS = 2_000L

/** Senders sharing a step's schedule, so one thread's call into the client is not the ceiling. */
private const val SENDERS = 4

/** Requests in flight at once: the driver's connection count, as `wrk2 -c` would set it. */
private const val IN_FLIGHT = 256

/** A step whose senders fall this far behind their schedule is abandoned: the server is not keeping up. */
private val ABANDON_LAG_NANOS = TimeUnit.SECONDS.toNanos(2)
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)

/** What keeping up means: p99 under this, and errors under [ERROR_LIMIT]. */
private val P99_LIMIT_NANOS = TimeUnit.MILLISECONDS.toNanos(100)
private const val ERROR_LIMIT = 0.001

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MICRO = 1_000.0
private const val NANOS_PER_MILLI = 1_000_000L
private const val HISTOGRAM_DIGITS = 3

private data class Step(
    val server: String,
    val rate: Int,
    val latency: Histogram,
    val errors: Long,
    val abandoned: Boolean,
) {
    val keptUp: Boolean
        get() = !abandoned &&
            latency.getValueAtPercentile(P99) < P99_LIMIT_NANOS &&
            errors <= latency.totalCount * ERROR_LIMIT
}

private const val P50 = 50.0
private const val P99 = 99.0
private const val P999 = 99.9

/** A server in its own JVM, started by [WireServer]'s `main` and stopped by closing its stdin. */
private class Child(name: String, side: Side) : AutoCloseable {
    private val process: Process = ProcessBuilder(
        ProcessHandle.current().info().command().orElseThrow(),
        "-Xms1g",
        "-Xmx1g",
        "-Dpelican.jetty.pool=${side.pool}",
        "-Dpelican.jetty.connector=${side.connector}",
        "-cp",
        side.classpath,
        "io.github.matthewjones372.pelican.benchmarks.wire.WireServerKt",
        name,
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    val port: Int = process.inputReader().readLine()?.removePrefix("PORT ")?.toInt()
        ?: error("$name exited before printing its port")

    override fun close() {
        process.outputStream.close()
        if (!process.waitFor(STOP_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
    }

    private companion object {
        const val STOP_SECONDS = 20L
    }
}

/**
 * An open-model load driver: requests leave on a fixed schedule whether or not earlier ones have answered, and each
 * one's latency runs from when the schedule said it should leave. A server that stalls therefore shows the stall in
 * every request that queued behind it, rather than slowing the driver down to a rate it can manage (coordinated
 * omission, which a closed loop of "send, wait, send" reports as a short tail).
 *
 * On Jetty's client rather than the JDK's: the JDK client fell behind its own schedule at 20,000 to 40,000 requests a
 * second against both servers, so above that it was measuring itself.
 */
private class Driver(port: Int, private val scenario: Scenario) : AutoCloseable {
    private val client = HttpClient().apply {
        maxConnectionsPerDestination = IN_FLIGHT
        connectTimeout = REQUEST_TIMEOUT.toMillis()
        start()
    }

    private val getUri = URI("http://127.0.0.1:$port/items/7?limit=25")
    private val postUri = URI("http://127.0.0.1:$port/items")
    private val ndjsonUri = URI("http://127.0.0.1:$port/ndjson")
    private val sseUri = URI("http://127.0.0.1:$port/sse")

    /** The scenario's first request when [even], its second otherwise; a stream is timed to its last byte. */
    private fun send(even: Boolean, done: (Int?, String?) -> Unit) {
        val request = when (scenario) {
            Scenario.REST -> if (even) {
                client.newRequest(getUri)
            } else {
                client.newRequest(postUri).method(HttpMethod.POST).body(StringRequestContent("application/json", BODY))
            }

            Scenario.STREAM -> client.newRequest(if (even) ndjsonUri else sseUri)
        }
        request.timeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).send(
            object : BufferingResponseListener() {
                override fun onComplete(result: Result) {
                    if (result.isFailed) done(null, null) else done(result.response.status, contentAsString)
                }
            },
        )
    }

    /** That both endpoints answer what the description says, before anything is timed. */
    fun verify() {
        val answers = LinkedBlockingQueue<Pair<Int?, String?>>()
        send(true) { s, b -> answers.put(s to b) }
        val first = answers.take()
        send(false) { s, b -> answers.put(s to b) }
        val second = answers.take()
        when (scenario) {
            Scenario.REST -> {
                check(first == (200 to """{"id":7,"name":"item-25"}""")) { "GET answered wrongly" }
                check(second == (201 to """{"id":1,"name":"anvil"}""")) { "POST answered wrongly" }
            }

            Scenario.STREAM -> {
                val frame = """{"id":$FRAMES,"name":"$FRAME_NAME"}"""
                val ndjson = first.second.orEmpty().lines().filter { it.isNotEmpty() }
                check(first.first == 200 && ndjson.size == FRAMES && ndjson.last() == frame) {
                    "NDJSON answered wrongly"
                }
                val sse = second.second.orEmpty().lines().filter { it.startsWith("data:") }
                check(second.first == 200 && sse.size == FRAMES && sse.last().endsWith(frame)) {
                    "SSE answered wrongly"
                }
            }
        }
    }

    override fun close() = client.stop()

    /** [rate] requests a second, alternating the scenario's two, [settle] seconds unrecorded and [measure] recorded. */
    fun run(server: String, rate: Int, settle: Int, measure: Int): Step {
        val recorder = Recorder(HISTOGRAM_DIGITS)
        val errors = AtomicLong()
        val abandoned = AtomicBoolean()
        val inFlight = Semaphore(IN_FLIGHT)
        val start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PAUSE_MILLIS / 2)
        val recordFrom = start + settle * NANOS_PER_SECOND
        val total = rate.toLong() * (settle + measure)

        val senders = (0 until SENDERS).map { first ->
            thread(name = "wire-sender-$first") {
                var i = first.toLong()
                while (i < total && !abandoned.get()) {
                    val intended = start + i * NANOS_PER_SECOND / rate
                    val wait = intended - System.nanoTime()
                    if (wait > 0) LockSupport.parkNanos(wait)
                    inFlight.acquireUninterruptibly()
                    if (System.nanoTime() - intended > ABANDON_LAG_NANOS) {
                        abandoned.set(true)
                        inFlight.release()
                        break
                    }
                    send(i % 2 == 0L) { status, _ ->
                        val latency = System.nanoTime() - intended
                        inFlight.release()
                        if (intended >= recordFrom) {
                            if (status == null || status !in OK) errors.incrementAndGet()
                            recorder.recordValue(latency)
                        }
                    }
                    i += SENDERS
                }
            }
        }
        senders.forEach { it.join() }
        inFlight.acquireUninterruptibly(IN_FLIGHT)
        return Step(server, rate, recorder.intervalHistogram, errors.get(), abandoned.get())
    }

    private companion object {
        val OK = 200..299
        const val BODY = """{"name":"anvil"}"""
    }
}

private fun loadAverage(): String =
    "%.2f".format(Locale.ROOT, ManagementFactory.getOperatingSystemMXBean().systemLoadAverage)

private fun duration(nanos: Long): String = when {
    nanos >= NANOS_PER_MILLI -> "%.1fms".format(Locale.ROOT, nanos.toDouble() / NANOS_PER_MILLI)
    else -> "%.0fµs".format(Locale.ROOT, nanos / NANOS_PER_MICRO)
}

private fun Step.row(): String = "| $server | $rate | ${duration(latency.getValueAtPercentile(P50))} | " +
    "${duration(latency.getValueAtPercentile(P99))} | ${duration(latency.getValueAtPercentile(P999))} | " +
    "${duration(latency.maxValue)} | ${latency.totalCount} | $errors | ${if (keptUp) "yes" else "no"} |"

/** One ladder: warms [server] up, then climbs the scenario's rates until it stops keeping up. */
private fun ladder(
    server: String,
    label: String,
    side: Side,
    scenario: Scenario,
    schedule: Schedule,
    notes: MutableList<String>,
): List<Step> = Child(server, side).use { child ->
    Driver(child.port, scenario).use { driver ->
        driver.verify()
        val before = loadAverage()
        println("$label: warming up for ${schedule.warmup}s at ${scenario.warmupRate} ${scenario.unit}, load $before")
        driver.run(label, scenario.warmupRate, 0, schedule.warmup)
        val steps = mutableListOf<Step>()
        for (rate in scenario.rates.take(schedule.steps)) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PAUSE_MILLIS))
            val step = driver.run(label, rate, schedule.settle, schedule.measure)
            println(step.row())
            steps += step
            if (!step.keptUp) break
        }
        notes += "$label, ${scenario.name.lowercase()}: load average $before before warmup, " +
            "${loadAverage()} after its last rate"
        steps
    }
}

private fun header(scenario: Scenario) = listOf(
    "| server | ${scenario.unit} | p50 | p99 | p99.9 | max | requests | errors | kept up |",
    "|---|---|---|---|---|---|---|---|---|",
)

private fun median(values: List<Long>): Long = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

/** The highest rate a ladder kept up at, or 0 if it kept up at none. */
private fun ceiling(steps: List<Step>): Int = steps.takeWhile { it.keptUp }.lastOrNull()?.rate ?: 0

/**
 * A's runs against B's, per rate and percentile: medians, each side's range, and whether the ranges are disjoint. A
 * difference inside either side's own run-to-run spread is not one this run can attribute to the change.
 */
private fun comparison(server: String, scenario: Scenario, a: List<List<Step>>, b: List<List<Step>>): List<String> {
    val lines = mutableListOf(
        "### $server, ${scenario.name.lowercase()}",
        "",
        "Highest sustained ${scenario.unit}: A ${median(a.map { ceiling(it).toLong() })} " +
            "(runs: ${a.joinToString { ceiling(it).toString() }}), B ${median(b.map { ceiling(it).toLong() })} " +
            "(runs: ${b.joinToString { ceiling(it).toString() }}).",
        "",
        "| ${scenario.unit} | percentile | A median | A range | B median | B range | B vs A | outside the spread |",
        "|---|---|---|---|---|---|---|---|",
    )
    for (rate in scenario.rates) {
        val atA = a.mapNotNull { run -> run.find { it.rate == rate } }
        val atB = b.mapNotNull { run -> run.find { it.rate == rate } }
        if (atA.isEmpty() || atB.isEmpty()) continue
        for ((name, percentile) in listOf("p50" to P50, "p99" to P99, "p99.9" to P999)) {
            val va = atA.map { it.latency.getValueAtPercentile(percentile) }
            val vb = atB.map { it.latency.getValueAtPercentile(percentile) }
            val change = PERCENT * (median(vb) - median(va)) / median(va)
            val outside = when {
                va.size < 2 || vb.size < 2 -> "one run"
                vb.max() < va.min() -> "yes, faster"
                vb.min() > va.max() -> "yes, slower"
                else -> "no"
            }
            lines += "| $rate | $name | ${duration(median(va))} | ${duration(va.min())} to " +
                "${duration(va.max())} | ${duration(median(vb))} | ${duration(vb.min())} to ${duration(vb.max())} | " +
                "${"%+.1f".format(Locale.ROOT, change)}% | $outside |"
        }
    }
    return lines + ""
}

private const val PERCENT = 100.0

/** A classpath to run a server from, and the Jetty pool and connector settings `start()` uses there (spec 0085). */
private class Side(val name: String, val classpath: String, val pool: String, val connector: String = "") {
    override fun toString() = "$name, $pool pool, connector ${connector.ifEmpty { "defaults" }}"
}

/** What `:benchmarks:wire` passes: the output directory, then any of the flags `main` reads. */
private class Options(private val args: Array<String>) {
    private fun value(flag: String): String? = args.indexOf(flag).takeIf { it >= 0 }?.let { args[it + 1] }

    val outDir = File(args.first())
    val smoke = "--smoke" in args
    val scenarios = when (val s = value("--scenario") ?: "rest") {
        "both" -> Scenario.entries
        else -> listOf(Scenario.valueOf(s.uppercase()))
    }
    private val own = System.getProperty("java.class.path")
    private val pool = value("--pool") ?: "queued"
    private val abPool = "--ab-pool" in args
    private val connector = value("--connector").orEmpty()
    private val abConnector = value("--ab-connector")

    /** A: this checkout, on `VirtualThreadPool` with `--ab-pool` or with `--ab-connector`'s settings. */
    val mine = Side("this checkout", own, if (abPool) "virtual" else pool, abConnector ?: connector)

    /** B, if any: this checkout on its defaults with `--ab-pool` or `--ab-connector`, or `--baseline`'s build. */
    val baseline: Side? = when {
        abPool -> Side("this checkout", own, "queued", connector)
        abConnector != null -> Side("this checkout", own, pool)
        else -> value("--baseline")?.let { Side("the baseline", File(it).readText().trim(), pool, connector) }
    }
    val rounds = if (smoke) 1 else value("--rounds")?.toInt() ?: DEFAULT_ROUNDS
    val pekko = "--pekko" in args

    private companion object {
        const val DEFAULT_ROUNDS = 3
    }
}

/**
 * `./gradlew :benchmarks:wire`: each server over loopback, in turn, at each of the scenario's rates until it stops
 * keeping up, written to `wire-result.md`. With `--baseline` (a classpath file, from `-PwireAb`), Jetty (and Pekko
 * with `--pekko`) is run from this checkout as A and from the baseline as B, alternating for `--rounds` rounds, and
 * the comparison is written to `wire-ab.md`.
 */
fun main(args: Array<String>) {
    val options = Options(args)
    val schedule = if (options.smoke) SMOKE else FULL
    val lines = mutableListOf(
        "JDK ${Runtime.version()}, ${System.getProperty("os.name")} ${System.getProperty("os.version")}, " +
            "${Runtime.getRuntime().availableProcessors()} cores",
        "",
    )
    val notes = mutableListOf<String>()
    val baseline = options.baseline
    val mine = options.mine
    baseline?.let { lines += listOf("A: $mine. B: $it.", "") }
    for (scenario in options.scenarios) {
        if (baseline == null) {
            lines += header(scenario)
            for (server in listOf("pekko", "jetty")) {
                lines += ladder(server, server, mine, scenario, schedule, notes).map { it.row() }
            }
            lines += ""
        } else {
            val servers = if (options.pekko) listOf("jetty", "pekko") else listOf("jetty")
            for (server in servers) {
                val a = mutableListOf<List<Step>>()
                val b = mutableListOf<List<Step>>()
                repeat(options.rounds) { round ->
                    a += ladder(server, "$server A${round + 1}", mine, scenario, schedule, notes)
                    b += ladder(server, "$server B${round + 1}", baseline, scenario, schedule, notes)
                }
                lines += comparison(server, scenario, a, b)
                lines += header(scenario)
                lines += (a + b).flatten().map { it.row() }
                lines += ""
            }
        }
    }
    val report = (lines + notes).joinToString("\n")
    println(report)
    val name = if (baseline == null) "wire-result.md" else "wire-ab.md"
    File(options.outDir.apply { mkdirs() }, name).writeText(report + "\n")
}
