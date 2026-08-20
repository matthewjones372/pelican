// The Pekko half of the Arrow interop: the streaming binders, which are the
// only ones that have to name a backend type. Everything else a Raise handler
// needs is in pelican-arrow and works here unchanged.
dependencies {
    api(project(":pelican-arrow"))
    api(project(":pelican-pekko"))

    testImplementation(project(":pelican-jackson"))
    testImplementation(project(":pelican-test"))
    testImplementation(project(":pelican-test-pekko"))
}

// See pelican-arrow: the handler lambdas take `Raise<E>` as a context parameter.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
}
