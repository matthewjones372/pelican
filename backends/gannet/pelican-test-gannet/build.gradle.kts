// The Gannet half of the test support, apart from `pelican-test` for the reason `pelican-test-jetty` is: a service on
// another backend takes the typed client without Gannet arriving with it.
dependencies {
    api(project(":pelican-test"))
    api(project(":pelican-gannet"))

    testImplementation(project(":pelican-jackson"))
    testImplementation(testFixtures(project(":pelican-gannet")))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED")
}
