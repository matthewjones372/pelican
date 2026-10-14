// The Jetty half of the test support, apart from `pelican-test` for the reason `pelican-test-pekko` is: a service
// on another backend takes the typed client without Jetty arriving with it.
dependencies {
    api(project(":pelican-test"))
    api(project(":pelican-jetty"))

    testImplementation(project(":pelican-jackson"))
}
