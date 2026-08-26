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
request so a large upload is never held in memory. An event stream also takes a
`lastEventId` and reports the one it reached, so a caller can pick it up where
it stopped; the reconnect itself is the caller's, since how much of a gap is
worth replaying is not something a generated client can know. Declared failures become a
sealed type per endpoint: add a failure, regenerate, and the calls that do not
handle it stop compiling. A failure that declares response headers carries them
as properties, typed from the schema the document publishes — nullable, because
this is the reading end and a client that threw over a header would have thrown
away the failure it was handed.

Anything the endpoint did not describe is an `ApiCallFailed`, carrying the
status, the method, the path template and the body that arrived — capped at
8 KiB and marked where it was cut. A body the codec cannot read is refused the
same way even at a status the endpoint declared, because a proxy's HTML 404
satisfies the status and nothing else; the codec's own exception is the
`cause`. See
[docs/reference.md](reference.md#what-a-call-refuses-with).

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

`pelican-client-java` is the adapter over the JDK's own `HttpClient`: add the
module and a client finds it through `ServiceLoader`, with no line to write.

```kotlin
dependencies { implementation("io.github.matthewjones372:pelican-client-java:0.1.0") }

val client = OrdersClient("https://orders.internal", JacksonCodecs)
```

`pelican-client-pekko` is the second one, over Pekko HTTP's client, and the one
a service already running Pekko wants — it takes the `ActorSystem` that service
already has, so the calls go out on its dispatchers and under its
configuration:

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs, PekkoHttpTransport(system))
```

`pelican-client-ktor` is the third, over Ktor's `HttpClient`, and the one
a service already running Ktor wants — it takes the client that service has
already configured, so the calls go out through its engine, its plugins and its
connection pool:

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs, KtorHttpTransport(http))
```

Passing nothing there is also allowed, and then the adapter keeps a CIO client
of its own: a caller who has not chosen a Ktor engine does not have to choose
one to make a call. The suspending client behind the `CompletionStage`, and
what a per-request timeout does and does not bound there, are in
[docs/reference.md](reference.md#on-ktor).

`pelican-client-okhttp` is the fourth, over OkHttp's `Call`, and it takes the
`OkHttpClient` an application has already built — its interceptors, its cache
and its connection pool included:

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs, OkHttpTransport(okHttpClient))
```

### On the JVM it is a choice; on Android it is the answer

The module is plain JVM — core and `okhttp`, no Android plugin and no AndroidX,
which `DependenciesTest` asserts rather than promises. On a server it is one
adapter among four, worth taking when OkHttp is already the stack in the
process. On Android it is the only one of the four that runs at all: there is
no `java.net.http` on the platform, Pekko and Ktor-CIO are a second networking
stack in an app that already ships one, and OkHttp is what the platform uses
underneath anyway.

OkHttp **4.x is the floor**. Gradle resolves upwards, so a build already on
OkHttp 5 keeps it. A fleet pinned to OkHttp 3 has no adapter here and keeps the
escape hatch that predates this module — Ktor's client over an OkHttp engine,
which brings the Ktor machinery but leaves the socket work with the OkHttp 3
already in the build:

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs, KtorHttpTransport(HttpClient(OkHttp)))
```

More generally, a service that already runs an HTTP client passes that one as
the third argument and gets its pooling, its metrics and its tuning rather than
a second stack:

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs, ourOwnTransport)
```

Naming it is also what a classpath carrying *more than one* adapter has to do:
`ServiceLoader` finds two and the client refuses to guess between them, saying
so where it is constructed.

The stage is what makes that possible. The interface has to be the widest
shape, because an asynchronous transport can serve a blocking caller and a
blocking one cannot serve an asynchronous caller without a thread per call.
Streams cross it in both directions: a file part is a body the transport opens
and drains rather than one it is handed whole, and a streamed response arrives
as an unread stream that `Streamed<T>` decodes off as elements land. The
reasoning is in
[docs/reference.md](reference.md#the-transport-a-generated-client-sends-with).

## Presenting a credential

The fifth constructor argument is called once per request, which is what a
token that expires needs — a `Map` read at construction would be the token the
process started with:

```kotlin
val client = OrdersClient(
    "https://orders.internal",
    JacksonCodecs,
    headers = { mapOf("Authorization" to "Bearer ${tokens.current()}") },
)
```

Those headers go on every call this client makes, and on no webhook it sends:
a webhook goes to a subscriber's URL, and a subscriber is not the API. What a
receiver expects is declared on the webhook and arrives as a typed parameter.

A credential the *document* declares — `apiKeyHeader("X-Api-Key")` — is a
parameter on the methods that require it instead, because the description says
so. The lambda is for what every call carries.

## How long a call may take

`OrdersClient(..., timeout = Duration.ofSeconds(30))` is the deadline every
call is built with, and it reaches the transport as `ClientRequest.timeout`.
What that bounds is the transport's answer, and the four do not agree, so it
is worth knowing which you have:

| Transport | What the deadline bounds | Where it comes from |
|---|---|---|
| `pelican-client-java` | the response head; the body streams on after it | `HttpRequest.timeout` |
| `pelican-client-pekko` | the response head, likewise | the adapter, on the stage: Pekko's own timeouts are pool settings |
| `pelican-client-ktor`, with `HttpTimeout` installed | the whole exchange, the reading of the body included | `requestTimeoutMillis` |
| `pelican-client-ktor`, without it | the response head — the adapter imposes it, since Ktor would drop it | the adapter's own deadline |
| `pelican-client-okhttp` | the response head, likewise | the adapter, on the stage: OkHttp's `callTimeout` is the whole exchange, so it is not used |
| `InMemoryClientTransport` | nothing; there is no clock in a function call | — |

A streamed call — `ndjson`, `sse`, `jsonArray` or `bytes` — carries no deadline
at all. A deadline is for a call that ends, and an SSE subscription is meant to
stay open: inheriting the client's would have ended it mid-body on Ktor and
left it running on the other two, which is a difference no description
mentions. Bound a stream by taking what you need from it and closing it —
`use { it.take(100).toList() }` — or by the idle timeout on the engine you
handed over.

## Calling it without a socket

`InMemoryClientTransport` is a `ClientTransport` over an `Api` value, so a
generated client can be pointed at the service in the same process:

```kotlin
val client = OrdersClient("http://orders.test", JacksonCodecs, InMemoryClientTransport(api))

client.getUser(1L) shouldBe Outcome.Ok(User(1L, "Ada Lovelace", "ada@example.com"))
```

No port is bound and nothing is mocked. Routing, input decoding, the filters
the `Api` declares, the handlers and the response building are the ones a bound
server runs, because they are the same functions reading the same descriptions
— a test written this way is a test of the service, and it starts in
microseconds. It lives in `pelican-core`, so it is also available to production
wiring that would rather call a service it happens to be hosting than send a
request to itself.

Two things a backend owns cannot cross, and both are refused by name rather
than by `ClassCastException`. A `bytes(...)` request body: the handle a handler
reads it through is that backend's own type, and core has no value to hand
over. And a streamed response a handler produced as something other than a
`Sequence` — Pekko's `Source`, Ktor's `Flow` — which core cannot read without
depending on that library. An http4k binding streams as a `Sequence` and
crosses whole; for the other two, those calls belong against a bound server.

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
    ClientTransport.default().retrying(),          // retryPolicy(), or one of your own
)
```

`retryPolicy()` sends three times at most, waits 100ms and then 200ms with half
of each wait randomised, and retries only 408, 429, 502, 503 and 504, only on
the methods HTTP calls idempotent, and only for an `IOException`. It is not
retrying a 500, a POST, or a body it cannot ask for twice. A `Retry-After` is
honoured where it is longer than the computed wait, and where it asks for more
than ten seconds the policy stops retrying rather than waiting that long. Every
one of those is a constructor argument. The defence of each default is in
[docs/reference.md](reference.md#retrying-and-what-is-safe-to-retry).
