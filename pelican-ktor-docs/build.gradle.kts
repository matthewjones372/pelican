// The Ktor half of the split pelican-pekko-docs and pelican-http4k-docs make:
// this is the only module that needs both a server interpreter and a document
// generator, so a service that serves endpoints alone never compiles the
// generator in.
dependencies {
    api(project(":pelican-ktor"))
    api(project(":pelican-openapi"))

    testImplementation(project(":pelican-jackson"))
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
}
