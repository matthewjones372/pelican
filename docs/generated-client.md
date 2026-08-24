# A generated Kotlin client

Linked from the [README](../README.md); the half of Pelican that faces the
people calling your service rather than the people writing it.

Callers who cannot hold the descriptions, because they are in another repository
or on another release cycle, get a file generated from them instead. It is a
Gradle task: no `main` to write, no `JavaExec` to wire.

```kotlin
plugins { id("io.github.matthewjones372.pelican") version "0.1.0" }

pelican {
    clients {
        create("orders") {
            specClass.set("com.example.OrdersSpecKt")   // where ordersSpec() lives
            specFunction.set("ordersSpec")
            packageName.set("com.example.orders")
        }
    }
}
```

`./gradlew generateOrdersClient` writes
`build/generated/pelican/orders/com/example/orders/OrdersClient.kt`. Point
`outputDir` at a source root instead and the client is a file you commit and
review — which turns on `checkOrdersClient`, wired into `check`, so it cannot
quietly stop matching the descriptions. The plugin generates the OpenAPI
document from the same function; see
[docs/reference.md](reference.md#the-gradle-plugin).

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs)

val orders = client.listOrders(1L, limit = 3)          // Streamed<Order>, as they arrive

when (val result = client.placeOrder(1L, CreateOrder("anvil"), xApiKey = key)) {
    is Outcome.Ok  -> result.value                     // Order
    is Outcome.Err -> when (val failure = result.failure) {   // exhaustive
        is PlaceOrderFailure.Unauthorized   -> retryWith(freshKey())
        is PlaceOrderFailure.NotFound       -> null
        is PlaceOrderFailure.TooManyRequests -> sleep(failure.retryAfter)   // Long?
    }
}
```

One method per endpoint, named by its `operationId`. Path parameters are
positional; query parameters, headers and cookies are named parameters with
defaults, and leaving one out leaves it off the request rather than sending it
empty. A streaming endpoint hands back a `Streamed<T>`, a `Sequence` over the
open connection decoded as elements land, and a file part is streamed into the
request so a large upload is never held in memory. Declared failures become a
sealed type per endpoint: add a failure, regenerate, and the calls that do not
handle it stop compiling. A failure that declares response headers carries them
as properties, typed from the schema the document publishes — nullable, because
this is the reading end and a client that threw over a header would have thrown
away the failure it was handed.

An operation the document says is served somewhere else — an upload host, a
read replica — is called there. `servers("https://uploads.example.com")` on an
endpoint reaches the document and the generated method, which sends that one
call to that host whatever base URL the client was given. Routing ignores it
entirely: a server serves what it serves, and no description moves a request.

A `webhook(...)` becomes a sender rather than a call: `orderPlaced(url, body,
xSignature)`, with the destination first because the document does not know it.
See [Webhooks](#webhooks).

The generated file needs `pelican-core`, which has no dependencies of its own,
and a `Codecs` chosen by the caller. Transport is the JDK's `HttpClient`. The
example checks its generated client into the repo and runs the suite against a
real server, so a test fails if the file drifts from the descriptions.

A `oneOf` in the spec becomes a sealed interface, and that is the one payload
shape no JSON library can read off the Kotlin alone: which property carries the
branch, and what string selects each one, has to be written down. So those
declarations are annotated for one library, and `codec.set("kotlinx")` on the
client entry chooses which — the same setting an `endpoints` entry takes,
because a client's bodies are read by the same library the service's are. Unset
is Jackson, and a spec with no union generates the same client either way.

`ordersSpec().writeKotlinClient(sourceRoot, packageName = "com.example.orders")`
is the same thing without the build task, for a build that would rather make the
call itself.
