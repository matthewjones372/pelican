// The third backend, and the one that answers in a coroutine rather than on a
// thread: handlers here are `suspend` functions and streams are `Flow`, which
// is what a Ktor service already writes. The endpoint descriptions are the same
// values pelican-pekko and pelican-http4k interpret, unchanged.
//
// Ktor's server and nothing else. No JSON library — bodies go through the
// BodyCodec the Api was configured with — and no pelican-openapi, because
// serving documentation is opt-in and lives in pelican-ktor-docs.
// DecouplingTest holds both lines.
//
// ktor-server-cio is here so that `start()` binds a socket with no further
// dependency, exactly as the JDK's own server does for http4k. Any other engine
// works: pass its factory to `start` and add that Ktor module to your build.
val ktorVersion = "3.5.2"
val slf4jVersion = "2.0.17"

dependencies {
    api(project(":pelican-core"))
    api(platform("io.ktor:ktor-bom:$ktorVersion"))
    api("io.ktor:ktor-server-core")
    api("io.ktor:ktor-server-cio")

    // See pelican-pekko: an unhandled throwable is caught here, so this module
    // is what has to log it.
    api("org.slf4j:slf4j-api:$slf4jVersion")

    testImplementation(project(":pelican-jackson"))
    testImplementation("io.ktor:ktor-server-test-host")
}
