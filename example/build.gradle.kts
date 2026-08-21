import dev.pelican.gradle.DocumentFormat

plugins {
    application
    // The build's own plugin, included from pluginManagement in settings.gradle.kts.
    // The example applies it by id exactly as a consumer would, which is what
    // keeps the plugin honest: if generation breaks, this build breaks.
    id("dev.pelican")
}

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
 * Both readings of the same descriptions, as build tasks: the OpenAPI document
 * and the Kotlin client. No server is started and no request is made — the
 * plugin loads `ordersSpec()` off this module's own runtime classpath and
 * generates from the values it returns.
 *
 * `./gradlew :example:generateOrdersDocument` writes build/openapi.json.
 * `./gradlew :example:generateOrdersClient` rewrites the checked-in client.
 *
 * The client is written into this module's *test* sources on purpose, so it is
 * compiled and run against a real server by `GeneratedKotlinClientTest`. That
 * is what turns on `checkOrdersClient`, which `check` depends on: a committed
 * client that no longer matches the descriptions fails the build.
 */
pelican {
    documents {
        create("orders") {
            specClass.set("example.GenerateOpenApiKt")
            specFunction.set("ordersSpec")
            outputFile.set(layout.buildDirectory.file("openapi.json"))
        }
        // The same document, written the other way. Two entries rather than a
        // format that flips, because a service that publishes both publishes
        // both — and it is what keeps the YAML rendering exercised.
        create("ordersYaml") {
            specClass.set("example.GenerateOpenApiKt")
            specFunction.set("ordersSpec")
            format.set(DocumentFormat.YAML)
            outputFile.set(layout.buildDirectory.file("openapi.yaml"))
        }
    }
    clients {
        create("orders") {
            specClass.set("example.GenerateOpenApiKt")
            specFunction.set("ordersSpec")
            packageName.set("example.generated")
            outputDir.set(layout.projectDirectory.dir("src/test/kotlin"))
        }
    }
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
