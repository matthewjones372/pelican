package io.github.matthewjones372.pelican.metrics.otel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test.
 *
 * It is the mirror image of the one in `pelican-metrics`, and the pair of them
 * is the argument for two modules rather than one. A service that asks for
 * OpenTelemetry gets the descriptions it already had plus the OpenTelemetry
 * API; a service that asks for Micrometer gets the descriptions plus a meter
 * API. Neither acquires the other vendor's API by asking for its own, which is
 * a claim that cannot survive being left to habit — a single module carrying
 * both would break it the first time somebody imported across the two files.
 *
 * The rest is the same claim every module here makes: no server library, which
 * is what lets the one `openTelemetry(sdk)` line mean the same thing on all
 * every interpreter, and no OpenAPI generator, no JSON library, nothing that
 * would arrive uninvited.
 */
class NoOtherDependenciesTest {

    /**
     * The OpenTelemetry API's own runtime, and the Kotlin plugin's.
     * `opentelemetry-context` carries `Context` and the propagator interfaces,
     * and `opentelemetry-common` carries `Attributes` and `AttributeKey`; both
     * are what `opentelemetry-api` is assembled from. Anything outside this
     * list is a dependency that crept in — the SDK in particular, which the
     * tests use and a consumer chooses for itself.
     */
    private val allowed = listOf(
        "kotlin-stdlib", "annotations-", "pelican-core",
        "opentelemetry-api", "opentelemetry-context", "opentelemetry-common",
    )

    @Test
    fun `the main runtime classpath is core, the OpenTelemetry API, and nothing else`() {
        val raw = System.getProperty("pelican.metrics.otel.runtimeClasspath")
        withClue("the build must pass -Dpelican.metrics.otel.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-metrics-otel must stay core plus the OpenTelemetry API, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `neither a server library nor the other vendor's meters are reachable`() {
        listOf(
            "org.apache.pekko.http.javadsl.server.Directives",
            "org.http4k.core.Request",
            "io.ktor.server.application.Application",
            "io.github.matthewjones372.pelican.openapi.OpenApiKt",
            // The point of the split: asking for OpenTelemetry must not deliver
            // Micrometer, exactly as `pelican-metrics` asserts the converse.
            "io.micrometer.core.instrument.MeterRegistry",
            "io.github.matthewjones372.pelican.metrics.MetricsKt",
        ).forEach { name ->
            withClue("$name is on pelican-metrics-otel's classpath; a dependency crept in") {
                shouldThrow<ClassNotFoundException> { Class.forName(name) }
            }
        }
    }
}
