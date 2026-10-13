// A health check over the Lettuce connection a service already holds.
//
// `lettuce-core` is `compileOnly`, so the service's own version is the one on
// its classpath. It compiles against the 6.x line, which Spring Boot 3 still
// ships, so nothing only 7.x has can be called by accident; the container
// tests run on the current client.
val oldestLettuce = "6.8.2.RELEASE"
val currentLettuce = "7.8.0.RELEASE"

dependencies {
    api(project(":pelican-health"))
    compileOnly("io.lettuce:lettuce-core:$oldestLettuce")
    // Lettuce 6.8.2 brings Netty 4.1.125, which has open advisories (SNI
    // bypass, DNS cache poisoning, decoder resource exhaustion). Nothing here
    // ships it, but GitHub's dependency graph reports it all the same, and
    // 4.1.139 is the same API. The tests run on the 4.2 line 7.x brings.
    compileOnly(platform("io.netty:netty-bom:4.1.139.Final"))

    testImplementation("io.lettuce:lettuce-core:$currentLettuce")
    // A real Redis for the tests tagged `containers`. They need Docker, so
    // `build` leaves them out and CI runs them in a step of their own.
    testImplementation("org.testcontainers:testcontainers:2.0.5")
}

tasks.test {
    useJUnitPlatform { excludeTags("containers") }

    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.health.redis.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

val containerTests by tasks.registering(Test::class) {
    description = "Runs the tests that need Redis in Docker."
    group = "verification"
    useJUnitPlatform { includeTags("containers") }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
}

// Kover instruments every test task it can see and `check` depends on its
// verification, so a task it saw would put Docker back into `build`.
kover {
    currentProject {
        instrumentation { disabledForTestTasks.add("containerTests") }
    }
}
