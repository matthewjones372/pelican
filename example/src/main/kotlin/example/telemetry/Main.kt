package example.telemetry

import io.github.matthewjones372.pelican.pekko.start
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/**
 * A registry that publishes percentiles.
 *
 * `pelican-metrics` deliberately takes no parameter for this: percentiles, SLO
 * boundaries, common tags and dropping a meter altogether are all `MeterFilter`s
 * on the registry, and Micrometer answers them better than a parameter there
 * could. So the p99 in the report is configured here, where a real service
 * configures it too.
 */
fun percentileRegistry(): SimpleMeterRegistry = SimpleMeterRegistry().apply {
    config().meterFilter(
        object : MeterFilter {
            override fun configure(id: Meter.Id, config: DistributionStatisticConfig) =
                DistributionStatisticConfig.builder()
                    .percentiles(MEDIAN, NEAR_WORST)
                    .build()
                    .merge(config)
        },
    )
}

/** The two the report prints; a real service picks its own. */
private const val MEDIAN = 0.5
private const val NEAR_WORST = 0.99

fun main() {
    val registry = percentileRegistry()
    val recorded = RecordedSpans()
    val telemetry = recordingTelemetry(recorded)

    val server = telemetryService(registry, telemetry) { report(registry, recorded) }
        .start(port = 8080, systemName = "telemetry-orders")

    println("Listening on ${server.baseUrl}")
    println("  curl ${server.baseUrl}/orders/1                     # a 200")
    println("  curl ${server.baseUrl}/orders/99                    # the declared 404, not an error")
    println("  curl '${server.baseUrl}/orders?q=kettle'            # slow, and variably so")
    println("  curl ${server.baseUrl}/orders/1/receipt             # throws: an error in both")
    println("  curl ${server.baseUrl}/v1/orders                    # deprecated, still served")
    println("  curl ${server.baseUrl}/admin/report                 # what the two recorded")
}
