package example.backends

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // startWithDocs, /openapi.json and Swagger UI
 *         implementation("io.github.matthewjones372:pelican-jetty-docs:1.0.0")
 *     }
 */

import io.github.matthewjones372.pelican.jetty.docs.startWithDocs
import io.github.matthewjones372.pelican.openapi.docs

/**
 * The backend-agnostic endpoints, served by Jetty.
 *
 * ```
 * ./gradlew :example:runBackends            # Jetty :8080
 * ./gradlew :example:runBackends --args=9000
 * ```
 *
 * Note what the descriptions in `Greetings.kt` do *not* mention: a server
 * library, a JSON library, a stream type. Binding them is `OnJetty.kt`'s job
 * and nothing else's; `OnPekko.kt` binds the same ones to the other server.
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val docs = docs { docsPath = "/api-docs" }

    val jetty = jettyApi().startWithDocs(port = port, docs = docs)

    println(
        """
        |The greetings service:
        |
        |  Jetty   ${jetty.baseUrl}
        |
        |  curl ${jetty.baseUrl}/hello/ada
        |  curl '${jetty.baseUrl}/hello/ada?shout=true'
        |
        |  curl -N ${jetty.baseUrl}/countdown/5      # NDJSON, one row every 100ms
        |
        |A cookie, a form and an upload — the three inputs that are not a
        |query parameter:
        |
        |  curl ${jetty.baseUrl}/preferences -H 'Cookie: locale=de; session=xyz'
        |  curl ${jetty.baseUrl}/sign-in -d 'user=ada&remember=on&visits=3'
        |  curl ${jetty.baseUrl}/upload -F caption=Notes -F file=@gradle.properties
        |
        |The preflight a browser would send, answered from the descriptions:
        |
        |  curl -i -X OPTIONS ${jetty.baseUrl}/echo \
        |    -H 'Origin: https://console.example.com' \
        |    -H 'Access-Control-Request-Method: POST'
        |
        |  curl -s ${jetty.baseUrl}/openapi.json
        |
        |Ctrl-C to stop.
        """.trimMargin(),
    )

    Runtime.getRuntime().addShutdownHook(Thread { jetty.stop() })
    jetty.block()
}

/** What every example binds to unless told otherwise; `--args=8081` moves it. */
private const val DEFAULT_PORT = 8080
