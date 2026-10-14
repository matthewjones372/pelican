// Test support: a client that is type-checked against the same endpoint
// descriptions the server is interpreted from.
//
// Backend-agnostic, and enforced as such by `DecouplingTest`. `ApiClient`,
// `RequestSpec`/`ResponseSpec`, the assertions and `HttpClientTransport` know
// only `pelican-core` and the JDK, so a service built on another backend can
// take this module without Pekko arriving with it.
//
// The in-memory transports are per-backend and live next door, one module
// each: `pelican-test-pekko` is the one main ships.
dependencies {
    api(project(":pelican-core"))

    // For the List<T> KType a streamed JSON array decodes as.
    implementation(kotlin("reflect"))

    testImplementation(project(":pelican-jackson"))
}

tasks.test {
    // The main runtime classpath, so DecouplingTest can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.test.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
