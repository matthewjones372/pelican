import io.github.matthewjones372.pelican.gradle.DocumentFormat

plugins {
    application
    // For the codecs example, whose payload types are @Serializable so that
    // kotlinx.serialization can be one of the three libraries reading them.
    kotlin("plugin.serialization")
    // The build's own plugin, included from pluginManagement in settings.gradle.kts.
    // The example applies it by id exactly as a consumer would, which is what
    // keeps the plugin honest: if generation breaks, this build breaks.
    id("io.github.matthewjones372.pelican")
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
    // Meters, which are opt-in in the same way serving the docs is: this is the
    // module that adds them, and `example.metrics` is what it looks like.
    implementation(project(":pelican-metrics"))
    // The other two codec modules, for `example.codecs`: the same endpoints and
    // handlers served three times, once per JSON library. `pelican-kotlinx` also
    // carries the parser the assertions use — the tests read responses off a
    // socket, and something has to turn them back into a tree, which
    // `parseToJsonElement` does with no serializer of its own.
    implementation(project(":pelican-kotlinx"))
    implementation(project(":pelican-jsoniter"))
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")

    // The OpenAPI documents this repository emits are checked by a parser that
    // is not the one that wrote them — swagger-parser reads the document back
    // and reports what is wrong with it. A generator marking its own homework
    // is worth very little; see OpenApiSpecQualityTest.
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.47")

    testImplementation(project(":pelican-test"))
    // The in-memory transports are per-backend, so the suites that run twice
    // ask for the one they need. `pelican-test` itself stays backend-agnostic.
    testImplementation(project(":pelican-test-pekko"))

    // Version-less: the BOM comes transitively from pelican-pekko.
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_2.13")
    testImplementation(project(":pelican-test-http4k"))
    // Golden files for the document and for the request lines: the half of the
    // contract a typed call is blind to. See GoldenContractTest.
    testImplementation(project(":pelican-test-golden"))

    // Matchers, declared here rather than arriving through pelican-test. The
    // library ships assertions that throw AssertionError; which matcher
    // library a suite uses on top is the suite's own choice.
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
}

/**
 * What the import task runs against, kept out of the example's own classpaths.
 */
val pelicanImport = configurations.register("pelicanImport")

dependencies { pelicanImport(project(":pelican-import")) }

application { mainClass.set("example.MainKt") }

/** The second example, so both can be run: `./gradlew :example:runBookmarks --args=8081`. */
tasks.register<JavaExec>("runBookmarks") {
    group = "application"
    description = "Runs the Bookmarks example"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.bookmarks.BookmarksKt")
}

/** The security example: basic auth, an external identity provider, and a docs page that can sign in. */
tasks.register<JavaExec>("runSecured") {
    group = "application"
    description = "Runs the secured Reports example"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.secured.SecuredReportsKt")
}

/** The same Orders service, served by http4k instead of Pekko. */
tasks.register<JavaExec>("runHttp4k") {
    group = "application"
    description = "Runs the Orders example on the http4k backend"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.http4k.Http4kOrdersKt")
}

/** The same two endpoints, served by all three backends at once, for comparing them. */
tasks.register<JavaExec>("runBackends") {
    group = "application"
    description = "Runs the greetings example on Pekko, http4k and Ktor side by side"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.backends.MainKt")
}

/** The same service, served three times over three JSON libraries. */
tasks.register<JavaExec>("runCodecs") {
    group = "application"
    description = "Runs the notes example on Jackson, kotlinx.serialization and jsoniter side by side"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.codecs.ThreeCodecsKt")
}

/** The metered service: `curl` it, then read `/admin/meters` to see what that recorded. */
tasks.register<JavaExec>("runMetrics") {
    group = "application"
    description = "Runs the Orders example with Micrometer meters taken from the descriptions"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.metrics.MeteredOrdersKt")
}

/** The README's "Your first endpoint", kept runnable for the same reason. */
tasks.register<JavaExec>("runFirstEndpoint") {
    group = "application"
    description = "Runs the one-endpoint example from the README"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.hello.FirstEndpointKt")
}

/** The README's example, kept runnable so the front page cannot drift. */
tasks.register<JavaExec>("runReadmeExample") {
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
 */
pelican {
    documents {
        create("orders") {
            specClass.set("example.GenerateOpenApiKt")
            specFunction.set("ordersSpec")
            outputFile.set(layout.buildDirectory.file("openapi.json"))
            // The document callers hold, committed. Naming it registers
            // `checkOrdersDocument` and wires it into `check`: a change that
            // would break somebody written against this file fails the build
            // and prints what it would do to them. The same file is the
            // golden `GoldenContractTest` records, so the two cannot disagree.
            baseline.set(layout.projectDirectory.file("src/test/resources/golden/openapi.json"))
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
    /**
     * The same document, read back the other way.
     */
    endpoints {
        create("imported") {
            document.set(layout.buildDirectory.file("openapi.json"))
            packageName.set("example.imported")
            classpath.setFrom(pelicanImport)
            // Stubs as well, so that the third thing this proves is that a
            // handler bound to an imported endpoint type-checks — against a
            // real backend, for every output kind the example describes.
            handlers.set("pekko")
        }
    }
}

// The document is generated by this build, so the import waits for it. A
// consumer importing a document somebody published has nothing to wait for.
tasks.named("generateImportedEndpoints") { dependsOn("generateOrdersDocument") }

sourceSets["test"].kotlin.srcDir(layout.buildDirectory.dir("generated/pelican/imported"))

// Generated source compiled here but written by another module's tests. detekt
// filters by the path *inside* the source root, so this is that path rather
// than the `build/generated` one a reader would expect — a consumer pointing
// the task at a source root of their own has the same line to write, and the
// reference manual says so.
tasks.withType<dev.detekt.gradle.Detekt>().configureEach { exclude("example/imported/**") }

tasks.named("compileTestKotlin") { dependsOn("generateImportedEndpoints") }

// The benchmarks are a JMH harness in `:benchmarks`: `./gradlew :benchmarks:jmh`.
