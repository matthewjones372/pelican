// WireMock, stubbed and verified in endpoint values rather than URLs and JSON.
// See spec 0056.
//
// Everything a test names — WireMock's faults, Pelican's outcomes — is `api`.
// JUnit is `compileOnly`: `PelicanWireMock` never touches it, and only
// `PelicanWireMockExtension` does, for a build that already has JUnit.
dependencies {
    api(project(":pelican-core"))
    // Shaded Jetty and Jackson, so the versions under test are the service's own.
    api("org.wiremock:wiremock-standalone:3.13.1")
    compileOnly("org.junit.jupiter:junit-jupiter-api:6.1.3")

    testImplementation(project(":pelican-jackson"))
    testImplementation(project(":pelican-test"))
    testImplementation("org.junit.platform:junit-platform-testkit:6.1.3")
}

tasks.test {
    // The main runtime classpath, so DecouplingTest asserts on what is shipped
    // rather than on what the test JVM happens to load.
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
