package example.hello

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         implementation(platform("dev.surefold:pelican-bom:$pelicanVersion"))
 *         // the interpreter; brings pelican-core, and compiles against Pekko
 *         implementation("dev.surefold:pelican-pekko")
 *         implementation("dev.surefold:pelican-jackson")
 *         implementation("dev.surefold:pelican-pekko-docs")
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

import dev.surefold.pelican.api
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.openapi.docs
import dev.surefold.pelican.pekko.docs.startWithDocs
import dev.surefold.pelican.pekko.handledNow

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
