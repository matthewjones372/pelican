val pekkoVersion = "1.7.1"
val pekkoHttpVersion = "1.4.1"

// Separate from pelican-jetty for the reason pelican-pekko-docs is separate from pelican-pekko:
// a service that publishes no docs never compiles or ships the generator.
dependencies {
    api(project(":pelican-jetty"))
    api(project(":pelican-openapi"))

    testImplementation(project(":pelican-jackson"))

    // Pekko is the reference: the test asks both servers the same questions over a socket.
    testImplementation(project(":pelican-pekko-docs"))
    testImplementation(platform("org.apache.pekko:pekko-bom_2.13:$pekkoVersion"))
    testImplementation("org.apache.pekko:pekko-actor-typed_2.13")
    testImplementation("org.apache.pekko:pekko-stream_2.13")
    testImplementation("org.apache.pekko:pekko-http_2.13:$pekkoHttpVersion")
}
