// The Ktor mounting of what pelican-mcp-server speaks, and the only thing in
// it: a route that hands a POST body to the protocol and turns the answer into
// a response. Separate from pelican-ktor for the reason pelican-ktor-docs is —
// a service that serves endpoints alone never compiles the protocol in.
dependencies {
    api(project(":pelican-ktor"))
    api(project(":pelican-mcp-server"))

    testImplementation(project(":pelican-jackson"))
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
}
