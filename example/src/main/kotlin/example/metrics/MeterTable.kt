package example.metrics

import io.micrometer.core.instrument.MeterRegistry

/**
 * Everything the registry holds, one meter to a line, in a shape close enough
 * to Prometheus' exposition format to be recognisable and simple enough to read
 * in a terminal:
 *
 * ```
 * http.server.requests{deprecated=false,method=GET,operation=fetchOrder,path=/orders/{orderId},status=200} COUNT=2.0
 * ```
 *
 * A service that is actually scraped uses `micrometer-registry-prometheus` and
 * exports the registry properly. This is here so the example can show what came
 * out without adding a second dependency to make the point.
 */
fun meterTable(registry: MeterRegistry): String =
    registry.meters
        .map { meter ->
            val tags = meter.id.tags.joinToString(",") { "${it.key}=${it.value}" }
            val values = meter.measure().joinToString(" ") { "${it.statistic}=${it.value}" }
            "${meter.id.name}{$tags} $values"
        }
        .sorted()
        .joinToString("\n")
