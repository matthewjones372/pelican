// Deliberately minimal. No server framework, no HTTP library, no OpenAPI — and
// no JSON library either. Descriptions carry a KType, and `JsonValue` is enough
// to represent a schema, so core has nothing on its runtime classpath but the
// Kotlin standard library. `NoThirdPartyDependenciesTest` enforces that.
//
// If this file ever grows a `pekko` dependency, the layering has been broken.

tasks.test {
    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit; only the main one is meant to be bare.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.core.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
