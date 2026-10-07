// A health check over the Kafka Admin client a service already holds.
//
// `kafka-clients` is `compileOnly`, so the service's own version is the one on
// its classpath rather than one this module chose. It compiles against the
// oldest 3.x line still supported, so nothing newer can be called by accident,
// and the container tests run against both that and the current client.
val oldestKafka = "3.9.2"
val currentKafka = "4.3.1"

dependencies {
    api(project(":pelican-health"))
    compileOnly("org.apache.kafka:kafka-clients:$oldestKafka")

    testImplementation("org.apache.kafka:kafka-clients:$currentKafka")
    // A real broker for the tests tagged `containers`. They need Docker, so
    // `build` leaves them out and CI runs them in a step of their own.
    testImplementation("org.testcontainers:testcontainers-kafka:2.0.5")
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
                "-Dpelican.health.kafka.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

// The same test classpath with the client held back to the oldest supported
// line, so a call that only exists in 4.x fails here rather than in a service.
val oldestKafkaRuntime: Configuration by configurations.creating {
    extendsFrom(configurations.testRuntimeClasspath.get())
    isCanBeConsumed = false
    resolutionStrategy.force("org.apache.kafka:kafka-clients:$oldestKafka")
}

fun Test.againstABroker() {
    group = "verification"
    useJUnitPlatform { includeTags("containers") }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    shouldRunAfter(tasks.test)
}

val containerTests by tasks.registering(Test::class) {
    description = "Runs the tests that need a Kafka broker in Docker, on the current client."
    againstABroker()
    classpath = sourceSets.test.get().runtimeClasspath
}

val containerTestsOldestKafka by tasks.registering(Test::class) {
    description = "Runs the tests that need a Kafka broker in Docker, on the oldest supported client."
    againstABroker()
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + oldestKafkaRuntime
}

// Kover instruments every test task it can see and `check` depends on its
// verification, so a task it saw would put Docker back into `build`.
kover {
    currentProject {
        instrumentation { disabledForTestTasks.addAll("containerTests", "containerTestsOldestKafka") }
    }
}
