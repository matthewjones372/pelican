// The Ktor half of the Arrow interop, and the one that is not only streams:
// Ktor's binders take `suspend` handlers, so the value binders are re-declared
// here with a suspend lambda. A Raise block that awaits something is the usual
// case on this backend, and `raise` works across the suspension because the
// whole block still runs inside one `fold`.
dependencies {
    api(project(":pelican-arrow"))
    api(project(":pelican-ktor"))

    testImplementation(project(":pelican-jackson"))
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-cio")
}

// See pelican-arrow: the handler lambdas take `Raise<E>` as a context parameter.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
}
