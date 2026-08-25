// OpenTelemetry spans and the specified request-duration histogram for a
// Pelican service, with their names and attributes taken from the endpoint
// descriptions rather than from strings a caller remembered to pass.
//
// A module of its own rather than a second function inside `pelican-metrics`,
// and the reason is on both consumers' classpaths. `pelican-metrics` promises
// that a service asking for meters gets core plus a meter API and nothing
// else; a service asking for OpenTelemetry should get core plus the
// OpenTelemetry API and nothing else, and Micrometer is not "nothing else".
// One module carrying both would either put each vendor's API in front of the
// audience that did not ask for it, or make both `compileOnly` — which would
// take Micrometer off the classpath of every service already calling
// `metrics(registry)` and turn a working deployment into a
// NoClassDefFoundError. Two modules, two `NoOtherDependenciesTest`s, and
// neither audience pays for the other.
//
// It does not depend on `pelican-metrics` either, for the same reason. The two
// read the same descriptions through the same `Filter` and share no code that
// would be worth dragging one vendor's API in behind the other's.
//
// `opentelemetry-api` arrives as `api` rather than `implementation` because an
// `OpenTelemetry` is a parameter of this module's only public function: a
// consumer cannot call it without the type, and would have to declare the
// dependency a second time to say so.
dependencies {
    api(project(":pelican-core"))
    api("io.opentelemetry:opentelemetry-api:1.65.0")

    // The SDK, with its in-memory span exporter and metric reader, is how the
    // tests read back what was emitted without a collector and without a
    // network. Test-scoped on purpose: what this module puts on a consumer's
    // classpath is the API, and which SDK — if any — is wired underneath is
    // the service's own decision.
    testImplementation("io.opentelemetry:opentelemetry-sdk:1.65.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.65.0")
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.metrics.otel.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
