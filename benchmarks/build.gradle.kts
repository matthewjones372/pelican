/**
 * The benchmarks, in a module of their own and run by JMH.
 *
 * A timing loop in a test measures nothing trustworthy: no warmup control, no
 * fork isolation, nothing stopping the JIT deleting work whose result goes
 * unused, and allocation read off a thread counter. JMH answers all four, and
 * the only thing hand-written here is the wiring that runs it.
 */

// For `DecodeBenchmark`'s payload, which kotlinx reads only if it is `@Serializable`.
plugins { kotlin("plugin.serialization") }

// One version, shared by the runtime the benchmarks compile against and the
// generator that reads them. They are the same release or the generated stubs
// do not match the core they call.
val jmhVersion = "1.37"

dependencies {
    implementation(project(":pelican-core"))
    implementation(project(":pelican-pekko"))
    implementation(project(":pelican-jackson"))
    implementation(project(":pelican-jetty"))
    // The third server in `:benchmarks:wire` (spec 0088).
    implementation(project(":pelican-gannet"))
    implementation(project(":pelican-kotlinx"))

    // Pekko is declared here for the reason a consumer declares it: it is
    // `compileOnly` in `pelican-pekko`, which ships no Scala cross-build.
    implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-stream_2.13")
    implementation("org.apache.pekko:pekko-http_2.13:1.4.2")

    // jackson-module-kotlin arrives as `api` of the module above, and the
    // hand-written baselines are written against exactly what a consumer of
    // these modules already has, which is the point: a baseline reaching for a
    // dependency Pelican does not would be measuring a different thing.
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")

    // `:benchmarks:wire`'s percentiles, and the client it drives both servers with: the release `pelican-jetty` serves.
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    implementation("org.eclipse.jetty:jetty-client:12.1.14")
}

/**
 * The generator, kept off the compile and runtime classpaths on purpose.
 *
 * JMH's annotations are normally processed at compile time, but `kapt` is the
 * only way to run a Java annotation processor over Kotlin and it would mean
 * adding a compiler plugin to this build for one module. The bytecode
 * generator reads the compiled classes instead and emits the same stubs — it
 * is what the `me.champeau.jmh` plugin falls back to for any JVM language that
 * is not Java, so this is that plugin's own path taken directly.
 *
 * Taken directly rather than by applying the plugin because this build pins
 * every version inline and maintains a Gradle plugin of its own already; the
 * three tasks below are what the plugin would have contributed, minus a
 * dependency whose latest release predates the Gradle this build runs on.
 */
val jmhGenerator = configurations.register("jmhGenerator")

dependencies { jmhGenerator("org.openjdk.jmh:jmh-generator-bytecode:$jmhVersion") }

val generatedStubSources = layout.buildDirectory.dir("generated/jmh/java")
val generatedStubResources = layout.buildDirectory.dir("generated/jmh/resources")

val benchmarkClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    .flatMap { it.destinationDirectory }

val benchmarkRuntimeClasspath = the<SourceSetContainer>()["main"].runtimeClasspath

/**
 * The JDK the whole harness runs on, named here rather than inherited.
 *
 * A task registered by hand does not pick up the toolchain the Kotlin plugin
 * sets for the project, so without these two the stubs would be compiled and
 * forked by whichever JVM happened to be running the Gradle daemon. The first
 * time that happened it produced class files the benchmark JVM refused to
 * load; the quieter failure is a number measured on a JDK nobody wrote down.
 */
val toolchains = extensions.getByType<JavaToolchainService>()
val benchmarkJdk = JavaLanguageVersion.of(25)
val toolchainLauncher = toolchains.launcherFor { languageVersion.set(benchmarkJdk) }

/**
 * The reflection generator, not the ASM one.
 */
val generateBenchmarkStubs = tasks.register<JavaExec>("generateBenchmarkStubs") {
    description = "Generates JMH's benchmark stubs from the compiled Kotlin"
    mainClass.set("org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator")
    classpath(jmhGenerator, benchmarkRuntimeClasspath)
    javaLauncher.set(toolchainLauncher)
    inputs.dir(benchmarkClasses).withPropertyName("benchmarkClasses")
    outputs.dir(generatedStubSources)
    outputs.dir(generatedStubResources)
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                benchmarkClasses.get().asFile.path,
                generatedStubSources.get().asFile.path,
                generatedStubResources.get().asFile.path,
                "reflection",
            )
        },
    )
    // The generator appends to `BenchmarkList` rather than replacing it, so a
    // second run over a renamed benchmark would leave the old name in the list
    // and JMH would fail looking for a class that is no longer there.
    doFirst {
        delete(generatedStubSources)
        delete(generatedStubResources)
    }
}

val compileBenchmarkStubs = tasks.register<JavaCompile>("compileBenchmarkStubs") {
    description = "Compiles the generated JMH stubs"
    dependsOn(generateBenchmarkStubs)
    source(generatedStubSources)
    classpath = benchmarkRuntimeClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/jmh/java"))
    javaCompiler.set(toolchains.compilerFor { languageVersion.set(benchmarkJdk) })
    options.encoding = "UTF-8"
}

/**
 * The run itself: `./gradlew :benchmarks:jmh`.
 *
 * The second flight-records the run: JMH records each fork separately and
 * knows which part of it was warmup.
 */
val jmh = tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the JMH benchmarks (about six minutes; nothing else depends on it)"
    dependsOn(compileBenchmarkStubs)
    mainClass.set("org.openjdk.jmh.Main")
    classpath(
        compileBenchmarkStubs.map { it.destinationDirectory },
        generatedStubResources,
        benchmarkRuntimeClasspath,
    )
    // The launcher decides which JVM JMH forks, because a forked child
    // inherits `java.home` from the process that spawned it. Without this the
    // numbers would be whatever JDK happened to be running the Gradle daemon,
    // which is not the one the rest of the build compiles for.
    javaLauncher.set(toolchainLauncher)
    val results = layout.buildDirectory.file("jmh-result.json").get().asFile
    args("-prof", "gc", "-rf", "json", "-rff", results.path)
    args(providers.gradleProperty("benchmarkArgs").getOrElse("").split(" ").filter { it.isNotBlank() })
}

val wireAb = providers.gradleProperty("wireAb")
val baselineClasspath = layout.buildDirectory.file("wire-ab/baseline-classpath.txt")

/**
 * The B side of `-PwireAb=<ref>`: `<ref>` checked out as a worktree under `build/wire-ab`, with this checkout's
 * `WireServer.kt` copied in so only the libraries differ, built by its own Gradle, and its classpath written out.
 */
val wireBaseline = tasks.register("wireBaseline") {
    description = "Builds the wire server against -PwireAb's ref, for `wire` to run as B"
    onlyIf { wireAb.isPresent }
    val repo = rootDir
    val dir = layout.buildDirectory.dir("wire-ab/baseline").get().asFile
    val server = file("src/main/kotlin/io/github/matthewjones372/pelican/benchmarks/wire/WireServer.kt")
    val initScript = file("wire-ab.init.gradle.kts")
    val out = baselineClasspath.get().asFile
    doLast {
        fun run(workDir: File, vararg command: String) {
            val code = ProcessBuilder(*command).directory(workDir).inheritIO().start().waitFor()
            check(code == 0) { "${command.joinToString(" ")} exited $code" }
        }
        run(repo, "git", "worktree", "prune")
        if (dir.resolve(".git").exists()) {
            run(dir, "git", "checkout", "-q", "--force", "--detach", wireAb.get())
        } else {
            run(repo, "git", "worktree", "add", "-q", "--detach", dir.path, wireAb.get())
        }
        server.copyTo(dir.resolve(server.relativeTo(repo)), overwrite = true)
        run(
            dir,
            "./gradlew",
            "-q",
            "--init-script",
            initScript.path,
            "-PwireClasspathOut=${out.path}",
            ":benchmarks:wireClasspath",
        )
    }
}

/**
 * `./gradlew :benchmarks:wire`: Pelican on Pekko and on Jetty over a loopback socket, driven at fixed rates (about
 * eight minutes; nothing else depends on it). The table lands in `build/wire-result.md` as well as on stdout.
 * `-PwireSmoke` runs one few-second step per server, to check the setup rather than measure. `-PwireScenario` is
 * `rest` (the default), `async`, `stream` or `both`. `-PwireAb=<ref>` compares Jetty from this checkout (A) with Jetty from
 * `<ref>` (B), alternating for `-PwireRounds` rounds (3), into `build/wire-ab.md`; `-PwireAbPekko` adds Pekko.
 * `-PwirePool=queued|virtual` picks the pool Jetty's `start()` builds; `-PwireAbPool` compares this checkout on
 * `VirtualThreadPool` (A) with it on the queued pool (B), with no second build. `-PwireConnector=<k=v,...>` applies
 * Jetty connector settings to every Jetty run; `-PwireAbConnector=<k=v,...>` compares this checkout with those
 * settings (A) against it on Jetty's defaults (B), with no second build.
 */
tasks.register<JavaExec>("wire") {
    group = "verification"
    description = "Drives Pelican on Pekko and on Jetty over loopback at fixed rates and reports latency"
    dependsOn(wireBaseline)
    mainClass.set("io.github.matthewjones372.pelican.benchmarks.wire.WireDriverKt")
    classpath(benchmarkRuntimeClasspath)
    javaLauncher.set(toolchainLauncher)
    jvmArgs("-Xms1g", "-Xmx1g")
    args(layout.buildDirectory.get().asFile.path)
    if (providers.gradleProperty("wireSmoke").isPresent) args("--smoke")
    providers.gradleProperty("wireScenario").orNull?.let { args("--scenario", it) }
    providers.gradleProperty("wireRounds").orNull?.let { args("--rounds", it) }
    if (providers.gradleProperty("wireAbPekko").isPresent) args("--pekko")
    if (wireAb.isPresent) args("--baseline", baselineClasspath.get().asFile.path)
    providers.gradleProperty("wirePool").orNull?.let { args("--pool", it) }
    if (providers.gradleProperty("wireAbPool").isPresent) args("--ab-pool")
    providers.gradleProperty("wireConnector").orNull?.let { args("--connector", it) }
    providers.gradleProperty("wireAbConnector").orNull?.let { args("--ab-connector", it) }
}
