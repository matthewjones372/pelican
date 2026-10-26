val surefoldVersion = "0.12.0"

// `pelican-streams` on Jetty: the same seam over surefold core's `Stream.blocking`, so no surefold backend artifact and
// no Pekko. `surefold-stream` is provided as in `pelican-streams`: the consumer names it beside the backend it runs on.
dependencies {
    api(project(":pelican-jetty"))

    compileOnly("io.github.matthewjones372:surefold-stream:$surefoldVersion")

    testImplementation("io.github.matthewjones372:surefold-stream:$surefoldVersion")
    testImplementation("io.github.matthewjones372:surefold-stream-forks:$surefoldVersion")
    testImplementation(project(":pelican-test-jetty"))
    testImplementation(project(":pelican-jackson"))
}

tasks.test {
    // The main runtime classpath, so the dependency test asserts on what is shipped, not on the test JVM's.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.streams.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
