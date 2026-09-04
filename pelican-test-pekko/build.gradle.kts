val pekkoVersion = "1.2.1"
val pekkoHttpVersion = "1.3.0"
val scalaBinary = "2.13"

// The Pekko half of the test support: an in-memory transport that runs a
// request straight through the interpreted route, and the one-line bridge from
// a started `PelicanServer` to a client.
//
// Split out of `pelican-test` because it was the only thing in there that
// needed a server library, and an `api(project(":pelican-pekko"))` on the
// shared module put Pekko HTTP and Pekko streams on the test classpath of
// every service on another backend that wanted a typed client.
dependencies {
    api(project(":pelican-test"))
    api(project(":pelican-pekko"))

    // Provided, as in `pelican-pekko`: it is `compileOnly` there, so it does
    // not reach this module's compile classpath through the `api` above.
    compileOnly(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    compileOnly("org.apache.pekko:pekko-actor-typed_$scalaBinary")
    compileOnly("org.apache.pekko:pekko-stream_$scalaBinary")
    compileOnly("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")

    testImplementation(project(":pelican-jackson"))
    testImplementation(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    testImplementation("org.apache.pekko:pekko-actor-typed_$scalaBinary")
    testImplementation("org.apache.pekko:pekko-stream_$scalaBinary")
    testImplementation("org.apache.pekko:pekko-http_$scalaBinary:$pekkoHttpVersion")
}
