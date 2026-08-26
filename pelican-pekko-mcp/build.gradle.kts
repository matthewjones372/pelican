// The Pekko mounting of what pelican-mcp-server speaks, and the only thing in
// it: a route that hands a POST body to the protocol and turns the answer into
// an HttpResponse. Separate from pelican-pekko for the reason
// pelican-pekko-docs is — a service that serves endpoints alone never compiles
// the protocol in.
dependencies {
    api(project(":pelican-pekko"))
    api(project(":pelican-mcp-server"))
}
