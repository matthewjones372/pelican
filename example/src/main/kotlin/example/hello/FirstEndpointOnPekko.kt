package example.hello

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         implementation(platform("io.github.matthewjones372:pelican-bom:$pelicanVersion"))
 *         // the interpreter; brings pelican-core, and compiles against Pekko
 *         implementation("io.github.matthewjones372:pelican-pekko")
 *         implementation("io.github.matthewjones372:pelican-jackson")
 *         implementation("io.github.matthewjones372:pelican-pekko-docs")
 *         // Pekko itself: Pelican ships no Scala cross-build, so name yours
 *         implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
 *         implementation("org.apache.pekko:pekko-actor-typed_2.13")
 *         implementation("org.apache.pekko:pekko-stream_2.13")
 *         implementation("org.apache.pekko:pekko-http_2.13:1.4.1")
 *     }
 */

/*
 * The cookbook's "On Pekko" recipe, verbatim: the first endpoint's `greet`,
 * bound and served by Pekko HTTP. `FirstEndpointTest` calls it as well.
 */

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow

fun greetingsOnPekko() = api(
    endpoints = listOf(greet handledNow { name -> Greeting("Hello, $name!") }),
    codecs = JacksonCodecs,
) {
    title = "Greetings"
    version = "1.0.0"
}

fun main() {
    val server = greetingsOnPekko().startWithDocs(port = 8080, docs = docs { docsPath = "/api-docs" })
    println("Listening on ${server.baseUrl}, docs at ${server.baseUrl}/api-docs")
}
