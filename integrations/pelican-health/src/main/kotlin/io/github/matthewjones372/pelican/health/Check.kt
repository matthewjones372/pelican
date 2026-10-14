package io.github.matthewjones372.pelican.health

/** One dependency's health, asked for each time a probe is. Any lambda is one. */
fun interface Check {
    fun check(): Status
}

/** What a [Check] found: the IETF health check draft's `pass`, `warn` and `fail`. */
sealed interface Status {
    data object Pass : Status

    /** Working, but worth a look; the probe still answers 200. */
    data class Warn(val output: String) : Status

    data class Fail(val output: String) : Status
}

/**
 * The `application/health+json` body (draft-inadarei-api-health-check). Keys
 * in [checks] are `name:responseTime`, each holding the one measurement taken.
 */
data class HealthReport(val status: String, val checks: Map<String, List<CheckResult>>)

/** One check's answer. [output] is only present when the probes were built with `detail = true`. */
data class CheckResult(
    val status: String,
    val observedValue: Long,
    val observedUnit: String,
    val time: String,
    val componentType: String? = null,
    val output: String? = null,
)
