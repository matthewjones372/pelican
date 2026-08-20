plugins { application }

dependencies {
    implementation(project(":pelican-core"))
    implementation(project(":pelican-openapi"))
    implementation(project(":pelican-codegen"))
    implementation(project(":pelican-pekko"))
    // Serving the docs is opt-in: this is the module that adds them to a server.
    implementation(project(":pelican-pekko-docs"))
    // The second and third backends, wired to the same endpoint descriptions.
    implementation(project(":pelican-http4k"))
    implementation(project(":pelican-http4k-docs"))
    implementation(project(":pelican-ktor"))
    implementation(project(":pelican-ktor-docs"))
    implementation(project(":pelican-jackson"))
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")

    // A JSON parser for the assertions only. The tests read responses off a
    // socket, and something has to turn them back into a tree; this is not the
    // example's codec, which is Jackson. No serialization compiler plugin is
    // involved — `parseToJsonElement` needs none.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // The OpenAPI documents this repository emits are checked by a parser that
    // is not the one that wrote them — swagger-parser reads the document back
    // and reports what is wrong with it. A generator marking its own homework
    // is worth very little; see OpenApiSpecQualityTest.
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.47")

    testImplementation(project(":pelican-test"))
    // The in-memory transports are per-backend, so the suites that run twice
    // ask for the one they need. `pelican-test` itself stays backend-agnostic.
    testImplementation(project(":pelican-test-pekko"))
    testImplementation(project(":pelican-test-http4k"))

    // Matchers, declared here rather than arriving through pelican-test. The
    // library ships assertions that throw AssertionError; which matcher
    // library a suite uses on top is the suite's own choice.
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
}

application { mainClass.set("example.MainKt") }

/** The second example, so both can be run: `./gradlew :example:runBookmarks --args=8081`. */
val runBookmarks by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs the Bookmarks example"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.bookmarks.BookmarksKt")
}

/** The security example: basic auth, an external identity provider, and a docs page that can sign in. */
val runSecured by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs the secured Reports example"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.secured.SecuredReportsKt")
}

/** The same Orders service, served by http4k instead of Pekko. */
val runHttp4k by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs the Orders example on the http4k backend"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.http4k.Http4kOrdersKt")
}

/** The same two endpoints, served by all three backends at once, for comparing them. */
val runBackends by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs the greetings example on Pekko, http4k and Ktor side by side"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.backends.MainKt")
}

/** The README's example, kept runnable so the front page cannot drift. */
val runReadmeExample by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs the example from the README"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.readme.ReadmeExampleKt")
}

/**
 * Generates the OpenAPI document straight from the endpoint descriptions.
 * No server is started and no request is made — it only needs pelican-core,
 * pelican-openapi and a schema source.
 */
val generateOpenApi by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Writes build/openapi.json from the endpoint descriptions"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.GenerateOpenApiKt")
    args(layout.buildDirectory.file("openapi.json").get().asFile.absolutePath)
    outputs.file(layout.buildDirectory.file("openapi.json"))
}

/**
 * Generates the Kotlin client straight from the endpoint descriptions — the
 * same values, the same schemas, and still no server.
 *
 * It writes into this module's *test* sources, so the generated client is
 * compiled and run against a real server by `GeneratedKotlinClientTest`. The
 * generator lays out the package directories itself; regenerating is this task
 * and nothing else.
 */
val generateKotlinClient by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Regenerates example.generated.OrdersClient from the endpoint descriptions"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.GenerateKotlinClientKt")
    args(layout.projectDirectory.dir("src/test/kotlin").asFile.absolutePath)
}

// The benchmark is a test that takes ten seconds and asserts nothing, so it is
// off unless asked for: `./gradlew :example:test -Dbenchmark=true --tests "*OverheadBenchmark*"`.
val benchmarking = providers.systemProperty("benchmark").getOrElse("false") == "true"

tasks.withType<Test>().configureEach {
    systemProperty("benchmark", if (benchmarking) "true" else "false")

    // `-Dprofile=true` alongside it records a flight recording of the run, so
    // "where does the overhead go" is answered by the JVM rather than guessed.
    if (providers.systemProperty("profile").getOrElse("false") == "true") {
        val recording = layout.buildDirectory.file("benchmark.jfr").get().asFile
        jvmArgs(
            "-XX:StartFlightRecording=settings=profile,filename=$recording,dumponexit=true",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints",
        )
    }
}

// Coverage instrumentation rewrites bytecode, and it rewrites more of Pelican
// than of a hand-written route — which is exactly the comparison the benchmark
// makes. Measuring through it would report the agent, not the library.
if (benchmarking) {
    kover { currentProject { instrumentation { disabledForAll.set(true) } } }
}
