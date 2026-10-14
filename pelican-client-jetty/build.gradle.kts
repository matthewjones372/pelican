val jettyVersion = "12.1.13"

// Core's `ClientTransport` over Jetty's client, the same release `pelican-jetty`
// serves with, so a service runs one HTTP stack in both directions.
dependencies {
    api(project(":pelican-core"))
    api("org.eclipse.jetty:jetty-client:$jettyVersion")
}

tasks.test {
    // The main runtime classpath, so DependenciesTest can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.client.jetty.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
