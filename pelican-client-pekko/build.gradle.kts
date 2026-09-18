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

    // Provided, for the reason `pelican-pekko/build.gradle.kts` gives: the
    // Scala suffix belongs to the application, not to us.
    compileOnly(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    compileOnly("org.apache.pekko:pekko-actor-typed_$scalaBinary")
    compileOnly("org.apache.pekko:pekko-stream_$scalaBinary")
    compileOnly("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")

    // The tests send at the JDK's own `HttpServer`, which proves more about
    // what goes on the wire than Pekko's testkit talking to the server half of
    // the same library would. They still need a Pekko to run against.
    testImplementation(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    testImplementation("org.apache.pekko:pekko-actor-typed_$scalaBinary")
    testImplementation("org.apache.pekko:pekko-stream_$scalaBinary")
    testImplementation("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")
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
