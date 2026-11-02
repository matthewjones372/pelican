package example.hello

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         implementation(platform("dev.surefold:pelican-bom:$pelicanVersion"))
 *         // the interpreter; brings pelican-core and Jetty's server
 *         implementation("dev.surefold:pelican-jetty")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("dev.surefold:pelican-jackson")
 *         // startWithDocs, /openapi.json and Swagger UI
 *         implementation("dev.surefold:pelican-jetty-docs")
 *     }
 */

/*
 * "Your first endpoint" from the README, verbatim, so it cannot rot: if this
 * file stops compiling, the front page of the project is wrong. Run it with
 * `./gradlew :example:runFirstEndpoint`, and see `FirstEndpointTest` for the
 * two lines of test the README shows beside it.
 */

import dev.surefold.pelican.api
import dev.surefold.pelican.div
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.jetty.docs.startWithDocs
import dev.surefold.pelican.jetty.handledNow
import dev.surefold.pelican.openapi.docs
import dev.surefold.pelican.pathParam

data class Greeting(val message: String)

val who = pathParam<String>("who", description = "Who to greet")

val greet = endpoint(who) {
    get("hello" / who)
    summary = "Greet somebody by name"
    json<Greeting>()
}

fun greetings() = api(
    endpoints = listOf(greet handledNow { name -> Greeting("Hello, $name!") }),
    codecs = JacksonCodecs,
) {
    title = "Greetings"
    version = "1.0.0"
}

fun main() {
    val server = greetings().startWithDocs(port = 8080, docs = docs { docsPath = "/api-docs" })
    println("Listening on ${server.baseUrl} — docs at ${server.baseUrl}/api-docs")
}
