# A generated Kotlin client

A caller who cannot depend on your endpoint descriptions (another repository,
another release cycle) gets a Kotlin source file generated from them instead:
one class, one method per endpoint, typed from the same schemas the OpenAPI
document publishes. The Gradle plugin generates it, and can check a committed
copy against the descriptions on every `./gradlew check`.

This page covers what you configure, what Pelican generates, and what you keep
up to date yourself. The design behind the transport and the retry policy is in
[docs/reference.md](reference.md#the-transport-a-generated-client-sends-with).

## Configuring the plugin

The plugin is on Maven Central rather than the Gradle Plugin Portal; the
`pluginManagement` block that lets a build resolve it is in the
[README](../README.md#the-gradle-plugin). The generator runs against a
classpath that holds your spec function and `pelican-codegen`, so the source
set the spec is compiled in needs `pelican-codegen` as a dependency:

```kotlin
// build.gradle.kts
plugins { id("io.github.matthewjones372.pelican") version "1.0.0" }

dependencies {
    implementation("io.github.matthewjones372:pelican-core:1.0.0")
    implementation("io.github.matthewjones372:pelican-codegen:1.0.0") // what the generate task calls
}

pelican {
    clients {
        create("orders") {
            specClass.set("com.example.OrdersSpecKt")  // the JVM class holding ordersSpec()
            specFunction.set("ordersSpec")
            packageName.set("com.example.orders")
        }
    }
}
```

`example/build.gradle.kts` has three real entries: `orders` (committed under
`src/test/kotlin`), `ordersSuspending` and `adoption` (both generated into
`build/`).

### Every setting on a `clients` entry

| Property | Default | What it does |
|---|---|---|
| `specClass` | none, required | The class holding the spec function, as the JVM names it: a top-level `ordersSpec()` in `OrdersSpec.kt` lives in `com.example.OrdersSpecKt`. |
| `specFunction` | `spec` | A no-argument function returning the `ApiSpec`: top-level, on an `object`, or on a class with a no-argument constructor. |
| `classpath` | `main`'s runtime classpath | What the generator loads the spec and `pelican-codegen` from. The default carries its own task dependencies, so generating compiles `main` first. |
| `packageName` | none, required | The package the file declares, and the directories under `outputDir` it is written into. |
| `clientName` | the spec's title + `Client` | `Orders` gives `OrdersClient`; a title that makes no usable name gives `ApiClient`. Also the file name. |
| `baseUrl` | the spec's first server | The default for the constructor's `baseUrl` argument. |
| `includeHidden` | `false` | Hidden endpoints are left out, as they are left out of the document. Set it to generate an internal client. |
| `codec` | `jackson` | `jackson` or `kotlinx`: which library the payload types are annotated for. See [Payload types and codecs](#payload-types-and-codecs). |
| `callStyle` | `blocking` | `blocking` or `suspending`. See [Blocking or suspending](#blocking-or-suspending). |
| `outputDir` | `build/generated/pelican/<name>` | The source root written into. Where it points decides whether the check task runs; see below. |

`codec` and `callStyle` are matched case-insensitively; any other value fails
the task naming the accepted ones.

### Tasks, and where the file goes

Each entry registers two tasks, named from the entry with its first letter
capitalised: `orders` gives `generateOrdersClient` and `checkOrdersClient`.

`generate<Name>Client` writes `<outputDir>/<package directories>/<ClientName>.kt`,
for the block above `build/generated/pelican/orders/com/example/orders/OrdersClient.kt`.
What happens next depends on `outputDir`:

- **Inside `build/` (the default).** The directory belongs to the task: it is
  emptied before each run, so a renamed client leaves nothing behind, and it is
  a tracked output. The plugin does not add it to a source set. Add it to the
  one that calls the client, and make that compile depend on the task:

  ```kotlin
  sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/pelican/orders"))
  tasks.named("compileKotlin") { dependsOn("generateOrdersClient") }
  ```

  Nothing in `build/` can drift from the descriptions, so `check<Name>Client`
  is not wired into `check`.

- **A source root, such as `src/main/kotlin`.** The client is a file you
  commit and review. The task never reports up to date and does not empty the
  directory, and compiling does not depend on it: you regenerate on purpose.
  `check<Name>Client` is wired into `check`. It generates into a temporary
  directory and fails when the committed file is missing or differs, saying
  "Run the matching generate task and commit the result".

The plugin runs the generator on Gradle's JVM, or in a process on the project's
Java toolchain when that toolchain is newer than Gradle's.

### Without Gradle

The task calls one function in `pelican-codegen`, which you can call yourself:

```kotlin
import com.example.ordersSpec
import io.github.matthewjones372.pelican.codegen.writeKotlinClient
import java.nio.file.Path

fun main() {
    ordersSpec().writeKotlinClient(Path.of("src/main/kotlin"), packageName = "com.example.orders")
}
```

It takes the same settings as named arguments (`clientName`, `baseUrl`,
`includeHidden`, `codec = CodecAnnotations.KOTLINX`, `callStyle =
CallStyle.SUSPENDING`) and returns the file it wrote. `kotlinClient(...)`
returns the source as a string instead.

## What is generated

One file. Read `example/src/test/kotlin/example/generated/OrdersClient.kt`
for a complete one; it is committed and checked by `checkOrdersClient`. In the
file's package:

- **The client class**, with this constructor:

  ```kotlin
  class OrdersClient(
      baseUrl: String = DEFAULT_BASE_URL,                         // from `baseUrl`, or the spec's first server
      private val codecs: Codecs,
      private val transport: ClientTransport = ClientTransport.default(),
      private val timeout: Duration = Duration.ofSeconds(30),
      private val headers: () -> Map<String, String> = { emptyMap() },
  )
  ```

- **Payload types**: a data class per object schema, an enum class per enum,
  a sealed interface per `oneOf`.
- **`<Operation>Failure`**: a sealed interface per endpoint that declares
  failures, one data class per declared failure status carrying the decoded
  body and any declared response headers.
- **`<Operation>Result`**: a sealed interface per endpoint that answers with
  more than one 2xx, so a 201 and a 202 stay distinguishable.
- **A fixed runtime section**: `Outcome<F, T>` with `Ok` and `Err`,
  `valueOrNull()`, `orThrow()`, `ApiCallFailed`, `Streamed<T>`,
  `TextResponse` and `MultipartContent`. These are declared in the generated
  package, not imported from Pelican, so `Outcome` here is
  `com.example.orders.Outcome` and not core's server-side
  `io.github.matthewjones372.pelican.Outcome`.

### Calling it

From `example/src/test/kotlin/example/PekkoTransportClientTest.kt` and the
generated `OrdersClient.kt`, with `packageName` set to `example.generated`:

```kotlin
import example.generated.CreateOrder
import example.generated.Order
import example.generated.OrdersClient
import example.generated.Outcome
import example.generated.PlaceOrderFailure
import io.github.matthewjones372.pelican.jackson.JacksonCodecs

fun place(client: OrdersClient, key: String): Order? =
    when (val result = client.placeOrder(1L, CreateOrder("anvil"), xApiKey = key)) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> when (val failure = result.failure) {        // exhaustive
            is PlaceOrderFailure.Unauthorized -> null
            is PlaceOrderFailure.NotFound -> null
            is PlaceOrderFailure.TooManyRequests -> null                // failure.retryAfter: Long?
        }
    }

val client = OrdersClient("https://orders.internal", JacksonCodecs)
val orders = client.listOrders(1L, limit = 3).use { it.toList() }       // Streamed<Order>
```

What a method looks like follows from its endpoint:

- **Name.** The `operationId`.
- **Parameters.** Path parameters are positional. Query parameters, headers
  and cookies are named parameters; optional ones default to `null`, and a
  `null` is left off the request rather than sent empty. A credential the
  endpoint declares, such as `apiKeyHeader("X-Api-Key")`, is a parameter
  (`xApiKey`) on the methods that require it.
- **Return type.** `Outcome<PlaceOrderFailure, Order>` when the endpoint
  declares failures; the success type itself (`Order`, `Unit`) when it
  declares none. `valueOrNull()` turns an `Outcome` into the value or `null`;
  `orThrow()` returns the value or throws `IllegalStateException` naming the
  failure. The failure types are sealed, so adding a failure to an endpoint
  and regenerating makes every `when` that does not handle it stop compiling.
- **Response headers on a failure** are properties typed from the schema, and
  nullable: a missing header does not throw away the failure it came with.
- **Streaming responses** (`ndjson`, `sse`, `jsonArray`) return `Streamed<T>`,
  a `Sequence<T>` decoded as elements arrive. Iterating to the end closes the
  connection; stopping early does not, so wrap it in `use`. An event stream
  method takes `lastEventId` and `Streamed.lastEventId` reports the last `id:`
  seen; reconnecting is the caller's job. A `bytes(...)` response returns an
  `InputStream`.
- **Uploads.** A file part is an `UploadedFile`, and a raw or `ndjson`
  request body is an `InputStream` or a `Sequence`; each is streamed into the
  request rather than held in memory.
- **Operation servers.** An endpoint that declares `servers(...)` is sent to
  that host whatever `baseUrl` the client was given.
- **Webhooks.** A `webhook(...)` becomes a sender, `orderPlaced(url, body,
  xSignature)`, with the destination first because the document does not
  know it. See [Webhooks](reference.md#webhooks-the-calls-the-service-sends).

A response the endpoint did not declare throws `ApiCallFailed`, carrying the
status, the method, the path template and the body (capped at 8 KiB and marked
where it was cut). A body the codec cannot read throws it too, even at a
declared status, because a proxy's HTML 404 matches the status and nothing
else; the codec's exception is the `cause`. See
[docs/reference.md](reference.md#what-a-call-refuses-with).

### Payload types and codecs

The generated payload types are plain Kotlin classes; the `Codecs` you pass
reads and writes them. `codec` decides which annotations the file carries:

- **`jackson`** (the default). Only a sealed interface generated from a
  `oneOf` is annotated (`@JsonTypeInfo`, `@JsonSubTypes`), because no JSON
  library can tell from the Kotlin alone which property names the branch. A
  spec with no `oneOf` produces a file with no Jackson import. A file that has
  them compiles against `jackson-annotations`, which `pelican-jackson` brings.
- **`kotlinx`**. Every generated class and enum is `@Serializable`, because
  kotlinx.serialization has no reflective fallback, and a union also carries
  `@JsonClassDiscriminator` and `@SerialName`. The calling build needs the
  `org.jetbrains.kotlin.plugin.serialization` compiler plugin and
  `kotlinx-serialization-json`. `pelican-kotlinx`, the matching `Codecs`, is on
  `main` and not yet released.

Choose the library whose `Codecs` the caller will pass. `endpoints` entries
take the same setting; see [docs/importing.md](importing.md).

## What the caller needs

To compile and run, the generated file needs:

- **`pelican-core`**, for `ClientTransport`, `ClientRequest`, `Codecs` and the
  other types it imports. Its one runtime dependency is `jackson-core`.
- **A `Codecs`** on the caller's classpath, passed to the constructor:
  `JacksonCodecs` from `pelican-jackson`, or your own.
- **A `ClientTransport`**: one adapter module, or your own implementation.
  See [Where the requests go](#where-the-requests-go).
- **`kotlinx-coroutines-core`**, only for a `suspending` client.
- **A JDK and Kotlin compiler that can read Pelican's jars.** The 1.0.0
  library modules are compiled to JDK 25 bytecode with Kotlin 2.4.20, so the
  caller runs on JDK 25 or newer. On `main`, not yet released, the floor is
  JDK 21. Pelican is not tested with older Kotlin
  compilers.

The generated file itself only uses the Kotlin standard library, the JDK and
those modules.

What stays your job after the first run:

- **Regenerate** when the descriptions change: on every build if `outputDir`
  is inside `build/`, by running `generate<Name>Client` and committing the
  result otherwise. For a committed client, `check<Name>Client` in `check` is
  what tells you it is due.
- **Keep the adapter and its HTTP library** on the classpath, at versions it
  supports (for `pelican-client-pekko`, the Pekko modules below).
- **Do not edit the file.** The check compares it byte for byte, and the next
  regeneration replaces it.
- **Exclude it from linters** that scan the source root. The example's
  `detekt` configuration excludes its generated packages.

## Where the requests go

The generated code never names an HTTP library. It builds a `ClientRequest`
(method, encoded URL, headers, a per-request timeout, and a body that is
empty, text or a stream) and hands it to a `ClientTransport`:

```kotlin
fun interface ClientTransport {
    fun send(request: ClientRequest): CompletionStage<ClientResponse>
}
```

Both types are in `pelican-core`. `ClientTransport.default()`, the
constructor's default, finds an adapter through `ServiceLoader`. With none on
the classpath it fails, naming the modules to add; with more than one it
refuses to choose, and you pass one explicitly.

| Adapter | Status | Constructor |
|---|---|---|
| `pelican-client-pekko` | in 1.0.0 | `PekkoHttpTransport(system)` sends on an actor system you already run; `PekkoHttpTransport()` uses one the module starts on first use (daemonic). |
| `pelican-client-jetty` | on `main`, not yet released | `JettyHttpTransport(httpClient)` borrows a Jetty `HttpClient` you own; `JettyHttpTransport()` starts its own on the first request and stops it on `close()`. |
| `InMemoryClientTransport(api)` | in 1.0.0, in `pelican-core` | Calls an `Api` in the same process; see [Calling it without a socket](#calling-it-without-a-socket). |

`pelican-client-pekko` declares Pekko as `compileOnly`, so the calling build
declares it, with the Scala suffix it uses:

```kotlin
dependencies {
    implementation("io.github.matthewjones372:pelican-client-pekko:1.0.0")
    implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-stream_2.13")
    implementation("org.apache.pekko:pekko-http_2.13:1.4.1")
}
```

```kotlin
import example.generated.OrdersClient
import io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import org.apache.pekko.actor.typed.ActorSystem

fun ordersClient(system: ActorSystem<*>) =
    OrdersClient("https://orders.internal", JacksonCodecs, PekkoHttpTransport(system))
```

See [On Pekko HTTP](reference.md#on-pekko-http). A borrowed Jetty `HttpClient`
keeps its owner's protocol handlers, which matters for an endpoint that
declares a 401; [On Jetty](reference.md#on-jetty-1) has the fix.

Any other HTTP client works by implementing `ClientTransport` and passing it
as the third argument, which keeps that client's pooling, metrics and tuning.
A file part or a raw body arrives as a `ClientRequest.Body.Streaming` the
transport opens and drains, and a streamed response is handed back as an
unread stream that `Streamed<T>` decodes.

## Presenting a credential

The `headers` argument is called once per request, so a token that expires is
read fresh each time:

```kotlin
val client = OrdersClient(
    "https://orders.internal",
    JacksonCodecs,
    headers = { mapOf("Authorization" to "Bearer ${tokens.current()}") },
)
```

Those headers go on every call the client makes and on no webhook it sends:
a webhook goes to a subscriber's URL, not to the API. A credential the
document declares on an endpoint is a method parameter instead (see
[Calling it](#calling-it)).

## How long a call may take

`OrdersClient(..., timeout = Duration.ofSeconds(30))` is the deadline every
call is built with (30 seconds by default). It reaches the transport as
`ClientRequest.timeout`:

| Transport | What the deadline bounds |
|---|---|
| `pelican-client-pekko` | the response head; the body streams on after it |
| `pelican-client-jetty` | the response head; the body streams on after it |
| `InMemoryClientTransport` | nothing |

A streamed call (`ndjson`, `sse`, `jsonArray` or `bytes`) is sent with no
deadline, because an event stream is meant to stay open. Bound one by taking
what you need and closing it (`use { it.take(100).toList() }`), or with the
idle timeout of the HTTP client you hand over.

## Calling it without a socket

`InMemoryClientTransport` is a `ClientTransport` over an `Api` value, so a
generated client can call a service in the same process. With the example's
`ordersApi()` and its generated `OrdersClient`:

```kotlin
val client = OrdersClient("http://orders.test", JacksonCodecs, InMemoryClientTransport(ordersApi()))

client.getUser(1L) shouldBe Outcome.Ok(User(1L, "Ada Lovelace", "ada@example.com"))
```

No port is bound. Routing, input decoding, the `Api`'s filters, the handlers
and response building are the same functions a bound server runs.

Two kinds of call cannot cross, and both throw `UnsupportedInMemoryCall`
naming the reason: a `bytes(...)` request body, whose handle is the backend's
own type; and a streamed response a handler produced as something other than a
`Sequence`, such as a Pekko `Source`. Test those against a bound server.

## Blocking or suspending

By default the methods block: they join the transport's stage and unwrap the
`CompletionException`, so a caller catches what the transport raised.
`callStyle.set("suspending")` generates the same class with `suspend`
methods instead. From `example/build.gradle.kts`:

```kotlin
pelican {
    clients {
        create("ordersSuspending") {
            specClass.set("example.GenerateOpenApiKt")
            specFunction.set("ordersSpec")
            packageName.set("example.generated.suspending")
            callStyle.set("suspending")
        }
    }
}
```

With `OrdersClient`, `CreateOrder`, `Order` and `Outcome` imported from
`example.generated.suspending`:

```kotlin
suspend fun place(client: OrdersClient, key: String): Order? =
    when (val result = client.placeOrder(1L, CreateOrder("anvil"), key)) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> null
    }
```

Method names, parameters, payload types and failures are identical. A file
has one style, not both, so a coroutine cannot call a blocking method by
mistake and a blocking caller does not need kotlinx.coroutines. A caller that
needs both generates two entries, as the example does.

The suspending file imports `kotlinx.coroutines`, so the caller needs
`org.jetbrains.kotlinx:kotlinx-coroutines-core`. A coroutine cancelled while
a call is outstanding cancels the exchange. A whole body is read on
`Dispatchers.IO`; a `Streamed<T>` is read by whoever iterates it, so iterate
one inside `withContext(Dispatchers.IO)`.

## Retrying

Nothing retries unless you wrap the transport, using `retrying()` from
`pelican-core`:

```kotlin
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.retrying

val client = OrdersClient(
    "https://orders.internal",
    JacksonCodecs,
    ClientTransport.default().retrying(),          // or retrying(retryPolicy { ... })
)
```

The default policy sends a request at most three times, waits 100 ms and then
200 ms (each wait up to half randomised, capped at 2 s), and only for GET,
HEAD, PUT, DELETE and OPTIONS. It retries a 408, 429, 502, 503 or 504
response, or an `IOException` from the transport (a refused or reset
connection, a timeout). It does not retry a 500, a POST, or a streamed request
body. A `Retry-After` in seconds replaces the computed wait where it is
longer, and one asking for more than 10 seconds ends the retries and returns
that response. Each of these is a setting in the `retryPolicy { }` block; the
reasoning for each default is in
[docs/reference.md](reference.md#retrying-and-what-is-safe-to-retry).
