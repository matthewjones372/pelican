package io.github.matthewjones372.pelican.metrics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test.
 *
 * The claim is the same one every other module here makes, and it is the reason
 * the meters live in a module of their own rather than in a backend: a service
 * asks for metrics, and what it gets is the descriptions it already had plus a
 * meter API. No server library — which is what lets the same one line mean the
 * same thing on every interpreter — and no OpenAPI generator, no JSON
 * library, and nothing that would arrive uninvited.
 *
 * `pelican-metrics-otel` is the other half of that, and the reason it is a
 * module rather than a second file here: a service that wanted Micrometer must
 * not acquire the OpenTelemetry API for it, and the test below says so in the
 * same breath as the rest.
 */
class NoOtherDependenciesTest {

    /**
     * Micrometer's own runtime, and the Kotlin plugin's. `micrometer-commons`
     * and `micrometer-observation` are what `micrometer-core` is assembled
     * from; `HdrHistogram` is the histogram behind a `Timer`'s percentiles, and
     * `jspecify` is its nullability annotations. Anything outside this list is
     * a dependency that crept in.
     */
    private val allowed = listOf(
        "kotlin-stdlib", "annotations-", "pelican-core",
        "micrometer-core", "micrometer-commons", "micrometer-observation", "HdrHistogram", "jspecify",
    )

    @Test
    fun `the main runtime classpath is core, a meter API, and nothing else`() {
        val raw = System.getProperty("pelican.metrics.runtimeClasspath")
        withClue("the build must pass -Dpelican.metrics.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-metrics must stay core plus a meter API, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `no server library and no second telemetry API is reachable from the meters`() {
        listOf(
            "org.apache.pekko.http.javadsl.server.Directives",
            "org.http4k.core.Request",
            "io.ktor.server.application.Application",
            "io.github.matthewjones372.pelican.openapi.OpenApiKt",
            // A service that asked for Micrometer did not ask for the other
            // vendor's API. `pelican-metrics-otel` asserts the converse.
            "io.opentelemetry.api.OpenTelemetry",
        ).forEach { name ->
            withClue("$name is on pelican-metrics' classpath; a dependency crept in") {
                shouldThrow<ClassNotFoundException> { Class.forName(name) }
            }
        }
    }
}
