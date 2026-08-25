// Meters for a Pelican service, with their dimensions taken from the endpoint
// descriptions rather than from strings a caller remembered to pass.
//
// Two dependencies and no more: `pelican-core` for the descriptions, and
// Micrometer for the registry to put the meters in. No server library — this
// module reads a description and a `Filter`, and neither knows which
// interpreter is serving it, which is what lets one `metrics(registry)` line
// mean the same thing on Pekko, http4k and Ktor. `NoOtherDependenciesTest`
// is that claim stated as a test.
//
// Micrometer arrives as `api` rather than `implementation` because a
// `MeterRegistry` is a parameter of this module's only public function: a
// consumer cannot call it without the type, and would have to declare the
// dependency a second time to say so.
dependencies {
    api(project(":pelican-core"))
    // The tests meter into a SimpleMeterRegistry, which holds its meters in
    // memory and ships in this same artifact — so asserting on what was
    // recorded needs no monitoring backend and no network.
    api("io.micrometer:micrometer-core:1.17.1")
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.metrics.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
