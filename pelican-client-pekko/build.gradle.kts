val pekkoVersion = "1.7.0"
val pekkoHttpVersion = "1.4.0"
val scalaBinary = "2.13"

// The caller-side counterpart of `pelican-pekko`: core's `ClientTransport` over
// Pekko HTTP's client. Core plus Pekko and nothing else, which is what
// `DependenciesTest` asserts.
//
// It does not depend on `pelican-pekko`. Sending a request and interpreting an
// endpoint description into a route are separate jobs, and a caller that only
// makes calls should not compile the interpreter in.
dependencies {
    api(project(":pelican-core"))

    api(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    api("org.apache.pekko:pekko-actor-typed_$scalaBinary")
    api("org.apache.pekko:pekko-stream_$scalaBinary")
    api("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")

    // Nothing extra for the tests. They send at the JDK's own `HttpServer`,
    // which proves more about what goes on the wire than Pekko's testkit
    // talking to the server half of the same library would.
}

tasks.test {
    // The main runtime classpath, so DependenciesTest can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.client.pekko.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
