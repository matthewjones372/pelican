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

private val SERVERS = listOf("pekko", "jetty")

private const val WARMUP_RATE = 5_000

/** How long each phase runs, in seconds; [SMOKE] only checks that both servers start and answer. */
private data class Schedule(val warmup: Int, val settle: Int, val measure: Int, val rates: List<Int>)

private val FULL = Schedule(warmup = 30, settle = 5, measure = 15, rates = RATES)
private val SMOKE = Schedule(warmup = 1, settle = 1, measure = 2, rates = RATES.take(1))
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
private class Child(name: String) : AutoCloseable {
    private val process: Process = ProcessBuilder(
        ProcessHandle.current().info().command().orElseThrow(),
        "-Xms1g",
        "-Xmx1g",
        "-cp",
        System.getProperty("java.class.path"),
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
private class Driver(port: Int) : AutoCloseable {
    private val client = HttpClient().apply {
        maxConnectionsPerDestination = IN_FLIGHT
        connectTimeout = REQUEST_TIMEOUT.toMillis()
        start()
    }

    private val getUri = URI("http://127.0.0.1:$port/items/7?limit=25")
    private val postUri = URI("http://127.0.0.1:$port/items")

    private fun send(get: Boolean, done: (Int?, String?) -> Unit) {
        val request = if (get) {
            client.newRequest(getUri)
        } else {
            client.newRequest(postUri).method(HttpMethod.POST).body(StringRequestContent("application/json", BODY))
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
        check(answers.take() == (200 to """{"id":7,"name":"item-25"}""")) { "GET answered wrongly" }
        send(false) { s, b -> answers.put(s to b) }
        check(answers.take() == (201 to """{"id":1,"name":"anvil"}""")) { "POST answered wrongly" }
    }

    override fun close() = client.stop()

    /** [rate] requests a second, alternately GET and POST, for [settle] seconds unrecorded and [measure] recorded. */
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

/** Warms [server] up, then climbs the rates until it stops keeping up; returns its load averages. */
private fun ladder(server: String, schedule: Schedule, row: (String) -> Unit): String = Child(server).use { child ->
    Driver(child.port).use { driver ->
        driver.verify()
        val before = loadAverage()
        println("$server: warming up for ${schedule.warmup}s at $WARMUP_RATE req/s, load $before")
        driver.run(server, WARMUP_RATE, 0, schedule.warmup)
        for (rate in schedule.rates) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PAUSE_MILLIS))
            val step = driver.run(server, rate, schedule.settle, schedule.measure)
            println(step.row())
            row(step.row())
            if (!step.keptUp) break
        }
        "$server: load average $before before warmup, ${loadAverage()} after its last rate"
    }
}

/**
 * `./gradlew :benchmarks:wire`: Pelican on Pekko and Pelican on Jetty over loopback, each in turn, at each of
 * [RATES] until it stops keeping up. Prints a Markdown table and writes it to the path in the first argument;
 * `--smoke` as the second runs one short step per server instead.
 */
fun main(args: Array<String>) {
    val lines = mutableListOf(
        "JDK ${Runtime.version()}, ${System.getProperty("os.name")} ${System.getProperty("os.version")}, " +
            "${Runtime.getRuntime().availableProcessors()} cores",
        "",
        "| server | req/s | p50 | p99 | p99.9 | max | requests | errors | kept up |",
        "|---|---|---|---|---|---|---|---|---|",
    )
    val schedule = if (args.getOrNull(1) == "--smoke") SMOKE else FULL
    val notes = SERVERS.map { server -> ladder(server, schedule) { lines += it } }
    val report = (lines + "" + notes).joinToString("\n")
    println(report)
    args.firstOrNull()?.let { File(it).apply { parentFile.mkdirs() }.writeText(report + "\n") }
}
