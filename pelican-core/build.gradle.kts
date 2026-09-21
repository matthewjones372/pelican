// Deliberately minimal. No server framework, no HTTP library, no OpenAPI, and
// no JSON *databind* — descriptions carry a KType and never a serializer, which
// is what keeps the codec pluggable.
//
// One exception, and it is a judgement call made once: `jackson-core`, the
// streaming parser under databind. Core has to read JSON as well as write it
// — a generated client parses the document embedded in it, and the golden and
// compatibility tools read documents this project did not produce — and a
// hand-written reader on those paths was a parser nobody chose to own. See
// spec 0045. It has no transitive dependencies of its own, and every service
// using `pelican-jackson` already has it, so the shipped configuration gains
// no jar. `NoThirdPartyDependenciesTest` permits exactly this one artifact and
// still asserts databind, kotlinx and Pekko are absent.
//
// If this file ever grows a `pekko` dependency, the layering has been broken.

dependencies {
    // Pinned to match `pelican-jackson`, so a service running both resolves one
    // version; an application's own BOM still wins.
    api("com.fasterxml.jackson.core:jackson-core:2.22.2")
}

/**
 * The modules `FunctionalStyleTest` reads, in this file rather than in the test
 * because Gradle is the one that has to know them.
 */
val styledModules = listOf(
    "pelican-core", "pelican-openapi", "pelican-schema", "pelican-mcp", "pelican-mcp-server",
    "pelican-codegen",
    "pelican-jackson",
    "pelican-arrow",
    "pelican-pekko",
    "pelican-metrics",
    "pelican-metrics-otel",
    "pelican-client-pekko",
    "pelican-test", "pelican-test-pekko",
)

/**
 * One list, read twice: Gradle snapshots these directories to decide whether
 * the test's answer still holds, and the test walks these same directories to
 * reach it. Letting the test find the sources for itself — it resolved the
 * repository root from the working directory — is how the two came apart.
 */
val styledSources = styledModules.map { rootDir.resolve("$it/src/main/kotlin") }

/**
 * A gate in this module's tests that judges fourteen modules is a wart, kept because
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
