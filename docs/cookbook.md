# Cookbook

Recipes, one problem each, with the imports, the description, the handler and,
where it matters, the test. The [reference manual](reference.md) has the
reasoning and the edge cases; each recipe links to the part it shortens.

Two recipes are verbatim copies of files the example module compiles and tests
on every build, and say so. The rest are cut from the example's
[`Endpoints.kt`](../example/src/main/kotlin/example/Endpoints.kt),
[`OrdersApi.kt`](../example/src/main/kotlin/example/OrdersApi.kt) and the other
files under `example/src`. Names a recipe uses without declaring (`User`,
`Order`, `CreateOrder`, `Store`, `apiKey`, `newOrder`) are the example's own,
from [`Model.kt`](../example/src/main/kotlin/example/Model.kt) and
`Endpoints.kt`; in a service of your own they are your types.

The recipes share one set of Gradle dependencies:

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("io.github.matthewjones372:pelican-bom:$pelicanVersion"))
    implementation("io.github.matthewjones372:pelican-jetty")         // brings pelican-core and Jetty's server
    implementation("io.github.matthewjones372:pelican-jackson")       // JacksonCodecs
    implementation("io.github.matthewjones372:pelican-jetty-docs")    // startWithDocs and Swagger UI
    testImplementation("io.github.matthewjones372:pelican-test-jetty") // inMemory(); brings pelican-test
}
```

`pelicanVersion` is the release you pin, `val pelicanVersion = "1.2.0"`. To
serve on Pekko HTTP instead, use `pelican-pekko`, `pelican-pekko-docs` and
`pelican-test-pekko`, plus the Pekko dependencies your service names itself,
as in [Getting started](../README.md#getting-started). Every binder below
exists under the same name in `io.github.matthewjones372.pelican.pekko`; a
streaming handler returns a Pekko `Source` where Jetty takes a `Sequence`.
[On Pekko](#on-pekko) is the first recipe on Pekko.

A recipe needing a module beyond these carries its own `dependencies` block.
Imports are written out one name per line, so the import block says where each
piece lives; the build refuses wildcard imports.

---

## The whole thing, once

The smallest service that runs: one endpoint, served with its OpenAPI document
and Swagger UI. This is
[`example/hello/FirstEndpoint.kt`](../example/src/main/kotlin/example/hello/FirstEndpoint.kt),
verbatim apart from the punctuation of the `println`, and it is compiled and
tested on every build.

```kotlin
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.docs.startWithDocs
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pathParam

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
    println("Listening on ${server.baseUrl}, docs at ${server.baseUrl}/api-docs")
}
```

Run it from a clone with `./gradlew :example:runFirstEndpoint`. Its test, the
Jetty half of
[`FirstEndpointTest.kt`](../example/src/test/kotlin/example/hello/FirstEndpointTest.kt):

```kotlin
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.test.jetty.inMemory
import io.github.matthewjones372.pelican.test.shouldBuild
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FirstEndpointTest {
    private val app = greetings().inMemory(Handlers.onVirtualThreads)

    @Test
    fun `answers the greeting, by endpoint value`() {
        app.call(greet, "world") shouldBe Greeting("Hello, world!")
    }

    @Test
    fun `is served at the URL its callers were given`() {
        app.request(greet, "world") shouldBuild "GET /hello/world"
    }
}
```

Two tests, two jobs. The first is behaviour and must survive a rename; the
second is the contract and must break on one. See
[Pinning the URL](reference.md#pinning-the-url).

---

## Typed inputs

Read path, query and header parameters as typed handler arguments. Declare the
inputs on `endpoint(...)` and the handler's signature is fixed by them; listing
them there also registers them for decoding and for the document.

```kotlin
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.queryParam

val userId = pathParam<Long>("userId")
val limit  = queryParam<Int>("limit").default(25)
val status = queryParam<OrderStatus>("status").optional()
val trace  = headerParam<String>("X-Trace-Id").optional()

val listOrders = endpoint(userId, limit, status, trace) {
    get("users" / userId / "orders")
    json<List<Order>>()
}

listOrders handledNow { (id, max, status, trace) ->
    //                   Long  Int  OrderStatus?  String?
    Store.orders(id, max, status)
}
```

The typed overloads take up to six inputs. Past that, use the lens form,
`endpoint(lensInputs) { }` with `query(...)` and `header(...)` inside: the
handler receives `Params` and reads by key, and an undeclared key throws at
request time instead of failing to compile. `searchOrders` in `Endpoints.kt` is
one.

Two inputs of the same type can be swapped without the compiler noticing. Give
them distinct types:

```kotlin
import io.github.matthewjones372.pelican.LongCodec
import io.github.matthewjones372.pelican.map
import io.github.matthewjones372.pelican.pathParam

@JvmInline value class UserId(val value: Long)

val userId = pathParam("userId", LongCodec.map(::UserId, UserId::value))
```

It still documents as `integer/int64`; only Kotlin sees the wrapper. See
[Typechecked endpoints](reference.md#typechecked-endpoints).

---

## A constraint that reaches the document

Reject out-of-range input before the handler runs, and publish the same rule in
the schema. Refining a codec does both:

```kotlin
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.between
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.queryParam

val limit = queryParam("limit", IntCodec.between(1, 100), description = "How many to return").default(20)
```

`?limit=0` is then a 400 that the document already predicted. For your own
rules, `PlainCodec.mapOrFail` validates as it parses. See
[Refined inputs](reference.md#refined-inputs).

---

## Failures a caller was promised

Return a documented error (a 404, a 401) as a typed value instead of throwing.
Declare the failure as a value and list it on the output with `orFail`: it
joins the endpoint's type, and the handler has to produce it.

```kotlin
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jetty.handledOrFail
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail

val noSuchUser = errorJson<ApiError>(404, "No user with that id")
val badApiKey  = errorJson<ApiError>(401, "Missing or bad API key")

val getUser = endpoint(userId) {
    get("users" / userId)
    json<User>() orFail noSuchUser
}                                  // Endpoint<Long, Outcome<ApiError, User>>

getUser handledOrFail { id ->
    Store.user(id)?.let { ok(it) } ?: noSuchUser(ApiError(404, "No user $id"))
}
```

Several failures, one handler:

```kotlin
val placeOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders")
    json<Order>(status = 201).orFail(badApiKey, noSuchUser)
}

placeOrder handledOrFail { (id, key, req) ->
    when {
        key != "let-me-in"     -> badApiKey(ApiError(401, "Bad API key"))
        Store.user(id) == null -> noSuchUser(ApiError(404, "No user $id"))
        else                   -> ok(Store.create(id, req))
    }
}
```

The status comes from the declaration, not from the payload's type, which is
why two failures may carry the same type.

Do not wrap the body in `runCatching` and map the result into a failure: that
gives the service two error models, one declared and one accidental.
[`example/shop`](../example/src/main/kotlin/example/shop/Shop.kt) shows the
alternative, a domain error type that is also the payload of the declared
failures. See [Declared failures](reference.md#declared-failures).

---

## More than one success

Answer one request with one of two successful responses: here, 201 when the
order is placed now, 202 when it is queued. Declare both outputs as values and
the handler names the one it is producing. From `Endpoints.kt` and
`OrdersApi.kt`.

```kotlin
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jetty.handledOneOf
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.of
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.responseHeader

val orderAt = responseHeader<String>("Location", description = "Where the placed order lives")

val orderPlaced = json<Order>(status = 201, orderAt)
val orderQueued = json<Queued>(status = 202)

val submitOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders" / "submit")
    orderPlaced or orderQueued orFail badApiKey
}

submitOrder handledOneOf { (id, key, req) ->
    when {
        key != "let-me-in" -> badApiKey(ApiError(401, "Bad API key"))

        req.quantity > Store.BURST_LIMIT ->
            orderQueued(Queued("ticket-$id-${req.item}", position = req.quantity))

        else -> {
            val order = Store.create(id, req)
            orderPlaced(order, orderAt of "/users/$id/orders/${order.id}")
        }
    }
}
```

`Location` is declared on the 201 only, so the document does not promise it on
the 202. See
[More than one successful response](reference.md#more-than-one-successful-response).

---

## Response headers

Send headers that the document lists and the handler sets by key rather than by
string:

```kotlin
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.responseHeader

val location   = responseHeader<String>("Location", "Where the new order lives")
val rateLeft   = responseHeader<Int>("X-RateLimit-Remaining")
val retryAfter = responseHeader<Long>("Retry-After").optional()

val placeOrder = endpoint(newOrder) {
    post("orders")
    emits(location, rateLeft)
    errorResponse(429, "Slow down", retryAfter)
    json<Order>(status = 201)
}

placeOrder handledNow { req ->
    val order = Store.create(1, req)
    setHeader(location, "/orders/${order.id}")
    setHeader(rateLeft, quota.remaining)
    order
}
```

`emits(...)` documents the headers on every success response. To declare one on
a single status, pass it to that output, as `orderAt` is passed to the 201
above. See [Response headers](reference.md#response-headers).

---

## A form body

Accept an `application/x-www-form-urlencoded` body as a typed value:

```kotlin
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.formBody
import io.github.matthewjones372.pelican.jetty.handledNow

data class SignIn(val user: String, val remember: Boolean, val visits: Int)
data class Session(val user: String, val remember: Boolean, val visits: Int)

val credentials = formBody<SignIn>(description = "The sign-in form")

val signIn = endpoint(credentials) {
    post("sign-in")
    json<Session>()
}

signIn handledNow { form -> Session(form.user, form.remember, form.visits) }
```

A test sends it as a caller would. The form is encoded against its published
schema, so nothing in the test knows the wire format:

```kotlin
app.call(signIn, SignIn("ada", remember = true, visits = 3))
```

`formBody<T>() or jsonBody<T>()` accepts either encoding, as `orderForm` in
`Endpoints.kt` does. See [Form bodies](reference.md#form-bodies).

---

## A file upload

Accept a multipart upload with a text field and a file, without holding the
file in memory:

```kotlin
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.filePart
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.nonEmpty
import io.github.matthewjones372.pelican.textPart

data class Imported(val caption: String, val filename: String?, val lines: Int)

val caption = textPart("caption", StringCodec.nonEmpty(), description = "What to call it")
val upload  = filePart("file", contentType = "text/csv", description = "One order per line")

val importOrders = endpoint(caption, upload) {
    post("orders" / "import")
    json<Imported>(status = 201)
}

importOrders handledNow { (caption, file) ->      // String, UploadedFile
    Imported(caption, file.filename, file.stream().bufferedReader().useLines { it.count() })
}
```

`file.stream()` reads the part straight off the request body; consume it before
the handler returns. The multipart envelope is parsed by core rather than by
the backend, so a part decodes to the same value on either server. A test
passes the same `UploadedFile` the handler receives:

```kotlin
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.UploadedFile

app.call(importOrders, In2("March", UploadedFile("orders.csv", "text/csv", stream)))
```

`bufferedFile(...)` is the in-memory alternative for a small part, with a size
limit. See [Multipart uploads](reference.md#multipart-uploads).

---

## Streaming

Send a response as it is produced (NDJSON, server-sent events or a chunked JSON
array) instead of building it in memory. The description is the same on every
backend; the handler returns the backend's own stream type.

```kotlin
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint

val streamOrders = endpoint(userId, limit) {
    get("users" / userId / "orders" / "stream")
    ndjson<Order>()
}
```

```kotlin
// pelican-jetty: a Sequence<T>, pulled one element per write
import io.github.matthewjones372.pelican.jetty.streamedNow

streamOrders streamedNow { (id, max) -> Store.orders(id, max, status = null).asSequence() }
```

```kotlin
// pelican-pekko: a Source<T, NotUsed>
import io.github.matthewjones372.pelican.pekko.streamedNow
import org.apache.pekko.stream.javadsl.Source

streamOrders streamedNow { (id, max) -> Source.from(Store.orders(id, max, status = null)) }
```

The wire format is rendered by core either way. `sse<T>()` and `jsonArray<T>()`
are the other two shapes; `streamedOrFail` streams with a declared failure, as
`streamOrders` in `OrdersApi.kt` does. Collect any of them in a test with
`app.collect(...)`. See
[How streaming stays backend-agnostic](reference.md#how-streaming-stays-backend-agnostic).

---

## Security schemes

Publish how callers authenticate, so Swagger UI and generated clients know what
to send:

```kotlin
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.oauth2AuthorizationCode

val oauth = oauth2AuthorizationCode(
    authorizationUrl = "https://id.example.com/oauth2/authorize",
    tokenUrl = "https://id.example.com/oauth2/token",
    scopes = mapOf(
        "orders:read"  to "Read orders",
        "orders:write" to "Place and cancel orders",
    ),
)

val placeOrder = endpoint(userId, newOrder) {
    post("users" / userId / "orders")
    security(oauth, "orders:write")     // a scope the scheme never declared fails here
    json<Order>(status = 201)
}
```

A default for the whole API, and an endpoint that opts out:

```kotlin
import io.github.matthewjones372.pelican.requires

api(routes, codecs = JacksonCodecs) { security = listOf(oauth.requires("orders:read")) }

val ping = endpoint {
    get("ping")
    noSecurity()        // documented as public: `security: []`
    text()
}
```

`security(...)` documents the requirement; it does not check a credential. To
enforce it, declare a caller with `authenticatedBy(...)` (and `pelican-oidc` for
JWTs), described in [Who is calling](reference.md#who-is-calling), or check it
in a [filter](#filters), as
[`example/secured`](../example/src/main/kotlin/example/secured/SecuredReports.kt)
does.

---

## Filters

Run code around every handler: authentication, rate limiting, a timer. Filters
compose outermost first, narrow with `onlyWhen`, and reject by throwing. From
[`example/filters/FilterExample.kt`](../example/src/main/kotlin/example/filters/FilterExample.kt):

```kotlin
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.attribute
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.unauthorized

data class Caller(val subject: String, val plan: String)

val caller = attribute<Caller>("caller")
val authorization = headerParam<String>("Authorization", description = "Bearer token").optional()

val requireToken: Filter = before { p ->
    val presented = p[authorization]?.removePrefix("Bearer ")
    p[caller] = tokens[presented] ?: unauthorized("Present a bearer token")
}

val timing = Filter { params, next ->
    val started = System.nanoTime()
    next(params).thenApply { it.also { metrics.record(params.endpoint, System.nanoTime() - started) } }
}

api(routes, JacksonCodecs) {
    filter(timing)
    filter(requireToken)
}
```

A filter runs after the inputs are decoded, so `p[authorization]` is the typed
header of an endpoint that declares it. A handler reads what a filter stored by
the same key; `this` is the request's `Params`:

```kotlin
getReport handledNow { (id, _) -> Report(id, "Q3", visibleTo = this[caller].subject) }
```

`after { }` and `afterStatus { }` cover the log-what-happened case. See
[Filters](reference.md#filters).

---

## Health checks

Serve liveness and readiness probes for an orchestrator.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.matthewjones372:pelican-health")
}
```

```kotlin
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.health.diskSpace
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.health.jdbc
import io.github.matthewjones372.pelican.health.noDeadlockedThreads
import io.github.matthewjones372.pelican.jackson.JacksonCodecs

val health = health {
    live("threads") { noDeadlockedThreads() }
    ready("orders-db") { jdbc(dataSource) }
    ready("disk", critical = false) { diskSpace("/var/data", minFreeBytes = 1L shl 30) }
}

api(endpoints = health.endpoints + routes, codecs = JacksonCodecs)
```

`/health/live` runs the `live` checks and `/health/ready` runs both, each check
under its own timeout. The body is `application/health+json`: 200 for `pass` or
`warn`, 503 for `fail`. A non-critical check that fails is a `warn`. Any
lambda returning a `Status` is a check;
[`example/health/Warehouse.kt`](../example/src/main/kotlin/example/health/Warehouse.kt)
has one. See [Health checks](reference.md#health-checks).

---

## Testing, in three layers

Test behaviour, the URL contract, and the real socket with one typed client and
no path strings. Against the example's `ordersApi()`, from
[`ClientContractTest.kt`](../example/src/test/kotlin/example/ClientContractTest.kt):

```kotlin
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.In4
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.jetty.start
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.jetty.client
import io.github.matthewjones372.pelican.test.jetty.inMemory
import io.github.matthewjones372.pelican.test.shouldBuild
import io.kotest.matchers.shouldBe

val app = ordersApi().inMemory(Handlers.onVirtualThreads)

// 1. behaviour: typed in, typed out
val user: User = app.call(getUser, 1L)
val orders: List<Order> = app.collect(streamOrders, In4(1L, 7, null, null))
app.response(placeOrder, In3(1L, "wrong", CreateOrder("anvil", 1))).status shouldBe 401

// 2. the contract: the URL a caller holds, pinned as a literal
app.request(getUser, 1L) shouldBuild "GET /users/1"

// 3. the same suite, over a socket
class OverHttpContractTest : ClientContractTest() {
    private lateinit var server: PelicanServer

    override fun open(): ApiClient {
        server = ordersApi().start(port = 0)
        return server.client()
    }

    override fun shutDown() {
        app.close()
        server.stop()
    }
}
```

`ClientContractTest` is the example's abstract suite, written against
`ApiClient`; `InMemoryContractTest` runs it in memory and
`OverHttpContractTest` over a bound port. Keep layers 1 and 2 in different
files: behaviour tests should not break on a rename, and the contract test
should.

Golden files (`pelican-test-golden`) are a fourth layer: per-endpoint snapshots
that fail when a change breaks existing callers. A moved golden is the test
working; read the diff and decide whether the break was intended. See
[Golden testing](golden-testing.md) and [Testing](reference.md#testing).

---

## On Pekko

Serve the first recipe's `greet` on Pekko HTTP, or on an actor system the
service already runs. The description does not change; the binders,
`startWithDocs` and the dependencies do. This is
[`example/hello/FirstEndpointOnPekko.kt`](../example/src/main/kotlin/example/hello/FirstEndpointOnPekko.kt),
verbatim, run with `./gradlew :example:runFirstEndpointOnPekko`.

Pelican compiles against Pekko and ships none of it, so the build names the
Pekko it runs. Either Scala cross-build works: for Scala 3, write `_3` in place
of `_2.13` on every Pekko line.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.matthewjones372:pelican-pekko:1.2.0")       // brings pelican-core; compiles against Pekko
    implementation("io.github.matthewjones372:pelican-jackson:1.2.0")
    implementation("io.github.matthewjones372:pelican-pekko-docs:1.2.0")  // startWithDocs and Swagger UI
    implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-stream_2.13")
    implementation("org.apache.pekko:pekko-http_2.13:1.4.1")
    testImplementation("io.github.matthewjones372:pelican-test:1.2.0")
    testImplementation("io.github.matthewjones372:pelican-test-pekko:1.2.0") // inMemory() on Pekko
}
```

```kotlin
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow

fun greetingsOnPekko() = api(
    endpoints = listOf(greet handledNow { name -> Greeting("Hello, $name!") }),
    codecs = JacksonCodecs,
) {
    title = "Greetings"
    version = "1.0.0"
}

fun main() {
    val server = greetingsOnPekko().startWithDocs(port = 8080, docs = docs { docsPath = "/api-docs" })
    println("Listening on ${server.baseUrl}, docs at ${server.baseUrl}/api-docs")
}
```

A test reaches it with `greetingsOnPekko().inMemory("greetings")`, imported
from `io.github.matthewjones372.pelican.test.pekko`, which starts an actor
system of that name. Beyond the stream type (`Source<T, NotUsed>` where Jetty
takes a `Sequence<T>`), the differences are the lower-level entry points:
`toRoute(system)` returns a Pekko `Route` to concatenate with your own, and
`start(system, port = 8080)` serves on an `ActorSystem` you already run. The
reference's [backend chapter](reference.md#the-backend) lists every difference,
and [Versions](reference.md#versions) has the supported Pekko releases.

---

## Switching backend

Move a service between Jetty and Pekko HTTP. Descriptions name no server
library, so switching is the binder imports, the stream type of streamed
endpoints, and `start`; the descriptions, the document and the tests through
the typed client are unchanged.
[`example/backends`](../example/src/main/kotlin/example/backends) shows the
seam: `Greetings.kt` holds the descriptions, and `OnJetty.kt` and `OnPekko.kt`
are the whole of binding them, one with `Sequence`s and one with `Source`s.
`AllBackendsTest` runs one suite against both.

```bash
./gradlew :example:runBackends
```

---

## Where to go next

- [Reference manual](reference.md): the long form, with the reasoning.
- [Choosing](choosing.md): when something else is the better answer.
- [A whole service](a-whole-service.md): one service in one file, end to end.
- [The generated client](generated-client.md): what callers get.
- [Golden testing](golden-testing.md): catching a break in your own contract.
- [Importing](importing.md): an existing OpenAPI document, as descriptions.
- [What it costs](what-it-costs.md): the benchmarks.
