import io.github.matthewjones372.pelican.gradle.DocumentFormat

plugins {
    application
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
    // And serving the tools is opt-in in exactly the same way: `example.mcp` is
    // what that looks like. `pelican-mcp` and `pelican-mcp-server` arrive
    // through it, which is why neither is declared here.
    implementation(project(":pelican-pekko-mcp"))
    implementation(project(":pelican-jackson"))
    // Arrow's Either at the edge of a Pelican handler: `example.arrow` is what
    // it looks like, and the module is the whole of what it takes.
    implementation(project(":pelican-arrow"))
    // Meters, which are opt-in in the same way serving the docs is: this is the
    // module that adds them, and `example.telemetry` is what it looks like.
    implementation(project(":pelican-metrics"))
    // The same idea through the other vendor's API, and a separate module for
    // exactly that reason: `example.telemetry` is what it looks like. The SDK is
    // declared here rather than arriving through the module, because which SDK
    // a service runs — or whether it runs one at all — is the service's choice
    // and not the library's.
    implementation(project(":pelican-metrics-otel"))
    implementation("io.opentelemetry:opentelemetry-sdk:1.65.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")
    // `example.logging` claims a level per status and a template rather than a
    // path; RequestLogTest reads the lines back through logback's own appender
    // rather than trusting the source, which needs it at compile scope there.
    testImplementation("ch.qos.logback:logback-classic:1.6.3")

    // The OpenAPI documents this repository emits are checked by a parser that
    // is not the one that wrote them — swagger-parser reads the document back
    // and reports what is wrong with it. A generator marking its own homework
    // is worth very little; see OpenApiSpecQualityTest.
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.47")

    // The compiler, so that "this does not compile, and here is what it says"
    // can be a test rather than a sentence in a document that goes stale.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")

    // The importer's own DSL — `importOptions(...) { }` — is part of the frozen
    // surface, so StillCompilesTest compiles a call site for it. Test scope
    // only: `pelicanImport` below is still what the import *task* runs against,
    // and nothing this module ships or serves sees it.
    testImplementation(project(":pelican-import"))

    // The generated client is compiled in this source set, and a generated
    // client needs a transport.
    testImplementation(project(":pelican-client-pekko"))

    // What the suspending client generated below is written against.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // The parser the assertions read responses back with. Deliberately not the
    // library under test: a suite that checked Jackson's bytes by handing them
    // to Jackson would be marking its own homework, which is the same reason
    // swagger-parser sits in front of the emitted document above. Only the
    // runtime is here — there is no serializer and no compiler plugin, because
    // `parseToJsonElement` needs neither.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // The in-memory metric reader `TracedOrdersTest` collects the histogram
    // through. The in-memory *span* exporter lives in the same artifact, but
    // the example writes its own so that the runnable service does not have to
    // ship a testing library to render `/admin/traces`.
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.65.0")

    testImplementation(project(":pelican-test"))
    // The in-memory transports are per-backend, so a suite asks for the one it
    // needs. `pelican-test` itself stays backend-agnostic.
    testImplementation(project(":pelican-test-pekko"))

    // Version-less: the BOM comes transitively from pelican-pekko.
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_2.13")
    // Golden files for the document and for the request lines: the half of the
    // contract a typed call is blind to. See GoldenContractTest.
    testImplementation(project(":pelican-test-golden"))

    // Matchers, declared here rather than arriving through pelican-test. The
    // library ships assertions that throw AssertionError; which matcher
    // library a suite uses on top is the suite's own choice.
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")

    // Generators, for the one suite that asks a question about *every* string
    // rather than about the seven a person thought of. Test scope, and only
    // here: what it generates are request lines, and this is where the backends
    // run behind one seam to be asked the same one.
    testImplementation("io.kotest:kotest-property:6.2.4")
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

/**
 * The bookshop: a domain that fails in three ways, and a document that says so.
 * `./gradlew :example:runShop --args=8082`.
 */
tasks.register<JavaExec>("runShop") {
    group = "application"
    description = "Runs the Rookery Books example"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.shop.ShopKt")
}

/**
 * A domain written in Arrow, described by Pelican: `Either` at the edge, in one
 * call. `./gradlew :example:runArrow --args=8083`.
 */
tasks.register<JavaExec>("runArrow") {
    group = "application"
    description = "Runs the Subscriptions example, whose domain is written in Arrow"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.arrow.SubscriptionsKt")
}

/**
 * An access log at the level each status earns, and the refusals a filter
 * cannot see. `./gradlew :example:runLogging --args=8084`.
 */
tasks.register<JavaExec>("runLogging") {
    group = "application"
    description = "Runs the Widgets example, which logs every call"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.logging.RequestLogKt")
}

/** The security example: basic auth, an external identity provider, and a docs page that can sign in. */
tasks.register<JavaExec>("runSecured") {
    group = "application"
    description = "Runs the secured Reports example"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.secured.SecuredReportsKt")
}

/** The endpoints that never name a server library, bound to the one 1.0 ships. */
tasks.register<JavaExec>("runBackends") {
    group = "application"
    description = "Runs the greetings example through the backend seam"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.backends.MainKt")
}

/** The same service over whichever JSON library it is handed. */
tasks.register<JavaExec>("runCodecs") {
    group = "application"
    description = "Runs the notes example over a codec module it does not name"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.codecs.PluggableCodecsKt")
}

/** The same service with its tools beside its endpoints: `curl` `/mcp` with a JSON-RPC message. */
tasks.register<JavaExec>("runMcp") {
    group = "application"
    description = "Runs the Orders example with its MCP tools served on /mcp"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.mcp.MainKt")
}

/** The metered service: `curl` it, then read `/admin/meters` to see what that recorded. */
/** The traced service: `curl` it, then read `/admin/traces` to see the spans that produced. */
tasks.register<JavaExec>("runTelemetry") {
    group = "application"
    description = "One service, metered and traced, with a report that answers both."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("example.telemetry.MainKt")
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
        // And the same endpoints written against the current specification.
        // Not a third thing to publish: it is what the round trip below reads,
        // so that the three fields 3.2 moved are exercised by a real document
        // through the real plugin rather than by a fixture somebody typed.
        create("orders32") {
            specClass.set("example.GenerateOpenApiKt")
            specFunction.set("ordersSpec")
            openApiVersion.set("3.2.0")
            outputFile.set(layout.buildDirectory.file("openapi-3.2.json"))
        }
    }
    clients {
        create("orders") {
            specClass.set("example.GenerateOpenApiKt")
            specFunction.set("ordersSpec")
            packageName.set("example.generated")
            outputDir.set(layout.projectDirectory.dir("src/test/kotlin"))
        }
        // The same descriptions, generated the other way round: one method per
        // endpoint again, each of them `suspend`. Two entries rather than a
        // switch on the first, because the two shapes are two audiences and
        // this repository is both of them — the suite calls the blocking client
        // from a test method and the suspending one from a coroutine, against
        // the same server.
        //
        // Not committed, unlike the entry above. Where that one is a reviewable
        // file with a `checkOrdersClient` behind it, this one takes the
        // default: written into `build/`, compiled by the test source set, and
        // regenerated on every run. Both paths are ones a consumer takes, and
        // each is exercised once here.
        create("ordersSuspending") {
            specClass.set("example.GenerateOpenApiKt")
            specFunction.set("ordersSpec")
            packageName.set("example.generated.suspending")
            callStyle.set("suspending")
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
        /**
         * The same descriptions, read back out of the 3.2 rendering.
         *
         * The claim being tested is that choosing a version changes what the
         * document says and not what it means: `ImportedOrdersTest` compares
         * these descriptions against the ones above and expects no difference.
         * It is worth a second entry because 3.2 puts a streamed response's
         * schema in a different field and an event stream's payload two levels
         * further in, and the orders service has both.
         *
         * No `handlers` here — the stubs the entry above writes are the same
         * stubs, and writing them twice would prove nothing new.
         */
        create("imported32") {
            document.set(layout.buildDirectory.file("openapi-3.2.json"))
            packageName.set("example.imported32")
            classpath.setFrom(pelicanImport)
        }
    }
}

// The document is generated by this build, so the import waits for it. A
// consumer importing a document somebody published has nothing to wait for.
tasks.named("generateImportedEndpoints") { dependsOn("generateOrdersDocument") }
tasks.named("generateImported32Endpoints") { dependsOn("generateOrders32Document") }

sourceSets["test"].kotlin.srcDir(layout.buildDirectory.dir("generated/pelican/imported"))
sourceSets["test"].kotlin.srcDir(layout.buildDirectory.dir("generated/pelican/imported32"))

// The suspending client, which is generated rather than committed: the
// directory the task defaults to, added to the source set that calls it.
sourceSets["test"].kotlin.srcDir(layout.buildDirectory.dir("generated/pelican/ordersSuspending"))

// Generated source compiled here but written by another module's tests. detekt
// filters by the path *inside* the source root, so this is that path rather
// than the `build/generated` one a reader would expect — a consumer pointing
// the task at a source root of their own has the same line to write, and the
// reference manual says so.
tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    exclude("example/imported/**")
    exclude("example/imported32/**")
    exclude("example/generated/suspending/**")
}

tasks.named("compileTestKotlin") {
    dependsOn(
        "generateImportedEndpoints",
        "generateImported32Endpoints",
        "generateOrdersSuspendingClient",
    )
}

// Two suites here run the Kotlin compiler inside the test JVM, and
// StillCompilesTest hands it nine fixtures against this module's whole
// classpath at once. Gradle's default 512m turns twelve seconds of that into a
// garbage-collection stall long enough to trip the build's 60s test timeout.
tasks.withType<Test>().configureEach { maxHeapSize = "2g" }

// The benchmarks are a JMH harness in `:benchmarks`: `./gradlew :benchmarks:jmh`.
