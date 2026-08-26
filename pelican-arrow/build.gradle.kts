// Arrow's Either into Pelican's Outcome and back. Core plus arrow-core and
// nothing else, which is what NoOtherDependenciesTest asserts: an Arrow
// codebase adopts Pelican without translating its domain by hand, and a
// service not running Arrow never sees it.
dependencies {
    api(project(":pelican-core"))
    api("io.arrow-kt:arrow-core:2.1.2")
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.arrow.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
