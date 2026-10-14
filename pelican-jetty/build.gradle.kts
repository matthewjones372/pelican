val jettyVersion = "12.1.13"
val slf4jVersion = "2.0.20"
val pekkoVersion = "1.7.1"
val pekkoHttpVersion = "1.4.1"

dependencies {
    api(project(":pelican-core"))

    // Shipped rather than `compileOnly` as Pekko is: Jetty has no Scala cross-build to leave to the caller.
    api("org.eclipse.jetty:jetty-server:$jettyVersion")
    api("org.slf4j:slf4j-api:$slf4jVersion")

    testImplementation(project(":pelican-jackson"))

    // Pekko is the reference: a socket test asks both servers the same question.
    testImplementation(project(":pelican-pekko"))
    testImplementation(platform("org.apache.pekko:pekko-bom_2.13:$pekkoVersion"))
    testImplementation("org.apache.pekko:pekko-actor-typed_2.13")
    testImplementation("org.apache.pekko:pekko-stream_2.13")
    testImplementation("org.apache.pekko:pekko-http_2.13:$pekkoHttpVersion")
}
