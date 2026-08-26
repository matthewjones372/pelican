# Cookbook

Complete recipes, in the order people need them. Each one is whole — imports,
description, handler, test — so it can be pasted and then edited, rather than
assembled from fragments.

Every snippet here is drawn from the compiled example or from
[the reference manual](reference.md), which is where the reasoning lives. This
page is the short answer; the reference is the long one.

The recipes share one set of Gradle dependencies, written once here:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC1")       // brings pelican-core, and Pekko itself, transitively
    implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC1")     // JacksonCodecs
    implementation("io.github.matthewjones372:pelican-pekko-docs:1.0.0-RC1")  // startWithDocs and Swagger UI
    testImplementation("io.github.matthewjones372:pelican-test:1.0.0-RC1")    // the typed test client
}
```

A recipe that needs a module beyond these says so in a comment on its first
line. Each recipe carries its own imports so it can be pasted whole, and every
import is written out in full — one line per name, so the import block is an
inventory of what the recipe uses and where each piece lives.

---

## The whole thing, once

The smallest service that runs. This is
[`example/hello/FirstEndpoint.kt`](../example/src/main/kotlin/example/hello/FirstEndpoint.kt),
verbatim, and it is compiled and tested on every build so it cannot rot.

```kotlin
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow

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
    println("Listening on ${server.baseUrl} — docs at ${server.baseUrl}/api-docs")
}
```

And its test, in full:

```kotlin
// build.gradle.kts: testImplementation("io.github.matthewjones372:pelican-test-pekko:1.0.0-RC1")
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.shouldBuild
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FirstEndpointTest {
    private val app = greetings().inMemory("first-endpoint")

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

Two tests, two different jobs. The first is behaviour and must survive a
rename; the second is the contract and must break on one. See
[Pinning the URL](reference.md#pinning-the-url).

---

## Typed inputs

Declare the inputs on `endpoint(...)` and the handler's signature is fixed by
them. No separate `query(...)` call — listing them did that.

```kotlin
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.handledNow
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

Overloads run to six inputs. Past that, drop to the lens form — `endpoint { }`
with `query(...)`/`header(...)`, handler receives `Params`, read by key — and
accept that an undeclared key throws at request time instead of failing to
compile.

**Two inputs of the same type can be swapped and the compiler will not notice.**
Give them distinct types:

```kotlin
@JvmInline value class UserId(val value: Long)
val userId = pathParam("userId", LongCodec.map(::UserId, UserId::value))
```

It still documents as `integer/int64`. Only Kotlin sees the wrapper.

---

## A constraint that reaches the document

Refining a codec narrows what is accepted *and* writes the constraint into the
schema. Both halves, or it is a lie in the contract:

```kotlin
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.between
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.queryParam

val limit = queryParam("limit", IntCodec.between(1, 100), description = "How many to return").default(20)
```

For your own rules, `PlainCodec.mapOrFail` validates as it parses. See
[Refined inputs](reference.md#refined-inputs).

---

## Failures a caller was promised

Declare the failure as a value, list it on the output, and it joins the
endpoint's type. The handler then has to *produce* it rather than throw it.

```kotlin
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pekko.handledOrFail

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
        key != expected        -> badApiKey(ApiError(401, "Bad API key"))
        Store.user(id) == null -> noSuchUser(ApiError(404, "No user $id"))
        else                   -> ok(Store.create(id, req))
    }
}
```

The status comes from the declaration, not from the payload's type — which is
why two failures may carry the same type.

**Do not** wrap the body in `runCatching` and map the result into a failure.
That produces two error models, one declared and one accidental. `example/shop`
exists to show the shape that goes wrong.

---

## More than one success

```kotlin
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.of
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pekko.handledOneOf
import io.github.matthewjones372.pelican.responseHeader

val orderAt = responseHeader<String>("Location", "Where the placed order lives")

val orderPlaced = json<Order>(status = 201, orderAt)
val orderQueued = json<Queued>(status = 202)

val submitOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders" / "submit")
    orderPlaced or orderQueued orFail badApiKey
}

submitOrder handledOneOf { (id, key, req) ->
    when {
        key != expected       -> badApiKey(ApiError(401, "Bad API key"))
        tooBigToPlaceNow(req) -> orderQueued(Queued(ticket(id), position = req.quantity))
        else -> {
            val order = Store.create(id, req)
            orderPlaced(order, orderAt of "/users/$id/orders/${order.id}")
        }
    }
}
```

---

## Response headers

Declared on the endpoint, set from the handler, published in the document:

```kotlin
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.pekko.handledNow
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
    val order = Store.create(req)
    setHeader(location, "/orders/${order.id}")
    setHeader(rateLeft, quota.remaining)
    order
}
```

---

## A form body

```kotlin
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.formBody
import io.github.matthewjones372.pelican.pekko.handledNow

data class SignIn(val user: String, val remember: Boolean, val visits: Int)

val credentials = formBody<SignIn>(description = "The sign-in form")

val signIn = endpoint(credentials) {
    post("sign-in")
    json<Session>()
}

signIn handledNow { form -> Session(form.user, form.remember, form.visits) }
```

Tests send it as a caller would — the form is encoded against its published
schema, so nothing in the test knows the wire format:

```kotlin
app.call(signIn, SignIn("ada", remember = true, visits = 3))
```

---

## A file upload

```kotlin
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.filePart
import io.github.matthewjones372.pelican.nonEmpty
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.textPart

val caption = textPart("caption", StringCodec.nonEmpty(), description = "What to call it")
val upload  = filePart("file", contentType = "text/csv", description = "One order per line")

val importOrders = endpoint(caption, upload) {
    post("orders" / "import")
    json<ImportResult>(status = 201)
}

importOrders handledNow { (caption, file) ->      // String, UploadedFile
    ImportResult(caption, file.filename, file.stream().bufferedReader().useLines { it.count() })
}
```

The multipart envelope is parsed by core rather than by the backend, so a part
decodes to the same value whichever server is underneath. Tests pass the same
`UploadedFile` the handler receives:

```kotlin
app.call(importOrders, In3("March", manifest, UploadedFile("orders.csv", "text/csv", stream)))
```

---

## Streaming

The description is backend-agnostic; the *binder* is the backend's own stream
type, so nothing in core knows what a `Source` is:

```kotlin
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint

val streamOrders = endpoint(userId, limit) {
    get("users" / userId / "orders" / "stream")
    ndjson<Order>()
}
```

```kotlin
// pelican-pekko — Source<T, NotUsed>
import io.github.matthewjones372.pelican.pekko.streamedNow
import org.apache.pekko.stream.javadsl.Source

streamOrders streamedNow { (id, max) -> Source.from(Store.orders(id, max)) }
```

The stream type is the backend's own: a binder demands it, and the wire format
— NDJSON, SSE, a chunked JSON array — is rendered by core either way.

`sse<T>()` and a chunked JSON array are the other two shapes. Collect them in a
test with `app.collect(...)`.

---

## Security schemes

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
    security(oauth, "orders:write")     // the scope, checked against the scheme
    json<Order>(status = 201)
}
```

A default for the whole API, and an endpoint that opts out:

```kotlin
api(routes, codecs = JacksonCodecs) { security = listOf(oauth.requires("orders:read")) }

val health = endpoint {
    get("health")
    noSecurity()        // documented as public: `security: []`
    text()
}
```

Pelican documents the scheme. It does not validate credentials — that is
[a deliberate refusal](reference.md#what-isnt-here), and a filter is where the
check goes.

---

## Filters

Composable, outermost-first, narrowable with `onlyWhen`, and able to reject by
throwing:

```kotlin
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.attribute
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.unauthorized

val caller = attribute<Caller>("caller")

val requireToken = before { p ->
    p[caller] = Tokens.check(p.request) ?: unauthorized("Present a bearer token")
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

A handler reads what a filter put there by the same key — `this` is the
request's `Params`:

```kotlin
fileReport handledNow { req -> Reports.file(this[caller].subject, req) }
```

---

## Testing, in three layers

```kotlin
// build.gradle.kts: testImplementation("io.github.matthewjones372:pelican-test-pekko:1.0.0-RC1")
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.In4
import io.github.matthewjones372.pelican.pekko.start
import io.github.matthewjones372.pelican.test.pekko.client
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.shouldBuild
import io.kotest.matchers.shouldBe

val app = ordersApi().inMemory()          // pelican-test-pekko

// 1. behaviour — typed in, typed out, no path strings
val user: User = app.call(getUser, 1L)
val orders: List<Order> = app.collect(streamOrders, In4(1L, 7, null, null))
app.response(placeOrder, In3(1L, "wrong", CreateOrder("anvil"))).status shouldBe 401

// 2. the contract — the URL a caller actually holds, pinned as a literal
app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"
app.request(listBookmarks, In2(20, Slug("streams"))) shouldBuild "GET /bookmarks?limit=20&tag=streams"

// 3. the same suite, over a socket
class OverHttpContractTest : ClientContractTest() {
    override fun open() = ordersApi().start(port = 0).client()
}
```

Keep 1 and 2 in different files. Behaviour tests should not break on a rename;
the contract test should. That split is the whole reason both exist.

Golden files (`pelican-test-golden`) are the fourth layer: per-endpoint
snapshots that fail when a change breaks existing callers. A moved golden is
the test working — read the diff and decide whether the break was intended.

---

## Switching backend

1.0 ships one: `pelican-pekko`. The seam it plugs into is still there, and
`example/backends` is what it looks like — the descriptions in `Greetings.kt`
name no server library, and `OnPekko.kt` is the whole of binding them:

```bash
./gradlew :example:runBackends
```

Switching is one import and the binders for streamed endpoints; everything else
— the descriptions, the document, the tests through the typed client — is
unchanged. The http4k and Ktor interpreters are complete and green on the
[`multi-backend`](https://github.com/matthewjones372/pelican/tree/multi-backend)
branch, where that suite runs against all three, and return after 1.0.

---

## Where to go next

- [Reference manual](reference.md) — the long form, with the reasoning.
- [Choosing](choosing.md) — when something else is the better answer.
- [A whole service](a-whole-service.md) — the example, end to end.
- [The generated client](generated-client.md) — what callers get.
- [Golden testing](golden-testing.md) — catching a break in your own contract.
- [Importing](importing.md) — an existing OpenAPI document, as descriptions.
- [What it costs](what-it-costs.md) — the benchmarks.
