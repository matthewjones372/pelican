package example.hello

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         implementation(platform("io.github.matthewjones372:pelican-bom:$pelicanVersion"))
 *         // the interpreter; brings pelican-core and Jetty's server
 *         implementation("io.github.matthewjones372:pelican-jetty")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson")
 *         // startWithDocs, /openapi.json and Swagger UI
 *         implementation("io.github.matthewjones372:pelican-jetty-docs")
 *     }
 */

/*
 * "Your first endpoint" from the README, verbatim, so it cannot rot: if this
 * file stops compiling, the front page of the project is wrong. Run it with
 * `./gradlew :example:runFirstEndpoint`, and see `FirstEndpointTest` for the
 * two lines of test the README shows beside it.
 */

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.docs.startWithDocs
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pathParam

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
