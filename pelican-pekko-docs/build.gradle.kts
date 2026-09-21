val pekkoVersion = "1.7.0"
val pekkoHttpVersion = "1.4.0"
val scalaBinary = "2.13"

// The glue that serves documentation over HTTP, and the only module that needs
// both a server and a document generator. Keeping it separate is what lets a
// service depend on pelican-pekko alone: no OpenAPI code is compiled into the
// server, and none of it ships with a service that does not publish docs.
dependencies {
    api(project(":pelican-pekko"))
    api(project(":pelican-openapi"))

    // Provided, as in `pelican-pekko`: it is `compileOnly` there, so it does
    // not reach this module's compile classpath through the `api` above.
    compileOnly(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    compileOnly("org.apache.pekko:pekko-actor-typed_$scalaBinary")
    compileOnly("org.apache.pekko:pekko-stream_$scalaBinary")
    compileOnly("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")
}
