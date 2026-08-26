package example.hello

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter; brings pelican-core and Pekko HTTP
 *         implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC1")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC1")
 *         // startWithDocs, /openapi.json and Swagger UI
 *         implementation("io.github.matthewjones372:pelican-pekko-docs:1.0.0-RC1")
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
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow

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
