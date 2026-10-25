plugins {
    // `RequiresGannet` and `assumeGannet`, shared with the tests of every module that serves on io_uring.
    `java-test-fixtures`
}

// A snapshot from Central's snapshot repository, which settings.gradle.kts admits for this group only.
val gannetVersion = "0.1.0-SNAPSHOT"

// Gannet reaches the JDK's own continuation, which java.base does not export, and calls io_uring through FFM. Both
// are flags on every JVM that runs it; `GannetSupport` names them when one is missing.
val runtimeFlags =
    listOf("--enable-native-access=ALL-UNNAMED", "--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED")

dependencies {
    api(project(":pelican-core"))
    // The server underneath, its own library; its types are in this module's signatures.
    api("io.github.matthewjones372:gannet:$gannetVersion")

    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api:6.1.3")

    testImplementation(project(":pelican-jackson"))
    testImplementation("org.eclipse.jetty:jetty-client:12.1.13")
    // Jetty is the reference: a socket test asks both servers the same question, with no Scala on the classpath.
    testImplementation(project(":pelican-jetty"))
}

// The fixtures are this build's test support, not a library: they stay out of the published module.
val javaComponent = components["java"] as AdhocComponentWithVariants
configurations.matching { it.name.startsWith("testFixtures") && it.name.endsWith("Elements") }.all {
    javaComponent.withVariantsFromConfiguration(this) { skip() }
}

// Nor are they code under test, so coverage leaves them out.
kover {
    currentProject {
        sources { excludedSourceSets.add("testFixtures") }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs(runtimeFlags)
}
