// The http4k mounting of what pelican-mcp-server speaks, and the only thing in
// it: a route that hands a POST body to the protocol and turns the answer into
// a Response. Separate from pelican-http4k for the reason pelican-http4k-docs
// is — a service that serves endpoints alone never compiles the protocol in.
dependencies {
    api(project(":pelican-http4k"))
    api(project(":pelican-mcp-server"))

    testImplementation(project(":pelican-jackson"))
}
