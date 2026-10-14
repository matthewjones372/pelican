val pekkoVersion = "1.7.1"
val pekkoHttpVersion = "1.4.1"

// The Jetty mounting of what pelican-mcp-server speaks, separate from pelican-jetty for the reason
// pelican-pekko-mcp is separate from pelican-pekko: a service serving endpoints alone never compiles the protocol in.
dependencies {
    api(project(":pelican-jetty"))
    api(project(":pelican-mcp-server"))

    testImplementation(project(":pelican-jackson"))

    // Pekko is the reference: the test asks both servers the same questions over a socket.
    testImplementation(project(":pelican-pekko-mcp"))
    testImplementation(platform("org.apache.pekko:pekko-bom_2.13:$pekkoVersion"))
    testImplementation("org.apache.pekko:pekko-actor-typed_2.13")
    testImplementation("org.apache.pekko:pekko-stream_2.13")
    testImplementation("org.apache.pekko:pekko-http_2.13:$pekkoHttpVersion")
}
