package example

import dev.pelican.pekko.docs.startWithDocs

fun main(args: Array<String>) {
    // `./gradlew :example:run --args=8081` when 8080 is taken.
    val port = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val server = ordersApi().startWithDocs(port = port, docs = ordersDocs)
    println(
        """
        |Orders API listening on ${server.baseUrl}
        |
        |  GET  ${server.baseUrl}/users/1
        |  GET  ${server.baseUrl}/users/1/orders?limit=5&status=SHIPPED     (NDJSON stream)
        |  GET  ${server.baseUrl}/users/1/orders/watch?limit=10             (SSE stream)
        |  POST ${server.baseUrl}/users/1/orders   -H 'X-Api-Key: let-me-in'
        |  POST ${server.baseUrl}/echo             (streams the body back)
        |
        |  Spec ${server.baseUrl}/openapi.json
        |  Docs ${server.baseUrl}/api-docs
        |
        |Ctrl-C to stop.
        """.trimMargin(),
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.stop().toCompletableFuture().join() })
    Thread.currentThread().join()
}

/** What every example binds to unless told otherwise; `--args=8081` moves it. */
private const val DEFAULT_PORT = 8080
