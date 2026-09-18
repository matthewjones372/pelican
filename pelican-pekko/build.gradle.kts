val slf4jVersion = "2.0.18"
val pekkoVersion = "1.7.0"
val pekkoHttpVersion = "1.4.0"
val scalaBinary = "2.13"

// The only module that knows Pekko exists — and it still knows nothing about
// JSON libraries. Bodies go through the BodyCodec the Api was configured with,
// so there is deliberately no Jackson or kotlinx dependency here.
//
// No pelican-openapi either: serving documentation is opt-in, and lives in
// pelican-pekko-docs. A service that only serves endpoints never compiles the
// document generator in. NoOpenApiDependencyTest holds that line.
dependencies {
    api(project(":pelican-core"))

    // Pekko is compiled against, not shipped: `pekko-actor_2.13` and
    // `pekko-actor_3` are separate Maven modules holding identical class names,
    // so no resolver sees a conflict and an application on the Scala 3 build
    // that received ours would carry both until Pekko's own version check
    // refused to start. The suffix and versions below are what the suites run
    // against; the application declares the ones it already runs.
    compileOnly(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    compileOnly("org.apache.pekko:pekko-actor-typed_$scalaBinary")
    compileOnly("org.apache.pekko:pekko-stream_$scalaBinary")
    compileOnly("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")

    // A 500 is caught here, so the server underneath never sees it. Something
    // has to write it down, and an API — not a binding — is the right weight:
    // the application picks the implementation. No Scala suffix, so this one is
    // shipped like any other dependency.
    api("org.slf4j:slf4j-api:$slf4jVersion")

    // Pekko's own route testkit, rather than a hand-rolled way of running a
    // route. `pekko-stream-testkit` and `pekko-testkit` are `provided` in its
    // pom and JUnit 4 is `test`, so all of them are named here: TestRouteResult
    // reports a failure through `org.junit.Assert`, which has to be on the
    // classpath even though nothing in this repository is a JUnit 4 test.
    // A codec for the body-limit test; the module itself needs none.
    testImplementation(project(":pelican-jackson"))

    testImplementation(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    testImplementation("org.apache.pekko:pekko-actor-typed_$scalaBinary")
    testImplementation("org.apache.pekko:pekko-stream_$scalaBinary")
    testImplementation("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")
    testImplementation("org.apache.pekko:pekko-http-testkit_$scalaBinary:$pekkoHttpVersion")
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_$scalaBinary")
    testImplementation("org.apache.pekko:pekko-stream-testkit_$scalaBinary")
    testImplementation("org.apache.pekko:pekko-testkit_$scalaBinary")
    testImplementation("junit:junit:4.13.2")

    // Routes Pekko's own logging at the slf4j API above. A binding, so it is
    // the application's to choose in the same way the implementation is.
    testRuntimeOnly("org.apache.pekko:pekko-slf4j_$scalaBinary")
}

// The claim 0037 rests on: this module's compiled output links against either
// Scala cross-build of Pekko, because it names `javadsl` and four types that
// are identical in both. Everything else here runs at `_2.13`, so without this
// source set that claim is an argument from an import list.
//
// `sourceSets.main.output` rather than a project dependency: the project would
// bring the `_2.13` artifacts back with it, and the bytecode on its own is what
// a consumer downloads.
val scala3Test: SourceSet = sourceSets.create("scala3Test")

dependencies {
    "scala3TestImplementation"(sourceSets.main.get().output)
    "scala3TestImplementation"(project(":pelican-core"))
    "scala3TestImplementation"(project(":pelican-jackson"))

    // The other four modules that name Pekko, so the linkage check covers all
    // of them. Since Pekko is `compileOnly` in each, a project dependency
    // brings none of it — which `Scala3LinkageTest` asserts rather than
    // assumes.
    "scala3TestImplementation"(project(":pelican-client-pekko"))
    "scala3TestImplementation"(project(":pelican-pekko-docs"))
    "scala3TestImplementation"(project(":pelican-pekko-mcp"))
    "scala3TestImplementation"(project(":pelican-test-pekko"))
    "scala3TestImplementation"("org.slf4j:slf4j-api:$slf4jVersion")

    "scala3TestImplementation"(platform("org.apache.pekko:pekko-bom_3:$pekkoVersion"))
    "scala3TestImplementation"("org.apache.pekko:pekko-actor-typed_3")
    "scala3TestImplementation"("org.apache.pekko:pekko-stream_3")
    "scala3TestImplementation"("org.apache.pekko:pekko-http_3:$pekkoHttpVersion")

    "scala3TestImplementation"(kotlin("test"))
    "scala3TestImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
    "scala3TestImplementation"("io.kotest:kotest-assertions-core:6.2.4")
    "scala3TestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val scala3TestTask = tasks.register<Test>("scala3Test") {
    description = "Runs the interpreter against the Scala 3 cross-build of Pekko."
    group = "verification"
    testClassesDirs = scala3Test.output.classesDirs
    classpath = scala3Test.runtimeClasspath
    useJUnitPlatform()
}

tasks.named("check") { dependsOn(scala3TestTask) }

tasks.test {
    // The main runtime classpath, so DependenciesTest can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.pekko.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
