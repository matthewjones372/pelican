// Separate from pelican-gannet for the reason pelican-jetty-docs is separate from pelican-jetty:
// a service that publishes no docs never compiles or ships the generator.
dependencies {
    api(project(":pelican-gannet"))
    api(project(":pelican-openapi"))

    testImplementation(project(":pelican-jackson"))
    testImplementation(testFixtures(project(":pelican-gannet")))

    // Jetty is the reference: the test asks both servers the same questions over a socket.
    testImplementation(project(":pelican-jetty-docs"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED")
}
