// Live and ready probes for a Pelican service, built from ordinary endpoints so
// one `health { }` means the same on every backend and is in the document.
//
// `pelican-core` and nothing else: the checks that ship here need only the JDK,
// and a check over a third-party client is a leaf module of its own (spec
// 0069). `NoOtherDependenciesTest` is that claim stated as a test.
dependencies {
    api(project(":pelican-core"))

    // The body is written by the service's own codecs; the tests use Jackson's,
    // and call the probes in memory through the typed test client.
    testImplementation(project(":pelican-jackson"))
    testImplementation(project(":pelican-test"))
    // An in-memory database with a pool that can be closed under the check.
    testImplementation("com.h2database:h2:2.3.232")
    // A downstream service that answers what each test needs, and then is not there.
    testImplementation("org.wiremock:wiremock-standalone:3.13.1")
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.health.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
