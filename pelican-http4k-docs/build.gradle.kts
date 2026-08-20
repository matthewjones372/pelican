// The http4k half of the same split pelican-pekko-docs makes: this is the only
// module that needs both a server interpreter and a document generator, so a
// service that serves endpoints alone never compiles the generator in.
dependencies {
    api(project(":pelican-http4k"))
    api(project(":pelican-openapi"))

    testImplementation(project(":pelican-jackson"))
}
