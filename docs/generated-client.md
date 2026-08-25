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
a `Codecs` chosen by the caller, and a `ClientTransport` to send with. The
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

## Where the requests go

The generated code never names an HTTP library. It builds a `ClientRequest` —
a method, an assembled and already-encoded URL, headers, a per-request timeout,
and a body that is empty, text or a stream — and hands it to a
`ClientTransport`, which answers with a `CompletionStage<ClientResponse>`.
Both types are core's, so the file still compiles against `pelican-core` and
nothing else.

```kotlin
fun interface ClientTransport {
    fun send(request: ClientRequest): CompletionStage<ClientResponse>
}
```

`pelican-client-java` is the adapter over the JDK's own `HttpClient` and the
default: add the module and a client finds it through `ServiceLoader`, with no
line to write.

```kotlin
dependencies { implementation("io.github.matthewjones372:pelican-client-java:0.1.0") }

val client = OrdersClient("https://orders.internal", JacksonCodecs)
```

A service that already runs an HTTP client passes that one instead, as the
third argument, and gets its pooling, its metrics and its tuning rather than a
second stack:

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs, ourOwnTransport)
```

The stage is what makes that possible. The interface has to be the widest
shape, because an asynchronous transport can serve a blocking caller and a
blocking one cannot serve an asynchronous caller without a thread per call.
Streams cross it in both directions: a file part is a body the transport opens
and drains rather than one it is handed whole, and a streamed response arrives
as an unread stream that `Streamed<T>` decodes off as elements land. The
reasoning is in
[docs/reference.md](reference.md#the-transport-a-generated-client-sends-with).

## Blocking or suspending

A generated client's methods block by default: they join the stage and unwrap
the `CompletionException`, so a caller catches what the transport actually
raised. One line on the entry generates the other surface instead:

```kotlin
clients {
    create("orders") {
        specClass.set("com.example.OrdersSpecKt")
        packageName.set("com.example.orders")
        callStyle.set("suspending")   // or "blocking", the default
    }
}
```

```kotlin
suspend fun place(key: String): Order? = when (val result = client.placeOrder(1L, CreateOrder("anvil"), key)) {
    is Outcome.Ok  -> result.value
    is Outcome.Err -> null
}
```

Same class, same method names, same parameters, same sealed failures — the
methods suspend, and the file needs `kotlinx-coroutines-core` beside
`pelican-core`. One shape per file rather than both on one class: both would
put two methods per endpoint on a class whose appeal is one method per
endpoint, and leave a blocking method where a coroutine could call it and park
a dispatcher thread for the length of an HTTP call. The choice belongs to
whoever generates the client, which is the caller, so it divides nothing.

A coroutine cancelled while a call is outstanding cancels the exchange rather
than leaving it running. Reading a whole body — a socket read, wherever it
happens — is done on `Dispatchers.IO`; a `Streamed<T>` is read by whoever
iterates it, so iterate one inside `withContext(Dispatchers.IO)`.

## Retrying

Nothing retries unless a transport is wrapped in something that does, which is
a decorator over the same interface rather than anything in the generated file:

```kotlin
val client = OrdersClient(
    "https://orders.internal",
    JacksonCodecs,
    ClientTransport.default().retrying(),          // RetryPolicy(), or one of your own
)
```

`RetryPolicy()` sends three times at most, waits 100ms and then 200ms with half
of each wait randomised, and retries only 408, 429, 502, 503 and 504, only on
the methods HTTP calls idempotent, and only for an `IOException`. It is not
retrying a 500, a POST, or a body it cannot ask for twice. A `Retry-After` is
honoured where it is longer than the computed wait, and where it asks for more
than ten seconds the policy stops retrying rather than waiting that long. Every
one of those is a constructor argument. The defence of each default is in
[docs/reference.md](reference.md#retrying-and-what-is-safe-to-retry).
