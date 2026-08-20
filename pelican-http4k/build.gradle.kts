// The second backend, and the reason the codec and streaming abstractions in
// pelican-core are load-bearing rather than aspirational: the same endpoint
// values that pelican-pekko interprets are interpreted here into an http4k
// `HttpHandler`, and no description changes.
//
// http4k-core and nothing else. No JSON library — bodies go through the
// BodyCodec the Api was configured with — and no pelican-openapi, because
// serving documentation is opt-in and lives in pelican-http4k-docs.
// NoPekkoDependencyTest and NoOpenApiDependencyTest hold both lines.
//
// The version is pinned to the last http4k built against Kotlin 2.2.20, which
// is what this repository compiles with. A newer http4k ships stdlib metadata
// this compiler refuses to read, so bump both together or neither.
val http4kVersion = "6.22.0.0"
val slf4jVersion = "2.0.17"

dependencies {
    api(project(":pelican-core"))
    api("org.http4k:http4k-core:$http4kVersion")

    // See pelican-pekko: an unhandled throwable is caught here, so this module
    // is what has to log it.
    api("org.slf4j:slf4j-api:$slf4jVersion")

    testImplementation(project(":pelican-jackson"))
}
