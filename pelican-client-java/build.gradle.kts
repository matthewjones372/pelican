// Core's `ClientTransport` over the JDK's own `HttpClient`. Core and the JDK,
// and nothing else, which is what `DependenciesTest` asserts.
//
// The package is `io.github.matthewjones372.pelican.client` rather than
// `...client.java`: inside a package whose last segment is `java`, Kotlin
// resolves a bare `java.` reference against the current package first.
dependencies {
    api(project(":pelican-core"))
}

tasks.test {
    // The main runtime classpath, so DependenciesTest can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.client.java.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
