// The Pekko half of the test support: an in-memory transport that runs a
// request straight through the interpreted route, and the one-line bridge from
// a started `PelicanServer` to a client.
//
// Split out of `pelican-test` because it was the only thing in there that
// needed a server library, and an `api(project(":pelican-pekko"))` on the
// shared module put Pekko HTTP and Pekko streams on the test classpath of
// every Ktor and http4k service that wanted a typed client.
dependencies {
    api(project(":pelican-test"))
    api(project(":pelican-pekko"))

    testImplementation(project(":pelican-jackson"))
}
