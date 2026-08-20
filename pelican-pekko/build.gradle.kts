val slf4jVersion = "2.0.17"
val pekkoVersion = "1.6.0"
val pekkoHttpVersion = "1.4.0"
val scalaBinary = "2.13"

// The only module that knows Pekko exists — and it still knows nothing about
// JSON libraries. Bodies go through the BodyCodec the Api was configured with,
// so there is deliberately no Jackson or kotlinx dependency here.
//
// No pelican-openapi either: serving documentation is opt-in, and lives in
// pelican-pekko-docs. A service that only serves endpoints never compiles the
// document generator in. NoOpenApiDependencyTest holds that line.
dependencies {
    api(project(":pelican-core"))

    api(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    api("org.apache.pekko:pekko-actor-typed_$scalaBinary")
    api("org.apache.pekko:pekko-stream_$scalaBinary")
    api("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")
    implementation("org.apache.pekko:pekko-slf4j_$scalaBinary")

    // A 500 is caught here, so the server underneath never sees it. Something
    // has to write it down, and an API — not a binding — is the right weight:
    // the application picks the implementation.
    api("org.slf4j:slf4j-api:$slf4jVersion")
}
