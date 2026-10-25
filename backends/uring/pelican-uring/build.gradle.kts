// The runtime reaches the JDK's own continuation, which java.base does not export, and calls io_uring through FFM.
// Both are flags on every JVM that runs it; `UringSupport` names them when one is missing.
val runtimeFlags =
    listOf("--enable-native-access=ALL-UNNAMED", "--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED")

dependencies {
    api(project(":pelican-core"))

    testImplementation(project(":pelican-jackson"))
    testImplementation("org.eclipse.jetty:jetty-client:12.1.13")
    // Jetty is the reference: a socket test asks both servers the same question, with no Scala on the classpath.
    testImplementation(project(":pelican-jetty"))
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED"))
}

tasks.withType<Test>().configureEach {
    jvmArgs(runtimeFlags)
}

// detekt and ktlint read Kotlin only. The Java core gets whitespace rules that need no formatter download.
spotless {
    java {
        target("src/**/*.java")
        trimTrailingWhitespace()
        endWithNewline()
        leadingTabsToSpaces(4)
    }
}
