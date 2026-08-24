<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/pelican-mark-dark.svg">
  <img src="docs/assets/pelican-mark-light.svg" width="88" height="88" alt="">
</picture>

# Pelican

**Type-safe HTTP for Kotlin.** Describe an endpoint once, as a value.

[![build](https://github.com/matthewjones372/pelican/actions/workflows/build.yml/badge.svg)](https://github.com/matthewjones372/pelican/actions/workflows/build.yml)
[![Kotlin 2.2.20](https://img.shields.io/badge/Kotlin-2.2.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![OpenAPI 3.1](https://img.shields.io/badge/OpenAPI-3.1-6BA539?logo=openapiinitiative&logoColor=white)](https://spec.openapis.org/oas/v3.1.0)
[![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

[Getting started](#getting-started) · [Describing endpoints](#describing-endpoints) ·
[Serving and testing](#serving-and-testing) · [Reference manual](docs/reference.md)

</div>

---

You describe an endpoint once, as a value. Pelican turns that one description
into the server route, the OpenAPI document, a test client, and a generated
Kotlin client for your callers — there is no second source of truth, because
there is no second description.

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

Interpreting a description is not free, and what it costs is measured rather
than argued about — a few hundred nanoseconds against a route someone tuned by
hand, and cheaper than the idiomatic one. The numbers, and the two baselines
they need, are in [what it costs](docs/what-it-costs.md).

## Why you might want this

**Your docs cannot drift.** The OpenAPI document is generated from the endpoint
values your server is built from, so there is no second source of truth to
forget. No annotations scanned at startup, no YAML written twice.

**Handlers get typed arguments.** A path parameter declared as `Long` arrives as
a `Long`. No `Params` bag, no casting, no `String.toLong()` in every handler.

**Bad input is rejected before your code runs.** Constraints live on the input
value, so the same regex that validates a request also appears as `pattern` in
the schema. Swagger UI refuses to send a request the server would reject.

**Every response an endpoint can send is part of the signature.** `orFail` puts
a failure in the endpoint's type and `or` puts a second success there — a
`200 Order` beside a `202 Accepted`. The handler names the one it is producing,
and the caller's generated client gets a sealed type to match on either way.

**Tests call endpoints, not URLs — then pin the URLs on purpose.** Behaviour
tests name endpoint values, so renaming an input stops them compiling instead of
starting to 404. The client and the server agree by construction, though, which
is why the wire contract your callers hold is pinned separately, in one line per
endpoint: `app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"`.

**Swapping backends does not touch your descriptions.** Only the type a
streaming handler returns changes: `Source` on Pekko, `Sequence` on http4k,
`Flow` on Ktor.

## Contents

**[Getting started](#getting-started)** — [Install](#install) ·
[Your first endpoint](#your-first-endpoint) ·
[What the compiler catches](#what-the-compiler-catches)

**[Describing endpoints](#describing-endpoints)** —
[Inputs and validation](#inputs-and-validation) ·
[Declared failures](#declared-failures) ·
[More than one successful response](#more-than-one-successful-response) ·
[Streaming](#streaming) · [Cookies, forms and uploads](#cookies-forms-and-uploads) ·
[Response headers](#response-headers) · [Webhooks](#webhooks)

**[Serving and testing](#serving-and-testing)** —
[Running a server](#running-a-server) · [Testing](#testing) · [Backends](#backends)

**[Appendix](#appendix)** — [Longer documents](#longer-documents) ·
[Running the examples](#running-the-examples) · [Versions](#versions)

The reference manual, with the reasoning behind each design decision, is
[docs/reference.md](docs/reference.md).

---

# Getting started

## Install

> [!NOTE]
> Pre-release. Nothing is published to Maven Central or the Gradle Plugin Portal
> yet, so today you install locally — one command, and both work as normal
> dependencies afterwards.

Not on Maven Central yet. `./gradlew publishToMavenLocal` installs all fifteen
modules at `dev.pelican:<module>:0.1.0-SNAPSHOT` with sources and javadoc, so
`mavenLocal()` or `includeBuild` both work today. The Gradle plugin is a build
of its own and installs the same way:
`./gradlew -p pelican-gradle-plugin publishToMavenLocal`.

```kotlin
dependencies {
    implementation("dev.pelican:pelican-core:0.1.0-SNAPSHOT")
    implementation("dev.pelican:pelican-pekko:0.1.0-SNAPSHOT")
    implementation("dev.pelican:pelican-jackson:0.1.0-SNAPSHOT")
    testImplementation("dev.pelican:pelican-test:0.1.0-SNAPSHOT")
}
```

The plugin is `dev.pelican`, and it is not on the Gradle Plugin Portal yet
either — so until it is, tell the build where to find it:

```kotlin
// settings.gradle.kts
pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }
```

## Your first endpoint

One endpoint, the server that serves it, and the two things worth asserting
about it. Everything here is compiled — it is
[`FirstEndpoint.kt`](example/src/main/kotlin/example/hello/FirstEndpoint.kt) in
the example module, so the front page cannot drift from what runs.

```kotlin
data class Greeting(val message: String)

val who = pathParam<String>("who", description = "Who to greet")

val greet = endpoint(who) {
    get("hello" / who)
    summary = "Greet somebody by name"
    json<Greeting>()
}

fun greetings() = Api(
    endpoints = listOf(greet handledNow { name -> Greeting("Hello, $name!") }),
    codecs = JacksonCodecs,
    title = "Greetings",
    version = "1.0.0",
)

fun main() {
    greetings().startWithDocs(port = 8080, docs = Docs(docsPath = "/api-docs"))
}
```

> [!TIP]
> `./gradlew :example:runFirstEndpoint` serves this on `:8080` — the endpoint and
> a Swagger UI page for it, from the one value above.

It serves on `:8080`, with Swagger UI at
`/api-docs` built from the same value — there is no second file describing this
endpoint, and nothing scanned an annotation to find it.

The test names the endpoint rather than the URL, and then pins the URL on
purpose, because those are two different promises: one to your own code, one to
your callers.

```kotlin
private val app = greetings().inMemory("first-endpoint")

app.call(greet, "world") shouldBe Greeting("Hello, world!")
app.request(greet, "world") shouldBuild "GET /hello/world"
```

Rename `who`, change `Greeting`, or add a declared failure, and the handler and
the first assertion stop compiling. The second keeps answering a different
question: whether the URL your callers hold still exists.

## What the compiler catches

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

### More than one value

`?tag=a&tag=b`, `?id=1,2`, `X-Feature: beta,dark`. The modifier says how the
values are told apart; the codec and its refinements are unchanged, because
what one value decodes to has not changed:

```kotlin
val tags = queryParam<String>("tag").repeated().optional()          // List<String>?
val ids  = queryParam("id", LongCodec.positive()).commaSeparated()  // List<Long>
```

The handler gets a `List<Long>`, not a string it has to split, and `?id=1,0` is
a 400 naming the element that failed. `spaceSeparated()` and `pipeSeparated()`
are there too; a header takes `commaSeparated()` and a cookie `repeated()`,
which are the encodings those two can carry. The document says `type: array`
with the element's schema — refinements included — under `items`, and writes
`style`/`explode` only where they differ from OpenAPI's own default for that
location.

An absent list is `null`, not the empty list: `?tag=` carries no element, so an
empty list cannot be sent, and reading absence as empty would leave `required`
with nothing to mean. `.default(emptyList())` is how a description asks for the
other reading.

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

A failure can carry response headers as well as a payload — a `Retry-After` on a
429 being the usual pair. They are declared on the failure rather than with
`emits(...)`, because they belong to that one response:

```kotlin
val retryAfter = responseHeader<Long>("Retry-After", "Seconds to wait")
val throttled  = errorJson<ApiError>(429, "Too many requests", retryAfter)

json<Order>(status = 201).orFail(badApiKey, noSuchUser, throttled)

throttled(ApiError(429, "Slow down"), retryAfter of 30L)
```

The value is typed by the header's own codec, a header the failure never
declared throws, and a required one left out throws too — at the call, which is
the one place the declaration and the value are both in hand. Mark it
`.optional()` for a header that is only sometimes sent. A failure that declares
no headers is returned exactly as before.

`defaultJson<ApiError>("Any other failure")` documents OpenAPI's `default` —
"and anything else" — which is the one response an endpoint can describe and
cannot produce. It is a statement rather than a value: nothing to pass to
`orFail`, nothing for a handler to name, because a handler answers with a
status and "some other status" is not one. `defaultResponse(...)` is the same
for one with no payload. See
[The one response an endpoint cannot produce](docs/reference.md#the-one-response-an-endpoint-cannot-produce).

## More than one successful response

A failure is one *alternative*, not a special kind of thing. `or` declares
several successful responses the same way, so `200 Order` beside `202 Accepted`
is describable — and the handler names the one it is producing:

```kotlin
val orderAt = responseHeader<String>("Location", description = "Where the placed order lives")

val orderPlaced = json<Order>(status = 201, orderAt)   // `Location`, on this response only
val orderQueued = json<Queued>(status = 202)

val submitOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders" / "submit")
    orderPlaced or orderQueued orFail badApiKey
}

submitOrder handledOneOf { (id, key, req) ->
    when {
        key != expected -> badApiKey(ApiError(401, "Bad API key"))
        tooBig(req)     -> orderQueued(Queued(ticket(id), position = req.quantity))
        else            -> {
            val order = Store.create(id, req)
            orderPlaced(order, orderAt of "/users/$id/orders/${order.id}")
        }
    }
}
```

Naming the declaration is what fixes the status, so `200 Order` and `201 Order`
stay distinguishable although the payload cannot say which is which. A response
the endpoint never declared does not compile. `ok(value)` means the first
declared success, so nothing about a single-response endpoint changed.

The document publishes both statuses with their own schemas and headers, and the
generated client hands the caller a sealed type — one member per status, `when`
over it exhaustive. Two responses cannot share a status, and a *streamed*
alternative is refused: producing one means handing over the backend's own
stream type, so a stream is still a success and still the only one. See
[More than one successful response](docs/reference.md#more-than-one-successful-response).

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
val locale   = cookieParam<String>("locale").default("en")     // in: cookie
val form     = formBody<SignIn>() or jsonBody<SignIn>()        // whichever the caller posts
val caption  = textPart("caption", StringCodec.nonEmpty())     // multipart/form-data
val manifest = bufferedFile("manifest", maxBytes = 8 * 1024)   // held, within a declared bound
val upload   = filePart("file", contentType = "text/csv")      // streamed, and so declared last

val importOrders = endpoint(locale, caption, manifest, upload) {
    post("orders" / "import")
    json<ImportResult>(status = 201)
}

importOrders handledNow { (locale, caption, manifest, file) -> // String, String, UploadedFile x2
    ImportResult(caption, manifest.text(), file.stream().bufferedReader().useLines { it.count() }, locale)
}
```

A form carries strings and nothing else, so what `visits=3` means comes from the
schema published for the body type. That is what makes a form body decode
identically under Jackson and under kotlinx.serialization, which coerce
differently when left to themselves.

`or` says the same payload arrives several ways: one `SignIn`, two encodings,
and the request's `Content-Type` picks the decode. A media type the endpoint did
not declare is a 415 naming the ones it did. Two different *schemas* under one
body remain undescribable — a handler is given one value of one type.

`file.stream()` is the request's own body, positioned at the part's first byte
and stopping at its boundary. Nothing holds a streamed upload, which brings one
constraint: **the streamed file must be the last part on the wire**, since
reading stops there. A companion file that has to arrive alongside it is
declared `bufferedFile("thumbnail", maxBytes = 256 * 1024)` — held in memory,
within a bound the declaration has to name — so a two-file upload form is
describable and what it costs is written where it is chosen.
A text part sent after the streamed file is a 400 that says so, and an HTML form
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
one.

`emits(...)` describes the *success* response, and a header on it may be set on
any response the endpoint sends — which is right for a correlation id and wrong
for a `Retry-After`. A header belonging to one failure is declared on that
failure instead: `errorResponse(429, "...", retryAfter)` for one that is only
documented, and `errorJson<ApiError>(429, "...", retryAfter)` for one the
handler returns — see [Declared failures](#declared-failures).

Every handler lambda has the request's `Params` as its receiver whatever its
input style, so a typed handler reaches `setHeader` without giving up its typed
inputs.

## Webhooks

OpenAPI 3.1's `webhooks` are the calls a service *sends* rather than answers. An
endpoint description was never a route — it is a method, some inputs and an
output — so a webhook is the same description read in the other direction:

```kotlin
val orderPlaced = webhook("orderPlaced") {
    body(orderPlacedEvent)
    header(hookSignature)
    summary = "Sent to a subscriber when an order is placed"
    empty(status = 204)
}

Api(endpoints = ordersRoutes, codecs = JacksonCodecs, webhooks = listOf(orderPlaced))
```

There is no path, because the path belongs to whoever subscribed: `webhook(...)`
takes the name OpenAPI files it under and the method, and writing a route or a
`servers(...)` is refused where it is written. The document publishes it under
`webhooks`, and a generated client grows a sender for it:

```kotlin
client.orderPlaced(url = subscriber.callbackUrl, body = order, xSignature = sign(order))
```

The destination is the first argument because the document does not know it, and
the client's standing headers are deliberately not sent with it — those are the
credential it presents to the API, and a subscriber is not the API.

**A webhook is never routed.** It lives in a field of its own on `Api`, the
three interpreters build their routes from `endpoints`, and binding one as a
handler fails at construction rather than quietly serving `POST /`. The `204`
above is what the *receiver* is expected to answer with, which is the one part
of the description nobody publishing it controls. See
[the reference](docs/reference.md#webhooks-the-calls-the-service-sends).

---

# Serving and testing

## Running a server

```kotlin
ordersApi().start(port = 8080)                                  // endpoints only
ordersApi().startWithDocs(port = 8080, docs = ordersDocs)       // plus /openapi.json and /docs
```

Both create an `ActorSystem` and shut it down again on `stop()`. A service that
already has one — for its cluster, its persistence, its streams — hands it over
instead of running a second:

```kotlin
val server = ordersApi().startWithDocs(system, port = 8080, docs = ordersDocs)
```

`stop()` unbinds the port either way, and terminates the system only if Pelican
created it: whoever made a system is who ends it. `toRoute(system)` is still
there for a service that concatenates Pelican's route with its own and binds
the result itself.

### Filters

A filter runs around every handler and sees the request with its inputs already
decoded — `p[reportId]` is a `Long`, not a string to parse again. Rejecting is
throwing: `unauthorized()`, `forbidden()`, `tooManyRequests(retryAfterSeconds = 3600)`,
so a refusal is rendered by the code that renders every other failure, on all
three backends.

What a filter works out goes into an *attribute*, which is how it reaches the
handler without becoming an input the document would have to declare:

```kotlin
val caller = attribute<Caller>("caller")

val requireToken = before { p ->
    val presented = p[authorization]?.removePrefix("Bearer ")
    p[caller] = tokens[presented] ?: unauthorized("Present a bearer token")
}

val rateLimit = before { p ->
    val who = p[caller]                              // already there: requireToken ran first
    if (who.plan == "free" && seen(who).incrementAndGet() > 100) {
        tooManyRequests("100 requests an hour on the free plan", retryAfterSeconds = 3600)
    }
}
```

Register them once, outermost first — that ordering is why `rateLimit` can read
what `requireToken` established:

```kotlin
Api(endpoints, JacksonCodecs, filters = listOf(requireToken, rateLimit))
```

And the handler reads the attribute off its receiver. There is no second check
here, and no way for this handler to have skipped the first:

```kotlin
getReport handledNow { (id, _) -> Report(id, "Q3", visibleTo = this[caller].subject) }
```

That whole example is
[`FilterExample.kt`](example/src/main/kotlin/example/filters/FilterExample.kt),
compiled on every build.

`:example:runSecured` takes the idea to its conclusion: one filter that reads
`endpoint.security`, the same list that drew the padlock in Swagger UI, and holds
the caller to it. Add an endpoint with `security(idp, "reports:admin")` and it is
covered before it is bound, with no second list to keep up to date.

### Unhandled exceptions

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

### Size limits and startup checks

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

### CORS

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

### Choosing a JSON library

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

A sealed hierarchy publishes the same way through either: `oneOf` over the
branches with a `discriminator` and a full `mapping`, so the document says which
*value* selects which branch. `@JsonSubTypes.Type(name = "bank_transfer")` on a
class called `BankTransfer` is a fact that lives in one annotation, and a
document that leaves it out is not vague — OpenAPI reads the missing mapping as
"the value is the schema name", so every client generated from it would send
`BankTransfer` and be rejected.

A hierarchy nested inside another publishes flat, through either, because
neither JSON library puts two type ids on a payload: a class two levels down
travels under the outermost discriminator with its own name. The nesting is a
Kotlin relation and stays one. A *document* that spreads the type over two
properties is refused rather than imported into a hierarchy that would decode
nothing — see
[docs/reference.md](docs/reference.md#two-levels-of-hierarchy).

## Testing

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

// A header the failure declared comes back on the failure, decoded by the same
// codec that wrote it.
(app.outcome(placeOrder, input) as Outcome.Err)[retryAfter] shouldBe 30L
```

These throw plain `AssertionError`, so `pelican-test` needs no matcher library
of its own and puts none on your classpath. Every suite runs twice, in memory
and over a real socket, so a difference between the two is a real difference in
behaviour.

### Pinning the URL

Nothing above mentions a path, which is what stops those tests drifting off your
endpoints — and also what stops them noticing when `"bookmarks"` becomes
`"books"`. The client builds its request from the same description the server
routes on, so a rename moves both ends at once: the suite stays green while
every caller already deployed against the old path starts getting a 404.

The URL and the parameter names are the contract those callers hold, so pin them
against literals — deliberately repeating what the description says, because a
copy that does not move is the only thing that can catch the move:

```kotlin
app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"
app.request(listBookmarks, In2(20, Slug("streams"))) shouldBuild "GET /bookmarks?limit=20&tag=streams"
app.request(deleteBookmark, In2(1L, key)) shouldBuild "DELETE /bookmarks/1"
```

`request` builds the call without sending it, so this costs no server and no
transport. It is the one test in the suite that *should* fail on a rename: a
red line here is the 404 your callers would have found for you.

## Backends

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

# Appendix

## Longer documents

Five things that wanted a page rather than a section, and one benchmark:

| Page | What it answers |
|---|---|
| [A whole service, in one file](docs/a-whole-service.md) | What all of it looks like at once — models, inputs, endpoints, handlers, store, server, docs. Compiled every build as `ReadmeExample.kt`. |
| [A generated Kotlin client](docs/generated-client.md) | What callers who cannot hold the descriptions get instead, and what the generator does with a union, a failure or a stream. |
| [Importing an OpenAPI document](docs/importing.md) | A document somebody else wrote, read into descriptions: what comes out, what is refused, and how to get past a document you do not own. |
| [The same endpoints, by hand](docs/by-hand.md) | The same two endpoints written directly against Pekko HTTP, so what the descriptions buy is legible rather than asserted. |
| [Modules](docs/modules.md) | What each of the fifteen modules is for and what it depends on, for deciding which ones your build needs. |
| [What it costs](docs/what-it-costs.md) | The interpreter measured against the hand-written route it replaces, with the baselines that comparison needs. |

---

## Running the examples

```bash
./gradlew build                          # all modules: tests, detekt, spotless, coverage
./gradlew :example:runReadmeExample      # the service above, on :8080
./gradlew :example:run                   # the fuller orders API (streaming, SSE, raw bodies)
./gradlew :example:runBackends           # all three backends at once, on :8080-:8082
./gradlew :example:runSecured            # a filter enforcing the security the descriptions declare
./gradlew :example:generateOrdersDocument  # the spec, with no server started
./gradlew :example:generateOrdersClient    # the Kotlin client, likewise
```

`runHttp4k` and `runBookmarks` are there too, and every example takes a port with
`--args=8081`. The two generator tasks come from the repository's own Gradle
plugin, and they start nothing: `pelican-openapi` and `pelican-codegen` depend
on core alone, so neither needs an HTTP library present.

## Versions

Kotlin 2.2.20 · Pekko 1.6.0 · Pekko HTTP 1.4.0 · http4k 6.22.0.0 · Ktor 3.5.2 ·
Jackson 2.22.2 · swagger-core 2.2.54 · kotlinx.serialization 1.9.0 ·
slf4j-api 2.0.17 · snakeyaml-engine 2.10 · JDK 21 · Gradle 8.14.3

http4k is pinned to the last release built against Kotlin 2.2.20. A newer one
ships stdlib metadata this compiler will not read, so bump both together.
