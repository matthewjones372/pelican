// Deliberately minimal. No server framework, no HTTP library, no OpenAPI — and
// no JSON library either. Descriptions carry a KType, and `JsonValue` is enough
// to represent a schema, so core has nothing on its runtime classpath but the
// Kotlin standard library. `NoThirdPartyDependenciesTest` enforces that.
//
// If this file ever grows a `pekko` dependency, the layering has been broken.

/**
 * The modules `FunctionalStyleTest` reads, in this file rather than in the test
 * because Gradle is the one that has to know them.
 *
 * The test walks these directories at run time and judges what it finds there.
 * A task that reads a file it never declared is up-to-date whenever that file
 * is the only thing to have changed, so the gate reported green over a real
 * violation for several builds running and only spoke up under `--rerun-tasks`.
 * A gate that answers from a stale reading is worse than no gate, because
 * people believe it.
 *
 * The list is the library modules. The example is a service and holds its store
 * in a map on purpose, and `pelican-import` is not covered yet — adding it
 * means writing down why each of its six accumulators is a builder, which is a
 * judgement about that module rather than a fix to this wiring.
 */
val styledModules = listOf(
    "pelican-core", "pelican-openapi", "pelican-codegen", "pelican-jackson", "pelican-kotlinx",
    "pelican-pekko", "pelican-http4k", "pelican-ktor",
    "pelican-test", "pelican-test-pekko", "pelican-test-http4k",
)

/**
 * One list, read twice: Gradle snapshots these directories to decide whether
 * the test's answer still holds, and the test walks these same directories to
 * reach it. Letting the test find the sources for itself — it resolved the
 * repository root from the working directory — is how the two came apart.
 *
 * `src/main/kotlin` and not the whole of `src/main`:
 * `pelican-codegen/src/main/resources` holds `.kt` files that are templates the
 * generator reads at run time. They are text, not this build's source, and
 * Spotless skips them for the same reason.
 */
val styledSources = styledModules.map { rootDir.resolve("$it/src/main/kotlin") }

/**
 * That the gate lives in this module's tests and judges ten others is a wart,
 * and the alternative was weighed: a verification task on the root project,
 * which is where a claim about the whole repository belongs. It would have
 * moved the regex and the sixteen exemptions — each carrying a paragraph on why
 * that file is a builder — out of a test and into a build script, where they
 * are not compiled with the code they describe, cannot be run from an IDE, and
 * report through Gradle rather than through the test report. The claim reads as
 * a test, so it stays one. Nothing orders it: `src/main/kotlin` is checked in,
 * not generated, so there is no producing task for this test to wait on, and it
 * reads the text of those modules rather than their output.
 */
tasks.test {
    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit; only the main one is meant to be bare.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")

    // RELATIVE rather than NAME_ONLY: the verdict is keyed by each file's path
    // from the repository root, because that is how the exemptions are written,
    // so moving a file is a change of answer. Nothing looser is available
    // either — the gate is a regex over raw text and reads a comment exactly as
    // it reads code, so a comment-only edit can genuinely change what it says.
    inputs.files(styledSources)
        .withPropertyName("functionalStyleSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The module names as well as their contents. Relative paths are the same
    // whichever directory they hang under, so renaming a module would leave the
    // snapshot identical while changing every key the test compares against.
    inputs.property("functionalStyleModules", styledModules)

    // Resolved to plain strings here, not read out of the script from inside
    // the provider below: a lambda that reaches back for a script-level value
    // captures the script object with it, which the configuration cache cannot
    // serialize. Nothing this build runs is cached that way yet, and adding the
    // first reason it could not be is not worth two saved lines.
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
