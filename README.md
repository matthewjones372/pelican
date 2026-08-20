# Pelican

Type-safe HTTP for Kotlin. You describe an endpoint once, as a value. Pelican
turns that one description into the server route, the OpenAPI document, a test
client, and a generated Kotlin client for your callers.

Runs on Pekko HTTP, http4k or Ktor. Roughly what tapir is for Scala, scoped to
what Kotlin's type system can express without implicits.

```kotlin
val getBookmark = endpoint(bookmarkId) {
    get("bookmarks" / bookmarkId)
    summary = "Fetch one bookmark"
    json<Bookmark>() orFail bookmarkMissing
}

getBookmark handledOrFail { id ->                    // id: Long, already decoded
    Bookmarks.find(id)?.let { ok(it) }               // must be a Bookmark
        ?: bookmarkMissing(NoSuchBookmark(id, "No bookmark $id"))   // must be the declared 404
}
```

Change the path parameter's type, the response type or the declared error, and
the handler stops compiling.

## Why you might want this

**Your docs cannot drift.** The OpenAPI document is generated from the endpoint
values your server is built from, so there is no second source of truth to
forget. No annotations scanned at startup, no YAML written twice.

**Handlers get typed arguments.** A path parameter declared as `Long` arrives as
a `Long`. No `Params` bag, no casting, no `String.toLong()` in every handler.

**Bad input is rejected before your code runs.** Constraints live on the input
value, so the same regex that validates a request also appears as `pattern` in
the schema. Swagger UI refuses to send a request the server would reject.

**Errors are part of the signature.** `orFail` puts a failure in the endpoint's
type. The handler must produce it, and the caller's generated client gets a
sealed type to match on.

**Tests call endpoints, not URLs.** Rename a parameter and your tests stop
compiling instead of starting to 404.

**Swapping backends does not touch your descriptions.** Only the type a
streaming handler returns changes: `Source` on Pekko, `Sequence` on http4k,
`Flow` on Ktor.

## Contents

[Install](#install) · [A whole service, in one file](#a-whole-service-in-one-file) ·
[What the compiler catches](#what-the-compiler-catches) ·
[Describing endpoints](#describing-endpoints) · [Running a server](#running-a-server) ·
[Testing](#testing) · [A generated Kotlin client](#a-generated-kotlin-client) ·
[Backends](#backends) · [What it costs](#what-it-costs) · [Modules](#modules) ·
[Running the examples](#running-the-examples) · [Known limits](#known-limits)

The reference manual, with the reasoning behind each design decision, is
[docs/reference.md](docs/reference.md).

## Install

Not on Maven Central yet. `./gradlew publishToMavenLocal` installs all fourteen
modules at `dev.pelican:<module>:0.1.0-SNAPSHOT` with sources and javadoc, so
`mavenLocal()` or `includeBuild` both work today.

```kotlin
dependencies {
    implementation("dev.pelican:pelican-core:0.1.0-SNAPSHOT")
    implementation("dev.pelican:pelican-pekko:0.1.0-SNAPSHOT")
    implementation("dev.pelican:pelican-jackson:0.1.0-SNAPSHOT")
    testImplementation("dev.pelican:pelican-test:0.1.0-SNAPSHOT")
}
```

## A whole service, in one file

Models, inputs, endpoints, handlers, store, server and docs. This block lives in
the repo as [`ReadmeExample.kt`](example/src/main/kotlin/example/readme/ReadmeExample.kt),
so it compiles on every build. Run it with `./gradlew :example:runReadmeExample`.

```kotlin
import dev.pelican.*
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.pekko.*
import dev.pelican.pekko.docs.Docs
import dev.pelican.pekko.docs.startWithDocs
import org.apache.pekko.stream.javadsl.Source

// ---------------------------------------------------------------- 1. models

data class Bookmark(val id: Long, val url: String, val title: String, val tags: List<String> = emptyList())
data class CreateBookmark(val url: String, val title: String, val tags: List<String> = emptyList())
data class NoSuchBookmark(val id: Long, val message: String)

// ---------------------------------------------------------------- 2. inputs
//
// Declared once as values, and reused by the route, the decoder, the document
// and the test client. Refinements are enforced *and* documented.

@JvmInline value class Slug(val value: String)

val slug = StringCodec
    .matching(Regex("[a-z0-9-]{1,40}"), "a slug: lowercase letters, digits and dashes")
    .map(::Slug, Slug::value)
    .describedAs("A URL-safe tag", example = "streams")

val bookmarkId  = pathParam<Long>("bookmarkId", description = "The bookmark's id")
val limit       = queryParam("limit", IntCodec.between(1, 100), description = "How many to return").default(20)
val tag         = queryParam("tag", slug, description = "Only bookmarks with this tag").optional()
val apiKey      =
    headerParam("X-Api-Key", StringCodec.nonEmpty().describedAs("The caller's API key", example = "let-me-in"))
val newBookmark = jsonBody<CreateBookmark>(description = "The bookmark to save")

val bookmarkMissing = errorJson<NoSuchBookmark>(404, "No bookmark with that id")
val badKey          = errorJson<ApiError>(401, "Missing or bad API key")

// ------------------------------------------------------------- 3. endpoints
//
// This section imports `dev.pelican` and nothing else. No Pekko, no Jackson.
// These are descriptions: they do no work and hold no handler.

val getBookmark = endpoint(bookmarkId) {
    get("bookmarks" / bookmarkId)
    summary = "Fetch one bookmark"
    tag("bookmarks")
    json<Bookmark>() orFail bookmarkMissing
}

val listBookmarks = endpoint(limit, tag) {
    get("bookmarks")
    summary = "List bookmarks, newest first"
    tag("bookmarks")
    jsonArray<Bookmark>()            // chunked `[{...},{...}]`, flushed as produced
}

val createBookmark = endpoint(apiKey, newBookmark) {
    post("bookmarks")
    summary = "Save a bookmark"
    tag("bookmarks")
    json<Bookmark>(status = 201) orFail badKey
}

// ----------------------------------------------------------------- 4. store

object Bookmarks {
    private val saved = mutableListOf(Bookmark(1, "https://pekko.apache.org", "Pekko", listOf("streams")))
    private var nextId = 2L

    fun find(id: Long) = saved.firstOrNull { it.id == id }
    fun list(limit: Int, tag: Slug?) = saved.filter { tag == null || tag.value in it.tags }.take(limit)
    fun add(req: CreateBookmark) = Bookmark(nextId++, req.url, req.title, req.tags).also { saved += it }
}

// -------------------------------------------------------------- 5. handlers
//
// The only place that knows a stream is a `Source`. Every parameter arrives
// decoded and typed, in the order the endpoint declared it.

val routes = listOf(
    getBookmark handledOrFail { id ->                       // id: Long
        Bookmarks.find(id)?.let { ok(it) }
            ?: bookmarkMissing(NoSuchBookmark(id, "No bookmark $id"))
    },

    listBookmarks streamedNow { (max, tag) ->               // max: Int, tag: Slug?
        Source.from(Bookmarks.list(max, tag))
    },

    createBookmark handledOrFail { (key, req) ->            // key: String, req: CreateBookmark
        if (key != "let-me-in") badKey(ApiError(401, "Bad API key"))
        else ok(Bookmarks.add(req))
    },
)

// ---------------------------------------------------------------- 6. server

fun main() {
    val api = Api(routes, codecs = JacksonCodecs, title = "Bookmarks", version = "1.0.0")
    val server = api.startWithDocs(port = 8080, docs = Docs(docsPath = "/api-docs"))
    println("Listening on ${server.baseUrl} — docs at ${server.baseUrl}/api-docs")
}
```

Swagger UI is at `/api-docs` and the document at `/openapi.json`. Both are
generated from the values above.

```bash
curl localhost:8080/bookmarks/1          # {"id":1,"url":"https://pekko.apache.org",...}
curl localhost:8080/bookmarks/42         # 404 {"id":42,"message":"No bookmark 42"}
curl 'localhost:8080/bookmarks?limit=0'  # 400, naming the constraint it broke
open localhost:8080/api-docs             # Swagger UI
```

---

# What the compiler catches

Each of these came from feeding the mistake to the compiler:

```
+(getUser handledNow { id: String -> ... })
  e: Argument type mismatch: actual 'Function1<String, User>', expected 'Function1<Long, User>'

+(watchOrders streamedNow { (_, max) -> Source.single("not a tick") })
  e: Return type mismatch: expected 'Source<Tick, NotUsed>', actual 'Source<String, NotUsed>'

+(getBookmark handledOrFail { id -> Bookmarks.find(id)!! })
  e: Return type mismatch: expected 'Outcome<NoSuchBookmark, Bookmark>', actual 'Bookmark'
```

Mismatches the type system cannot see are checked when the endpoint value is
constructed, at class-init rather than on the first request:

```
GET /things declares path parameter 'stray', but the path is /things
```

---

# Describing endpoints

## Inputs and validation

The `slug` and `limit` values above carry their constraints with them, so the
server rejects on the same rule the document publishes:

```json
{ "name": "tag", "in": "query", "required": false,
  "description": "Only bookmarks with this tag",
  "example": "streams",
  "schema": { "type": "string", "pattern": "[a-z0-9-]{1,40}" } }
```

Swagger UI reads those constraints and refuses to send a request the server
would reject. A request that gets through anyway is a 400 before any handler
runs:

```
GET /bookmarks?limit=0
{"status":400,"error":"Invalid parameter","detail":"Cannot decode '0' for 'limit': expected a value between 1 and 100"}

GET /bookmarks?tag=NOT%20A%20SLUG
{"status":400,"error":"Invalid parameter","detail":"Cannot decode 'NOT A SLUG' for 'tag': expected a slug: lowercase letters, digits and dashes"}
```

Built in, each emitting its own schema facet: `nonEmpty()`, `nonBlank()`,
`minLength`, `maxLength`, `matching(regex)`, `atLeast`, `atMost`, `between`,
`positive()`. Ready-made codecs cover `String`, `Int`, `Long`, `Double`,
`Boolean`, `UUID`, `Instant`, `LocalDate`, `LocalDateTime`, `URI`,
`NonEmptyString` and any Kotlin `enum`.

Your own types take three lines and validate on the way in:

```kotlin
@JvmInline value class Email(val value: String)

val email = StringCodec.mapOrFail(
    expected = "an email address",
    facets = jsonObj { "format" to "email" },
    decode = { raw -> if ("@" in raw) Email(raw) else null },
    encode = Email::value,
)
```

## Declared failures

`orFail` puts the failure in the endpoint's type, so the handler has to produce
it and the response body is the type the document promised:

```
GET /bookmarks/9999
404 {"id":9999,"message":"No bookmark 9999"}
```

An endpoint can declare several. The handler names which one it is returning,
so two failures can share a payload type under different statuses:

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

Give the failures a sealed supertype and that `when` is exhaustive too.

## Streaming

```kotlin
ndjson<Order>()             // one JSON document per line
sse<Tick>(eventName = "order")
jsonArray<Order>()          // `[{...},{...}]`, flushed as produced
bytes()                     // opaque, never buffered
```

Handlers return a stream and back-pressure runs from the socket to the source.
The test suite throttles a source and fails if the first frame does not arrive
well before the last, so a response that quietly buffers is caught as a bug.

## Cookies, forms and uploads

These are inputs like any other, and take `optional()`, `default(v)`, codecs,
refinements and 400s just as a query parameter does:

```kotlin
val locale  = cookieParam<String>("locale").default("en")     // in: cookie
val form    = formBody<SignIn>()                              // application/x-www-form-urlencoded
val caption = textPart("caption", StringCodec.nonEmpty())     // multipart/form-data
val upload  = filePart("file", contentType = "text/csv")

val importOrders = endpoint(locale, caption, upload) {
    post("orders" / "import")
    json<ImportResult>(status = 201)
}

importOrders handledNow { (locale, caption, file) ->          // String, String, UploadedFile
    ImportResult(caption, file.stream().bufferedReader().useLines { it.count() }, locale)
}
```

A form carries strings and nothing else, so what `visits=3` means comes from the
schema published for the body type. That is what makes a form body decode
identically under Jackson and under kotlinx.serialization, which coerce
differently when left to themselves.

`file.stream()` is the request's own body, positioned at the part's first byte
and stopping at its boundary. Nothing buffers an upload, which brings one
constraint: **the file must be the last part on the wire**, since reading stops
there. A text part sent after it is a 400 that says so, and an HTML form
satisfies the rule by putting its `<input type="file">` last.

## Response headers

```kotlin
val location = responseHeader<String>("Location", "Where the new order lives")

val placeOrder = endpoint(newOrder) {
    post("orders")
    emits(location)
    json<Order>(status = 201)
}

placeOrder handledNow { req ->
    val order = Store.create(req)
    setHeader(location, "/orders/${order.id}")   // `this` is the request's Params
    order
}
```

Declaring the header puts it in the document with its schema, its description
and whether it is always sent, and it is also the only thing `setHeader` will
accept: passing an undeclared header throws rather than shipping an undocumented
one. `Retry-After` on a declared 429 goes out through
`errorResponse(429, "...", retryAfter)`.

Every handler lambda has the request's `Params` as its receiver whatever its
input style, so a typed handler reaches `setHeader` without giving up its typed
inputs.

---

# Running a server

## Filters

```kotlin
Api(routes, JacksonCodecs, filters = listOf(requireToken, rateLimit))
```

A filter runs around every handler, outermost first, and sees the request with
its inputs already decoded. Rejecting is throwing: `unauthorized()`,
`forbidden()`, `tooManyRequests(retryAfterSeconds = 30)`, so a refusal is
rendered by the code that renders every other failure, on all three backends.
What the filter works out goes into an attribute:

```kotlin
val caller = attribute<Caller>("caller")

val requireToken = before { p ->
    p[caller] = Tokens.check(p) ?: unauthorized("Present a bearer token")
}

getReport handledNow { id -> Reports.visibleTo(p[caller], id) }
```

`:example:runSecured` takes this to its conclusion: one filter that reads
`endpoint.security`, the same list that drew the padlock in Swagger UI, and holds
the caller to it. Add an endpoint with `security(idp, "reports:admin")` and it is
covered before it is bound, with no second list to keep up to date.

## Unhandled exceptions

```
500 {"status":500,"error":"Internal server error","detail":"Reference: f3ef2bdef43b"}
```

```
ERROR dev.pelican.pekko - Unhandled failure in POST /reports [ref f3ef2bdef43b]
java.sql.SQLException: connection to db-primary.internal:5432 refused
  ...
```

An exception message is written for whoever is debugging and may name a table, a
host, a query or a file, so the caller gets an opaque reference and the log gets
the stack trace, with the reference in both so they join up. Set
`exposeInternalErrors = true` for a local run, or `onServerError` to add the
fields your log aggregator wants. Declared failures are unaffected: a 404 you
described still carries the payload you described.

## Size limits and startup checks

```kotlin
Api(
    routes, JacksonCodecs,
    maxBodyBytes = 2 * 1024 * 1024,   // default 8 MiB; a bigger body is a 413
    covers = allOrderEndpoints,       // every one of these must be bound
)
```

An unbounded request body is a way to run a service out of memory with one
request, so the limit has a default. Oversized bodies are refused before any
codec sees them, on all three backends; a `rawBody()` stream is exempt because
nothing holds it whole.

`covers` closes the gap a list leaves open. Hand it the same endpoint list the
spec is built from and forgetting to bind one is a startup failure rather than a
documented endpoint that answers 404. Binding two handlers to the same route,
where the second could never be reached, fails at startup with no opting in at
all.

## CORS

```kotlin
Api(routes, JacksonCodecs, cors = cors("https://app.example.com"))
```

That is the whole configuration. The methods a preflight allows are the methods
declared for that path. The request headers it allows are the ones those
endpoints declare, plus `Content-Type` where they take a body and the credential
header their security scheme names:

```
OPTIONS /echo · Origin: https://app.example.com · Access-Control-Request-Method: POST

204 · Allow-Origin: https://app.example.com
      Allow-Methods: POST
      Allow-Headers: X-Trace-Id, Content-Type
```

`X-Trace-Id` is in that answer because one endpoint declares a header of that
name, so a browser gains permission to send it the moment it is described. The
headers go on error responses too, so a browser shows your 400 instead of a bare
network error.

## Choosing a JSON library

```kotlin
Api(routes, codecs = JacksonCodecs)      // Jackson + swagger-core schemas
Api(routes, codecs = KotlinxCodecs)      // kotlinx.serialization
Api(routes, codecs = JacksonCodecs(myObjectMapper))
```

Descriptions carry a `KType` and nothing else, no serializer and no mapper, so
swapping libraries touches no endpoint. That the two produce the *same document*
is a test, over models covering defaults, nullability (including inside a `List`
or a `Map`, where erasure means only the Kotlin type still knows), enums, maps,
nesting and recursion. The two shapes where they disagree are named in
[docs/reference.md](docs/reference.md#what-isnt-here).

---

# Testing

`pelican-test` interprets the same descriptions a third way, as a client.

```kotlin
val app = bookmarksApi().inMemory()          // no socket; or .start().client() for a real one

val bookmark: Bookmark = app.call(getBookmark, 1L)
val tagged:  List<Bookmark> = app.collect(listBookmarks, In2(20, Slug("streams")))

app.outcome(getBookmark, 9_999L) shouldBeError NoSuchBookmark(9_999L, "No bookmark 9999")
```

A declared failure comes back as an `Outcome`, and the assertions say which side
they want instead of making you write a `when` with an unreachable branch. Ones
that return a value join up with whatever matchers you already use:

```kotlin
val bookmark = app.outcome(getBookmark, 1L).shouldBeOk()            // Bookmark
bookmark.title shouldBe "Pekko"

app.outcome(getBookmark, 9_999L).shouldBeError()                    // NoSuchBookmark
    .shouldBeInstanceOf<NoSuchBookmark>().id shouldBe 9_999L

// When two failures share a payload type, equality cannot tell them apart, so
// assert on the declaration the handler named, which is what fixed the status.
app.outcome(placeOrder, input) shouldBeFailure noSuchUser
```

These throw plain `AssertionError`, so `pelican-test` needs no matcher library
of its own and puts none on your classpath. No path strings, no JSON literals.
Every suite runs twice, in memory and over a real socket, so a difference
between the two is a real difference in behaviour.

---

# A generated Kotlin client

Callers who cannot hold the descriptions, because they are in another repository
or on another release cycle, get a file generated from them instead. Point it at
a source root and it lays out the package directories itself.

```kotlin
ordersSpec().writeKotlinClient(sourceRoot, packageName = "com.example.orders")
// -> <sourceRoot>/com/example/orders/OrdersClient.kt
```

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs)

val orders = client.listOrders(1L, limit = 3)          // Streamed<Order>, as they arrive

when (val result = client.placeOrder(1L, CreateOrder("anvil"), xApiKey = key)) {
    is Outcome.Ok  -> result.value                     // Order
    is Outcome.Err -> when (val failure = result.failure) {   // exhaustive
        is PlaceOrderFailure.Unauthorized -> retryWith(freshKey())
        is PlaceOrderFailure.NotFound     -> null
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
handle it stop compiling.

The generated file needs `pelican-core`, which has no dependencies of its own,
and a `Codecs` chosen by the caller. Transport is the JDK's `HttpClient`. The
example checks its generated client into the repo and runs the suite against a
real server, so a test fails if the file drifts from the descriptions.

---

# Backends

The backend is a choice about handlers. Bind the same endpoint values with
another module's binders and the only thing that changes is the type a streaming
handler returns:

```kotlin
// pelican-pekko: a stream is a Source
watchOrders streamedNow { (_, max) ->
    Source.range(1, max).throttle(1, ofMillis(100)).map { tick(it) }
}

// pelican-http4k: a stream is a Sequence, pulled as the body is written
watchOrders streamedNow { (_, max) ->
    (1..max).asSequence().map { Thread.sleep(100); tick(it) }
}

// pelican-ktor: a stream is a Flow, collected as the body is written
watchOrders streamedNow { (_, max) ->
    flow { for (i in 1..max) { delay(100); emit(tick(i)) } }
}
```

Ktor is the one backend whose handlers are `suspend` functions, because that is
how Ktor asks a question. `handledNow { id -> repository.find(id) }` may await
whatever it likes, and a lambda that suspends nowhere still fits, so there is no
second set of blocking binders.


`example/backends/` is one description file, three binding files, and a `main`
that starts all three servers so the endpoints can be curled side by side.
`AllBackendsTest` is one suite run three times, with its questions built from the
endpoint values, so it cannot ask two backends different things, and a final test
asserts the three answers match byte for byte down to the generated OpenAPI
document being the identical string.

One caveat: http4k's `SunHttp` and `Undertow` hold small writes in a buffer,
which turns a streamed response into a slow one, while `Jetty` does not. Rather
than ship a default that quietly breaks streaming, `pelican-http4k` binds
`StreamingSunHttp` (the JDK's server, flushing each frame, no extra dependency).
Pass any other `ServerConfig` to `start(config = ...)`.

---

# What it costs

A description has to be interpreted, and that is not free. What it costs is
measured rather than argued about:
[`OverheadBenchmark`](example/src/test/kotlin/example/OverheadBenchmark.kt)
serves one endpoint twice — once described with Pelican and interpreted onto
http4k, once written directly against http4k's own routing — and compares them.
Both decode a path parameter and an optional query parameter, and both encode
the same object with the same Jackson mapper, so what is left in the difference
is the interpreter.

| against | per request |
|---|---|
| http4k written the ordinary way | **0-35ns, 112 bytes** |
| http4k with the response hand-tuned | ~150ns, ~400 bytes |
| Pekko's routing DSL, written the ordinary way | **90ns and 1.4KB *faster*** |
| Pekko with the route hand-tuned | ~95ns, ~950 bytes |

Two baselines each, because one would flatter. An http4k `Response` is immutable, so
the idiomatic `Response(status).header(...).body(...)` copies it at each step —
three responses and two header lists where one of each will do. Pelican builds
it in one construction, which is worth about 300 bytes a request and is most of
why the first row is what it is. The second row is the honest comparison
against someone who has done the same thing by hand.

On Pekko the ordinary way costs more than the description does, which is the
row worth explaining rather than boasting about: an idiomatic route nests
`pathPrefix`, a `PathMatcher` and `parameterOptional`, and each layer allocates.
Pelican matches the path itself in one pass inside a single `extractRequest`,
which is what the fourth row measures against — the same thing done by hand.
[`PekkoOverheadBenchmark`](example/src/test/kotlin/example/PekkoOverheadBenchmark.kt)
seals both routes with `Route.function(system)`, so neither pays for a socket.

For scale: a loopback socket round trip is tens of microseconds and a database
call is milliseconds, so on a realistic endpoint this is a fraction of a
percent. It does not grow with the endpoint either — the cost is the
per-request bag of decoded values, the `Params` around it and the response, and
none of those scale with how many inputs are declared or how big the payload
is. An endpoint that decodes nothing pays much the same as one that decodes
two, which on the smaller absolute numbers of an empty endpoint reads as a
larger ratio.

```bash
./gradlew :example:test -Dbenchmark=true --tests "*OverheadBenchmark*"   # both
```

It is not JMH: one JVM, no forks, no blackholes. Treat the ratio as sound and
the absolute numbers as indicative. It runs only when asked for, and turns the
coverage agent off for itself — measuring through instrumentation reports the
agent rather than the library, which cost an afternoon to notice.

---

# Modules

Fourteen modules; you take four or five. The layering is enforced by tests
rather than convention.

| Module | Depends on | Contains |
|---|---|---|
| `pelican-core` | **nothing** | endpoint descriptions, plain codecs, a minimal JSON tree |
| `pelican-jackson` / `pelican-kotlinx` | core + one JSON library | your `Codecs` |
| `pelican-pekko` / `-http4k` / `-ktor` | core + one server library | descriptions → that server's routes |
| `pelican-*-docs` | its backend, openapi | serves the document and Swagger UI |
| `pelican-openapi` | core | descriptions → OpenAPI 3.1.0 |
| `pelican-codegen` | core | descriptions → a Kotlin client, as source |
| `pelican-test` | **core** | descriptions → a typed client for tests, on any backend |
| `pelican-test-pekko` / `-http4k` | test + that backend | the in-memory transport |

Every one of those dependency claims is a test. `pelican-core` asserts its
runtime classpath holds nothing but the Kotlin standard library, `pelican-openapi`
asserts Pekko is absent so docs can be generated in a build task with no server
present, each backend asserts the document generator and the other backends are
absent, and `pelican-test` asserts it drags in no server library and no matcher
library. The full breakdown is in
[docs/reference.md](docs/reference.md#modules).

---

# Running the examples

```bash
./gradlew build                          # all modules, 574 tests
./gradlew :example:runReadmeExample      # the service above, on :8080
./gradlew :example:run                   # the fuller orders API (streaming, SSE, raw bodies)
./gradlew :example:runBackends           # all three backends at once, on :8080-:8082
./gradlew :example:runSecured            # a filter enforcing the security the descriptions declare
./gradlew :example:generateOpenApi       # the spec, with no server started
./gradlew :example:generateKotlinClient  # the Kotlin client, likewise
```

`runHttp4k` and `runBookmarks` are there too, and every example takes a port with
`--args=8081`. The two generator tasks start nothing: `pelican-openapi` and
`pelican-codegen` depend on core alone, so neither needs an HTTP library present.

---

# Known limits

The ones most likely to affect whether Pelican fits. The full list, with the
reasoning behind each, is in [docs/reference.md](docs/reference.md#what-isnt-here).

- **Security schemes are documented, not enforced.** `security(scheme, "scope")`
  describes what a caller must present and draws the padlock; checking the token
  is yours. `:example:runSecured` is one filter, derived from `endpoint.security`,
  that covers every endpoint including ones added later.
- **OpenAPI 3.1 only.** JSON Schema 2020-12, `type: ["string", "null"]` where 3.0
  wrote `nullable: true`, numeric exclusive bounds. There is no switch back, and
  tooling that reads only 3.0 is not served. See the
  [migration note](docs/reference.md#moving-from-303-to-310).
- **One file part per multipart endpoint, and it goes last.** Reading stops at
  the file so the handler gets a live stream. A second part is a startup failure
  and a text part after the file is a 400. `rawBody()` is there for an envelope
  you would rather parse yourself.
- **No content negotiation.** One output media type per endpoint.
- **Six typed inputs, then the lens form.** `endpoint(a..f)` is the largest tuple
  overload. Past that, `endpoint { }` reads `Params` by key at the cost of the
  compile-time guarantee. Two adjacent inputs of the same primitive type can also
  be swapped by mistake; a value class and `map`/`mapOrFail` fixes it.
- **405 vs 404 fidelity is the router's, not Pelican's.** A wrong method on a
  known path is 405 on http4k, 404 on Ktor, and either on Pekko depending on
  what else is declared. `MethodMismatchTest` and `KtorInterpreterTest` pin it.
- **The generated client is a file, not a live binding.** Regenerate it when the
  descriptions change. A test comparing the checked-in file to a fresh one is
  the cheapest guard. Point its `Codecs` at `JsonInclude.Include.NON_NULL`, or
  an optional body property sends `null` where the server expected absence.

---

# Versions

Kotlin 2.2.20 · Pekko 1.6.0 · Pekko HTTP 1.4.0 · http4k 6.22.0.0 · Ktor 3.5.2 ·
Jackson 2.22.2 · swagger-core 2.2.54 · kotlinx.serialization 1.9.0 ·
slf4j-api 2.0.17 · JDK 21 · Gradle 8.14.3

http4k is pinned to the last release built against Kotlin 2.2.20. A newer one
ships stdlib metadata this compiler will not read, so bump both together.
