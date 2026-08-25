/**
 * The benchmarks, in a module of their own and run by JMH.
 *
 * A timing loop in a test measures nothing trustworthy: no warmup control, no
 * fork isolation, nothing stopping the JIT deleting work whose result goes
 * unused, and allocation read off a thread counter. JMH answers all four, and
 * the only thing hand-written here is the wiring that runs it.
 */

// One version, shared by the runtime the benchmarks compile against and the
// generator that reads them. They are the same release or the generated stubs
// do not match the core they call.
val jmhVersion = "1.37"

dependencies {
    implementation(project(":pelican-core"))
    implementation(project(":pelican-http4k"))
    implementation(project(":pelican-pekko"))
    implementation(project(":pelican-jackson"))

    // http4k-core, pekko-http and jackson-module-kotlin all arrive as `api` of
    // the three modules above. The hand-written baselines are written against
    // exactly what a consumer of those modules already has, which is the point:
    // a baseline reaching for a dependency Pelican does not would be measuring
    // a different thing.
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
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
val benchmarkJdk = JavaLanguageVersion.of(21)
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
