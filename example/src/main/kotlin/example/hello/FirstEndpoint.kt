package example.hello

/*
 * "Your first endpoint" from the README, verbatim, so it cannot rot: if this
 * file stops compiling, the front page of the project is wrong. Run it with
 * `./gradlew :example:runFirstEndpoint`, and see `FirstEndpointTest` for the
 * two lines of test the README shows beside it.
 */

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pekko.*
import io.github.matthewjones372.pelican.pekko.docs.Docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs

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
    val server = greetings().startWithDocs(port = 8080, docs = Docs(docsPath = "/api-docs"))
    println("Listening on ${server.baseUrl} — docs at ${server.baseUrl}/api-docs")
}
