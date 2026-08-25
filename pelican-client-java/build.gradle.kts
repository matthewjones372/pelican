// The default transport for a generated client: core's `ClientTransport` over
// the JDK's own `HttpClient`.
//
// pelican-core and the JDK, and nothing else — which is what `DependenciesTest`
// asserts. It is the caller-side counterpart of a backend module: one small
// adapter per HTTP stack, with core neutral between them. The Ktor and Pekko
// adapters are the same shape and live next door once they are written.
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
