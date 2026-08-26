package example.backends

import io.github.matthewjones372.pelican.openapi.Docs
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.http4k.docs.startWithDocs as startHttp4kWithDocs
import io.github.matthewjones372.pelican.ktor.docs.startWithDocs as startKtorWithDocs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs as startPekkoWithDocs

/**
 * All three servers at once, so the same endpoints can be curled side by side.
 *
 * ```
 * ./gradlew :example:runBackends            # Pekko :8080, http4k :8081, Ktor :8082
 * ./gradlew :example:runBackends --args=9000
 * ```
 *
 * Note what is *not* aliased apart: the endpoint values, the handlers' logic
 * and the OpenAPI document. Those are shared, which is why the `diff` at the
 * bottom of the banner prints nothing.
 */
fun main(args: Array<String>) {
    val basePort = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val docs = docs { docsPath = "/api-docs" }

    val pekko = pekkoApi().startPekkoWithDocs(port = basePort, docs = docs)
    val http4k = http4kApi().startHttp4kWithDocs(port = basePort + 1, docs = docs)
    val ktor = ktorApi().startKtorWithDocs(port = basePort + 2, docs = docs)

    println(
        """
        |The same three endpoints, served three times:
        |
        |  Pekko HTTP   ${pekko.baseUrl}
        |  http4k       ${http4k.baseUrl}
        |  Ktor         ${ktor.baseUrl}
        |
        |Ask all three the same questions and compare:
        |
        |  curl ${pekko.baseUrl}/hello/ada
        |  curl ${http4k.baseUrl}/hello/ada
        |  curl ${ktor.baseUrl}/hello/ada
        |
        |  curl '${ktor.baseUrl}/hello/ada?shout=true'
        |
        |  curl -N ${pekko.baseUrl}/countdown/5      # NDJSON, one row every 100ms
        |  curl -N ${http4k.baseUrl}/countdown/5
        |  curl -N ${ktor.baseUrl}/countdown/5
        |
        |A cookie, a form and an upload — the three inputs that are not a
        |query parameter, and all three read identically by all three servers:
        |
        |  curl ${pekko.baseUrl}/preferences -H 'Cookie: locale=de; session=xyz'
        |  curl ${http4k.baseUrl}/sign-in -d 'user=ada&remember=on&visits=3'
        |  curl ${ktor.baseUrl}/upload -F caption=Notes -F file=@gradle.properties
        |
        |The preflight a browser would send, answered from the descriptions:
        |
        |  curl -i -X OPTIONS ${pekko.baseUrl}/echo \
        |    -H 'Origin: https://console.example.com' \
        |    -H 'Access-Control-Request-Method: POST'
        |
        |  diff <(curl -s ${pekko.baseUrl}/openapi.json) <(curl -s ${ktor.baseUrl}/openapi.json)
        |
        |Ctrl-C to stop.
        """.trimMargin(),
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            ktor.stop()
            http4k.stop()
            pekko.stop()
        },
    )
    pekko.block()
}

/** What every example binds to unless told otherwise; `--args=8081` moves it. */
private const val DEFAULT_PORT = 8080
