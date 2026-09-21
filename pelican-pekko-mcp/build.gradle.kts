val pekkoVersion = "1.7.0"
val pekkoHttpVersion = "1.4.0"
val scalaBinary = "2.13"

// The Pekko mounting of what pelican-mcp-server speaks, and the only thing in
// it: a route that hands a POST body to the protocol and turns the answer into
// an HttpResponse. Separate from pelican-pekko for the reason
// pelican-pekko-docs is — a service that serves endpoints alone never compiles
// the protocol in.
dependencies {
    api(project(":pelican-pekko"))
    api(project(":pelican-mcp-server"))

    // Provided, as in `pelican-pekko`: it is `compileOnly` there, so it does
    // not reach this module's compile classpath through the `api` above.
    compileOnly(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    compileOnly("org.apache.pekko:pekko-stream_$scalaBinary")
    compileOnly("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")
}
