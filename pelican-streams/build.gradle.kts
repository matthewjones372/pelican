val pekkoVersion = "1.7.1"
val pekkoHttpVersion = "1.4.0"
val scalaBinary = "2.13"
val larkVersion = "0.9.0"

// The seam between a streaming body and lark's `Stream`, and nothing else: one
// function, so that a service that never streams never sees the library and the
// library never learns what an endpoint is.
//
// Two lark artifacts, because the seam needs both halves: `lark-stream` carries
// the `Stream` type and its operators, and `lark-stream-pekko` carries the Pekko
// backend this module is written against — `Stream.from(Source)` and `run`. They
// share the `io.github.matthewjones372.lark.stream` package.
//
// Both sides are provided. `pelican-pekko` made Pekko `compileOnly` so the
// application names the Scala suffix it already runs (spec 0037, "The Pekko the
// app already runs"); `lark-stream-pekko` pins `_2.13` as an `api` dependency, so
// taking it normally here would republish that suffix through the back door and
// undo the same decision. A consumer of this module already declares Pekko, and
// declares the lark artifacts beside it.
dependencies {
    api(project(":pelican-pekko"))

    compileOnly("io.github.matthewjones372:lark-stream:$larkVersion")
    compileOnly("io.github.matthewjones372:lark-stream-pekko:$larkVersion")
    compileOnly(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    compileOnly("org.apache.pekko:pekko-stream_$scalaBinary")

    testImplementation("io.github.matthewjones372:lark-stream:$larkVersion")
    testImplementation("io.github.matthewjones372:lark-stream-pekko:$larkVersion")
    testImplementation(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    testImplementation("org.apache.pekko:pekko-actor-typed_$scalaBinary")
    testImplementation("org.apache.pekko:pekko-stream_$scalaBinary")
    testImplementation("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")
    testImplementation(project(":pelican-test-pekko"))
    testImplementation(project(":pelican-jackson"))
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
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
