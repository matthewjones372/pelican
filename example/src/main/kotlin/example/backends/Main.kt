package example.backends

import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs

/**
 * The backend-agnostic endpoints, served by the backend 1.0 ships.
 *
 * ```
 * ./gradlew :example:runBackends            # Pekko :8080
 * ./gradlew :example:runBackends --args=9000
 * ```
 *
 * Note what the descriptions in `Greetings.kt` do *not* mention: a server
 * library, a JSON library, a stream type. Binding them is `OnPekko.kt`'s job
 * and nothing else's, which is the seam a second backend plugs back into.
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val docs = docs { docsPath = "/api-docs" }

    val pekko = pekkoApi().startWithDocs(port = port, docs = docs)

    println(
        """
        |The greetings service:
        |
        |  Pekko HTTP   ${pekko.baseUrl}
        |
        |  curl ${pekko.baseUrl}/hello/ada
        |  curl '${pekko.baseUrl}/hello/ada?shout=true'
        |
        |  curl -N ${pekko.baseUrl}/countdown/5      # NDJSON, one row every 100ms
        |
        |A cookie, a form and an upload — the three inputs that are not a
        |query parameter:
        |
        |  curl ${pekko.baseUrl}/preferences -H 'Cookie: locale=de; session=xyz'
        |  curl ${pekko.baseUrl}/sign-in -d 'user=ada&remember=on&visits=3'
        |  curl ${pekko.baseUrl}/upload -F caption=Notes -F file=@gradle.properties
        |
        |The preflight a browser would send, answered from the descriptions:
        |
        |  curl -i -X OPTIONS ${pekko.baseUrl}/echo \
        |    -H 'Origin: https://console.example.com' \
        |    -H 'Access-Control-Request-Method: POST'
        |
        |  curl -s ${pekko.baseUrl}/openapi.json
        |
        |Ctrl-C to stop.
        """.trimMargin(),
    )

    Runtime.getRuntime().addShutdownHook(Thread { pekko.stop() })
    pekko.block()
}

/** What every example binds to unless told otherwise; `--args=8081` moves it. */
private const val DEFAULT_PORT = 8080
