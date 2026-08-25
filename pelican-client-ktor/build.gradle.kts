val ktorVersion = "3.5.2"

// The caller-side counterpart of `pelican-ktor`: core's `ClientTransport` over
// Ktor's `HttpClient`. Core plus Ktor and nothing else, which is what
// `DependenciesTest` asserts.
//
// It does not depend on `pelican-ktor`. Sending a request and interpreting an
// endpoint description into a route are separate jobs, and a caller that only
// makes calls should not compile the interpreter in.
//
// CIO is the engine, for the reason `pelican-ktor` ships `ktor-server-cio`: it
// is Kotlin and Ktor's own networking rather than a second HTTP stack wearing a
// Ktor interface, so a build that adds this module gains no OkHttp and no
// Apache client. A caller who has tuned an engine of their own hands the whole
// `HttpClient` over and this one is never built.
dependencies {
    api(project(":pelican-core"))

    api(platform("io.ktor:ktor-bom:$ktorVersion"))
    api("io.ktor:ktor-client-core")
    api("io.ktor:ktor-client-cio")

    // Nothing extra for the tests. They send at the JDK's own `HttpServer`,
    // which proves more about what goes on the wire than Ktor's own test host
    // answering the client half of the same library would.
}

tasks.test {
    // The main runtime classpath, so DependenciesTest can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.client.ktor.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
