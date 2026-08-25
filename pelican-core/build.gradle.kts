// Deliberately minimal. No server framework, no HTTP library, no OpenAPI — and
// no JSON library either. Descriptions carry a KType, and `JsonValue` is enough
// to represent a schema, so core has nothing on its runtime classpath but the
// Kotlin standard library. `NoThirdPartyDependenciesTest` enforces that.
//
// If this file ever grows a `pekko` dependency, the layering has been broken.

/**
 * The modules `FunctionalStyleTest` reads, in this file rather than in the test
 * because Gradle is the one that has to know them.
 */
val styledModules = listOf(
    "pelican-core", "pelican-openapi", "pelican-codegen", "pelican-jackson", "pelican-kotlinx",
    "pelican-pekko", "pelican-http4k", "pelican-ktor",
    "pelican-metrics",
    "pelican-metrics-otel",
    "pelican-client-java",
    "pelican-test", "pelican-test-pekko", "pelican-test-http4k",
)

/**
 * One list, read twice: Gradle snapshots these directories to decide whether
 * the test's answer still holds, and the test walks these same directories to
 * reach it. Letting the test find the sources for itself — it resolved the
 * repository root from the working directory — is how the two came apart.
 */
val styledSources = styledModules.map { rootDir.resolve("$it/src/main/kotlin") }

/**
 * A gate in this module's tests that judges ten others is a wart, kept because
 * the regex and its exemptions are compiled with the code they describe, run
 * from an IDE, and report through the test report. Nothing orders it:
 * `src/main/kotlin` is checked in rather than generated, so there is no
 * producing task to wait on and the text is what gets read.
 */
tasks.test {
    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit; only the main one is meant to be bare.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")

    inputs.files(styledSources)
        .withPropertyName("functionalStyleSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The module names as well as their contents. Relative paths are the same
    // whichever directory they hang under, so renaming a module would leave the
    // snapshot identical while changing every key the test compares against.
    inputs.property("functionalStyleModules", styledModules)

    val repoRootPath = rootDir.path
    val styledSourcePaths = styledSources.joinToString(File.pathSeparator) { it.path }

    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            // Absolute paths, and deliberately not part of the input snapshot:
            // where the checkout sits must not decide whether a cached result
            // applies to it. The `inputs` above are what the answer depends on.
            listOf(
                "-Dpelican.core.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dpelican.style.repoRoot=$repoRootPath",
                "-Dpelican.style.sources=$styledSourcePaths",
            )
        },
    )
}
