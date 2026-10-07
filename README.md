<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/pelican-mark-dark.svg">
  <img src="docs/assets/pelican-mark-light.svg" width="88" height="88" alt="">
</picture>

# Pelican

Typed HTTP endpoints for Kotlin. You describe an endpoint once, and the server
route, the OpenAPI document and a typed client are built from that description.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.matthewjones372/pelican-core?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/io.github.matthewjones372/pelican-core)
[![build](https://github.com/matthewjones372/pelican/actions/workflows/build.yml/badge.svg)](https://github.com/matthewjones372/pelican/actions/workflows/build.yml)
[![coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmatthewjones372%2Fpelican%2Fbadges%2Fcoverage.json)](https://github.com/matthewjones372/pelican/actions/workflows/build.yml)
[![Kotlin 2.4.10](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![OpenAPI 3.1 and 3.2](https://img.shields.io/badge/OpenAPI-3.1%20%7C%203.2-6BA539?logo=openapiinitiative&logoColor=white)](https://spec.openapis.org/oas/v3.2.0)
[![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

[Getting started](#getting-started) · [The endpoint model](#the-endpoint-model) ·
[Serving and testing](#serving-and-testing) · [Cookbook](docs/cookbook.md) ·
[Reference manual](docs/reference.md) · [Choosing](docs/choosing.md)

</div>

---

Pelican is a Kotlin library for describing HTTP APIs. You write down what an
endpoint is (its path, its inputs, the types it can return) as an ordinary
Kotlin value. Pelican builds the rest from that value: the server route, the
OpenAPI document, a typed test client, and a generated Kotlin client for your
callers.

The endpoint description is the only description of the HTTP contract. There is
no annotation scanning and no hand-maintained YAML to keep in step with it.

It is a library, not a framework. It does not own your `main`, and it serves
through a web stack you already run, which for 1.0 is Pekko HTTP. If you know
tapir from Scala, this is the same idea, limited to what Kotlin's type system
can express without implicits.

A description, then the handler that answers it:

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

If you change the path parameter's type, the response type or the declared
error, the handler stops compiling.

## What it does

**The OpenAPI document comes from the code.** The document (3.1.0, or 3.2.0 on
request) is generated from the same endpoint values the server is built from,
so it stays in step with the server. See
[Which version the document says](docs/reference.md#which-version-the-document-says-and-how-to-choose)
for how to choose a version. For a tool that wants a JSON Schema for one type
rather than the whole document, `pelican-schema` writes one that resolves on its
own; see [A schema that resolves on its own](docs/schemas.md).

**Handlers get typed arguments.** A path parameter declared as `Long` arrives
as a `Long`. There is no `Params` bag, no casting and no `String.toLong()` in
each handler.

**Bad input is rejected before your code runs.** Constraints are part of the
input value, so the rule that refuses a request also appears in the schema:
`between(1, 100)` is both the check and the `minimum`/`maximum`.

**Every response is part of the endpoint's type.** `orFail` adds a failure to
the endpoint's type and `or` adds a second success, such as a `200 Order`
beside a `202 Accepted`. The handler says which one it is producing, and the
generated client gets a sealed type to match on.

**Tests call endpoints, and URLs are pinned separately.** Behaviour tests refer
to endpoint values, so a rename stops them compiling instead of making them
404. The URLs your callers depend on are pinned in a separate test, one line
per endpoint: `app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"`.

**Descriptions are independent of the backend.** 1.0 ships one backend and one
codec, Pekko HTTP and Jackson, to keep the first release small enough to
support properly. The http4k and Ktor interpreters and the kotlinx and
jsoniter codecs were written and passed the same parity suites, then were taken
off `main` before 1.0. They are in the history at commit
[`a6c20b2`](https://github.com/matthewjones372/pelican/tree/a6c20b20350dddb21ac4cbf2d22f88eb8756081e), the last commit before they were
removed, and are meant to come back after 1.0. Your descriptions will not need
to change when they do.

**The overhead is measured.** The interpreter is benchmarked with JMH against
the same routes written by hand, with baselines and error bars in
[what it costs](docs/what-it-costs.md). On a realistic endpoint the difference
is a fraction of a percent of the request time.

## Contents

**[Getting started](#getting-started)**: install, the modules, a first
endpoint, and what the compiler catches.

**[The endpoint model](#the-endpoint-model)**: inputs and validation, declared
failures, multiple responses, streaming, and the other things a description
can say.

**[Serving and testing](#serving-and-testing)**: running a server, filters and
metrics, who is calling, the typed test client, golden files, stubbing another
service, and clients for your callers.

**[Appendix](#appendix)**: the longer documents, the runnable examples,
[what is on main and not yet released](#on-main-not-yet-released),
[stability](#stability) and [versions](#versions).

The reference manual, which explains the reasoning behind each design
decision, is [docs/reference.md](docs/reference.md).

---

# Getting started

## Install

The current release is **1.0.0-RC3**, on Maven Central under
`io.github.matthewjones372`. It is a release candidate: the API below is what
1.0 will ship, but the stability promise only applies from 1.0 (see
[Stability](#stability)).

```kotlin
dependencies {
    // The interpreter. Brings pelican-core; compiles against Pekko HTTP.
    implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC3")
    // Pekko itself: Pelican ships no Scala cross-build, so name the one you run.
    implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-stream_2.13")
    implementation("org.apache.pekko:pekko-http_2.13:1.4.0")
    // The codec module: Jackson, and the schemas the document derives.
    implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC3")
    // /openapi.json and Swagger UI beside the endpoints. startWithDocs lives here.
    implementation("io.github.matthewjones372:pelican-pekko-docs:1.0.0-RC3")
    // The typed test client.
    testImplementation("io.github.matthewjones372:pelican-test:1.0.0-RC3")
    // inMemory() and server.client(): the test client run against a Pekko Api.
    testImplementation("io.github.matthewjones372:pelican-test-pekko:1.0.0-RC3")
}
```

Those modules are the standard stack. The examples on this page use them,
except where a section names another module. [Modules](#modules) below lists
every module, and [docs/modules.md](docs/modules.md) says what each depends on.

The Gradle plugin is `io.github.matthewjones372.pelican`. It is published to
Maven Central rather than the Gradle Plugin Portal, so the build needs to be
told once:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins { id("io.github.matthewjones372.pelican") version "1.0.0-RC3" }
```

To build against unreleased changes, `./gradlew publishToMavenLocal` installs
the modules and `./gradlew -p pelican-gradle-plugin publishToMavenLocal`
installs the plugin. Between releases the version comes from the nearest git
tag: an untagged commit gets a `-SNAPSHOT` of the next version.

## Modules

Every module uses the group `io.github.matthewjones372`. All of them are on
Maven Central at 1.0.0-RC3 except `pelican-oidc`, which is only on `main`.
Where a released module has gained something on `main` since RC3, its line
says so; those additions are not released yet, and
[On main, not yet released](#on-main-not-yet-released) lists them together.

**Core and documents**

- `pelican-core`: endpoint descriptions, plain codecs, a small JSON tree and
  parser, and the client SPI with an in-memory transport. It depends on the
  Kotlin standard library and `jackson-core` only. On `main`: callers
  (`authenticated`), `pages`, and tagged failures.
- `pelican-openapi`: descriptions to an OpenAPI 3.1.0 or 3.2.0 document, in
  JSON or YAML, and a comparison of two documents that reports what would break
  callers. On `main`: tagged failures as a `oneOf`, and a comparison that reads
  through `oneOf` and `anyOf`.
- `pelican-schema`: one type as a JSON Schema 2020-12 document that resolves on
  its own, for tools that do not read the OpenAPI document.

**JSON and Arrow**

- `pelican-jackson`: the `Codecs`. Jackson reads and writes bodies, and
  swagger-core describes the types for the document.
- `pelican-arrow`: Arrow's `Either` to an `Outcome` (`toOutcome`) and back
  (`toEither`). On `main`: a `toOutcome { }` that names which of several
  declared failures a `Left` becomes.

**Server**

- `pelican-pekko`: descriptions to a Pekko HTTP `Route`, and `start` to serve
  them. On `main`: synchronous handlers run on virtual threads (`Handlers`), and
  `pages` serves a classpath directory beside the endpoints.
- `pelican-pekko-docs`: `/openapi.json` and a Swagger UI or Redoc page beside
  the endpoints (`startWithDocs`).
- `pelican-streams`: a streamed request body as a lark `Stream`, whose failure
  is a value, and the result back as the declared `Outcome`.

**Client**

- `pelican-client-pekko`: `PekkoHttpTransport`, which sends a generated
  client's requests through Pekko HTTP's client.

**Testing**

- `pelican-test`: the typed test client, outcome assertions and URL pins, with
  no server library and no matcher library. On `main`: `TestCallers`,
  `signedInAs`, and an `inMemory()` that needs no backend module.
- `pelican-test-pekko`: `inMemory()` and `server.client()`, which run the
  typed test client against a Pekko `Api` without a socket or against a
  running server.
- `pelican-test-golden`: one golden file per endpoint, failing when a change
  would break existing callers.
- `pelican-test-wiremock`: a WireMock server stubbed and verified with endpoint
  values, for testing a client of somebody else's API. On `main`: `stubFile`,
  which writes those stubs out as WireMock mapping files.

**MCP**

- `pelican-mcp`: descriptions to MCP tool descriptions, and a dispatch that
  runs a tool call through the handler. Values only, with no transport.
- `pelican-mcp-server`: serves those tools over stdio (`mcpServe`), and the
  request/response half of Streamable HTTP for a backend to mount. No MCP SDK.
- `pelican-pekko-mcp`: mounts the tools on `/mcp` beside the endpoints.

**Metrics**

- `pelican-metrics`: a filter giving Micrometer meters tagged by method, path
  template and status, and a counter for requests refused before any filter
  runs.
- `pelican-metrics-otel`: the same for OpenTelemetry, with a `SERVER` span per
  request and a duration histogram. On `main`: `traced`, a client transport
  that opens a client span and sends `traceparent`.

**Security**

- `pelican-oidc`: verifies an OpenID Connect provider's tokens for a caller, and
  signs people in to pages. Only on `main`; not on Maven Central.

**Code generation and import**

- `pelican-codegen`: descriptions to a Kotlin client, as source. On `main`: the
  generated client reads the tag of a tagged failure.
- `pelican-import`: an OpenAPI document, in JSON or YAML, to descriptions, as
  source.

**Build plugin**

- `pelican-gradle-plugin`: the `io.github.matthewjones372.pelican` plugin. It
  runs the generators above as Gradle tasks: documents, clients, imported
  endpoints, and the checks against committed copies.

`example` and `benchmarks` are part of the build and are not published.

## Your first endpoint

One endpoint, the server that serves it, and its tests. This code is compiled
on every build: it is
[`FirstEndpoint.kt`](example/src/main/kotlin/example/hello/FirstEndpoint.kt) in
the example module.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC3")
    implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC3")
    implementation("io.github.matthewjones372:pelican-pekko-docs:1.0.0-RC3")
    // Pekko itself: Pelican ships no Scala cross-build, so name the one you run.
    implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-stream_2.13")
    implementation("org.apache.pekko:pekko-http_2.13:1.4.0")
}
```

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

> [!TIP]
> `./gradlew :example:runFirstEndpoint` serves this on `:8080`: the endpoint
> and a Swagger UI page for it.

The document at `/openapi.json` and the Swagger UI page at `/api-docs` are
both built from the `greet` value. There is no separate file describing the
endpoint, and no annotation scanning.

The test refers to the endpoint rather than the URL, and then checks the URL
separately. These are two different promises: one to your own code, and one
to your callers.

```kotlin
private val app = greetings().inMemory("first-endpoint")

app.call(greet, "world") shouldBe Greeting("Hello, world!")
app.request(greet, "world") shouldBuild "GET /hello/world"
```

If you rename `who` or change `Greeting`, the handler and the first assertion
stop compiling. The second assertion checks something else: whether the URL
your callers use still exists.

## What the compiler catches

Each of these errors is real compiler output for the mistake shown:

```
+(getUser handledNow { id: String -> ... })
  e: Argument type mismatch: actual type is 'Params.(String) -> User', but 'Params.(Long) -> User' was expected.

+(watchOrders streamedNow { (_, max) -> Source.single("not a tick") })
  e: Return type mismatch: expected 'Source<Tick, NotUsed>', actual 'Source<String!, NotUsed!>!'.

+(getBookmark handledOrFail { id -> Bookmarks.find(id)!! })
  e: Return type mismatch: expected 'Outcome<NoSuchBookmark, Bookmark>', actual 'Bookmark'.
```

`DoesNotCompileTest` compiles each of these on every build and checks the
error message, so this list cannot go out of date.

Some mistakes are beyond the type system: a path parameter missing from the
path, a scope its security scheme never granted, a missing codec. These are
checked when the endpoint value or the `Api` is constructed, so they fail at
start-up rather than on the first request:

```
GET /things declares path parameter 'stray', but the path is /things
```

---

# The endpoint model

A short tour, one idea and one example per section. The
[reference manual](docs/reference.md) covers each in full, and the
[cookbook](docs/cookbook.md) has complete recipes you can copy.

## Inputs and validation

Inputs are values. You declare them once, and the route, the decoder, the
document and the test client all use them. A refinement narrows what is
accepted and also appears in the schema:

```kotlin
val bookmarkId = pathParam<Long>("bookmarkId", description = "The bookmark's id")
val limit      = queryParam("limit", IntCodec.between(1, 100), description = "How many to return").default(20)
val tag        = queryParam("tag", slug, description = "Only bookmarks with this tag").optional()
```

Swagger UI reads the published constraints and will not send a request the
server would reject. A request that gets through anyway gets a 400 before any
handler runs, naming the parameter and the rule it broke.

There are ready-made codecs for the primitives, `UUID`, the `java.time` types,
`URI` and any Kotlin `enum`. Your own types take about three lines with
`PlainCodec.mapOrFail` and are validated as they are decoded. Multi-valued
parameters (`?tag=a&tag=b`, `?id=1,2`) decode to typed lists. See
[Refined inputs](docs/reference.md#refined-inputs) and
[More than one value](docs/reference.md#more-than-one-value).

## Declared failures

`orFail` adds the failure to the endpoint's type, so the handler has to
produce it and the response body is the type the document says it is. An
endpoint can declare several failures, and the handler names the one it is
returning:

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

The status comes from the declaration, not from the payload's type, so two
failures can share a payload type. If the failures share a sealed supertype,
the `when` is exhaustive. A failure can also carry response headers (for
example a `Retry-After` on a 429), declared on that failure and supplied where
it is produced.

An endpoint with a single declared failure can skip the naming: `err(value)`
is the counterpart of `ok` and means that one failure. In an Arrow codebase you
can convert at the edge instead: `service.find(id).toOutcome()`, from
`pelican-arrow`, treats a `Right` as `ok` and a `Left` as `err`, and
`toOutcome(failure)` names the declaration when there are several. See
[Declared failures](docs/reference.md#declared-failures).

On `main` and not yet released: two failures can share a status when each is
`tagged`, for example two different 503s. The tag is written into the body,
the document describes that status as a `oneOf` with a discriminator, and the
generated client reads the tag. `pelican-arrow` also gains
`toOutcome { e -> ... }`, which names one of several declared failures for each
`Left`. See specs [0063](specs/0063-two-failures-under-one-status.md) and
[0060](specs/0060-an-either-with-several-failures.md).

## More than one successful response

A failure is just one alternative response. `or` declares several successes in
the same way, such as a `201 Order` beside a `202 Queued`, and the handler
names the one it is producing with `handledOneOf`. The document publishes both
statuses with their own schemas and headers, and the generated client gives
the caller a sealed type with one member per status. See
[More than one successful response](docs/reference.md#more-than-one-successful-response).

One response can also be written in more than one format, as in
`negotiated(json<Report>(), media<Report>("text/csv"))`, with the caller's
`Accept` header choosing between them. See
[One response, several renderings](docs/reference.md#one-response-several-renderings).

## Streaming

```kotlin
ndjson<Order>()             // one JSON document per line
sse<Tick>(eventName = "order", id = { it.sequence.toString() })
jsonArray<Order>()          // `[{...},{...}]`, flushed as produced
bytes()                     // opaque, never buffered
```

Handlers return the backend's own stream type (a `Source`, on Pekko), and
back-pressure runs from the socket to the source. The test suite throttles a
source and fails if the first frame does not arrive well before the last, so a
response that buffers by mistake is caught. SSE streams can carry event ids and
a retry directive, so a caller can resume where it left off. A request body can
also be a typed stream, with `ndjsonIn<T>()`. See
[Streaming](docs/reference.md#how-streaming-stays-backend-agnostic).

`pelican-streams` hands a streamed request body to
[lark's](https://github.com/matthewjones372/lark) `Stream`, which carries a
failure as a value, and turns the result back into the `Outcome` the endpoint
declared. A row the handler cannot read then becomes the declared failure
rather than a 500. See
[A stream that names its failure](docs/reference.md#a-stream-that-names-its-failure).

## Cookies, forms and uploads

Cookies, form bodies, multipart text parts and file uploads are inputs like any
other, with the same codecs, the same `optional()`/`default(v)`, the same
refinements and the same 400s. A form body is decoded against its published
schema, a streamed file part is passed on without buffering, and a buffered
part declares how much memory it may use. `or` lets one payload arrive either
as a form or as JSON, with `Content-Type` deciding how it is decoded. See
[Cookies](docs/reference.md#cookies),
[Form bodies](docs/reference.md#form-bodies) and
[Multipart uploads](docs/reference.md#multipart-uploads).

## Response headers

Response headers are declared on the endpoint with `emits(...)`, set from the
handler with `setHeader`, and published in the document with their schemas.
Setting an undeclared header throws, so the document and the actual responses
stay in step. A header that belongs to one response, such as the `Location` on
a 201, is declared on that response only. See
[Response headers](docs/reference.md#response-headers).

## Webhooks

OpenAPI's `webhooks` are the calls a service sends. In Pelican a webhook is
described the same way as an endpoint, but read in the other direction: a name,
a method and a body, with no path, because the path belongs to whoever
subscribed. The document publishes it under `webhooks`, the generated client
gets a sender for it, and binding one as a route fails at construction. See
[Webhooks](docs/reference.md#webhooks-the-calls-the-service-sends).

## Tools a model can call

The same descriptions can also be served as MCP tools. An endpoint already
knows its name, its arguments, their constraints and the shape of its result,
which is what a tool description needs. Arguments are decoded with the same
codecs an HTTP request uses, calls go through the handler the route already
has, and declared failures come back as results the model can act on.
`mcpServe(api)` speaks the protocol over stdio, and `pelican-pekko-mcp` mounts
it on `/mcp`. Neither needs an MCP SDK on the classpath. See
[Tools a model can call](docs/mcp.md).

---

# Serving and testing

## Running a server

```kotlin
ordersApi().start(port = 8080)                                  // endpoints only, on 127.0.0.1
ordersApi().start(port = 8080, host = "0.0.0.0")                // every interface, said out loud
ordersApi().startWithDocs(port = 8080, docs = ordersDocs)       // plus /openapi.json and /docs
```

The server listens on loopback by default; listening on the network has to be
asked for explicitly. The handle is `AutoCloseable` with `block()`, `stop()`
and `stopAsync()`. A service that already has an `ActorSystem` can pass it in
with `start(system, port = 8080)`, and whoever created a system is responsible
for shutting it down.

Pelican does not have to own the port. `toRoute(system)` returns a Pekko
`Route` and binds nothing, so described endpoints can sit beside routes you
already have. This is how you would adopt Pelican one endpoint at a time:

```kotlin
ordersApi().start(port = 8080) { system ->
    Directives.concat(health, toRoute(system))
}
```

Two changes are on `main` and not yet released. A synchronous handler
(`handledNow`, `handledOrFail` and the rest) runs on a virtual thread of its
own, so it can block on a database call without holding one of Pekko's
dispatcher threads; `handlers = Handlers.onDispatcher` keeps the RC3
behaviour. And `pages = pages("ui")` in `api`'s block serves a classpath
directory beside the endpoints, with an endpoint always winning over a page at
the same path. See [Where a handler runs](docs/reference.md#where-a-handler-runs)
and [Pages beside the endpoints](docs/reference.md#pages-beside-the-endpoints).

## Filters

A filter runs around every handler and sees the request with its inputs
already decoded. To reject a request, a filter throws (`unauthorized()`,
`forbidden()`, `tooManyRequests(retryAfterSeconds = 3600)`). Anything a filter
works out is passed to the handler in a typed attribute:

```kotlin
val caller = attribute<Caller>("caller")

val requireToken = before { p ->
    p[caller] = tokens[p[authorization]?.removePrefix("Bearer ")]
        ?: unauthorized("Present a bearer token")
}

api(endpoints, JacksonCodecs) { filter(requireToken) }
```

A filter can see which endpoint matched, which makes one-line integrations
possible. `filter(metrics(registry))` gives Micrometer meters tagged by method,
path template and status (`pelican-metrics`), and `filter(openTelemetry(sdk))`
gives a `SERVER` span per request, named from the description
(`pelican-metrics-otel`). Requests refused before the filter chain runs, such
as a body over the size limit or a parameter that does not decode, are counted
by `onRefusal(refusalCounter(...))`, since no filter sees them. See
[Filters](docs/reference.md#filters) and [Metrics](docs/reference.md#metrics).

## Who is calling

This section is on `main` and not yet released.

An endpoint can declare who is calling it, under a security scheme, as a typed
value built from the verified identity. A request with no credential, or one
that fails verification, gets a 401 before the handler runs, and the document
lists the scheme. `pelican-oidc` verifies an OpenID Connect provider's tokens:

```kotlin
data class Caller(val subject: String, val groups: Set<String>)

val provider = oidc(issuer = "https://id.example.com", audience = "bookmarks")
val caller = authenticated(provider.scheme) { id -> Caller(id.subject, id.groups) }

val myAccount = endpoint {
    get("account")
    authenticatedBy(caller)
    json<Account>()
}

val routes = listOf(myAccount handledNow { Account(this[caller].subject) })

api(routes, JacksonCodecs) { authenticate(provider.scheme, provider) }
```

It fetches the provider's keys on first use and checks the signature, issuer,
audience, expiry and algorithm. The same value can sign people in to pages
with the authorization-code flow, keeping the session in an encrypted cookie.
Tests call as anyone with `TestCallers` from `pelican-test`. Pelican says who
is calling; what they may do stays in the service's own code. See
[Who is calling](docs/reference.md#who-is-calling) and
[spec 0061](specs/0061-who-is-calling.md).

## Errors, limits and the rest

An unhandled exception becomes a 500 with an opaque reference. The stack trace
goes to the log with the same reference, so the two can be matched up without
showing internals to callers. Declared failures are not affected. Request
bodies are limited by `maxBodyBytes` (8 MiB by default), `covers = ...` makes
an unbound endpoint a start-up failure, and `cors("https://app.example.com")`
answers preflight requests from what the endpoints already declare. See
[Errors](docs/reference.md#errors-and-what-a-caller-is-told),
[Limits and startup checks](docs/reference.md#limits-and-startup-checks) and
[CORS](docs/reference.md#cors).

Descriptions carry a `KType` and no serializer, so the JSON library is a single
argument: `api(routes, codecs = JacksonCodecs)`, or
`JacksonCodecs(myObjectMapper)` to use your own mapper. See
[Choosing a JSON library](docs/reference.md#choosing-a-json-library).

## Testing

`pelican-test` uses the same descriptions to build a typed client, and
`pelican-test-pekko` runs it against a Pekko `Api`, in memory or over a socket:

```kotlin
val app = bookmarksApi().inMemory()          // no socket; or .start().client() for a real one

val bookmark: Bookmark = app.call(getBookmark, 1L)
app.outcome(getBookmark, 9_999L) shouldBeError NoSuchBookmark(9_999L, "No bookmark 9999")
```

There are no path strings and no hand-written JSON, so renaming an input makes
the tests stop compiling instead of returning 404s. The assertions throw plain
`AssertionError`, so the module adds no matcher library to your classpath. Each
suite runs both in memory and over a real socket.

Because the client and the server are built from the same description, a
rename changes both at once. So the URLs your callers use are pinned
separately, against literal strings, in the one test that should fail on a
rename:

```kotlin
app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"
app.request(listBookmarks, In2(20, Slug("streams"))) shouldBuild "GET /bookmarks?limit=20&tag=streams"
```

Some breaking changes are not caught by pinned URLs, such as a new required
field in a request body or a deleted endpoint. `pelican-test-golden` records
what the descriptions publish, one file per endpoint, and fails when a change
would break existing callers:

```kotlin
private val golden = Golden()

@Test fun `every endpoint publishes what it published`() {
    golden.operations(bookmarksSpec())      // one file per endpoint
}
```

```
post-bookmarks.json — 1 change breaks callers.

  POST /bookmarks
    ✖ `folder` in the request body (application/json) is new and required
        every caller that is not sending it is refused
```

A new optional parameter updates the golden file and passes; a breaking change
fails. The same check runs from Gradle as `check<Name>Document`. See
[Golden files](docs/golden-testing.md) and
[Testing](docs/reference.md#testing).

## Stubbing another service

When the code under test is the caller and the API belongs to somebody else,
`pelican-test-wiremock` runs a WireMock server stubbed with that API's endpoint
values, written by hand or imported from its OpenAPI document. Each answer is
an `Outcome` the endpoint declares, rendered by the same code a server uses.
This test, from the example module, points the generated client at it:

```kotlin
@JvmField
@RegisterExtension
val orders = PelicanWireMockExtension(JacksonCodecs)

private val client by lazy { OrdersClient(orders.baseUrl, JacksonCodecs, PekkoHttpTransport()) }

@Test
fun `a stubbed declared failure reaches the client as the failure it declares`() {
    orders.stub(getUser, 2L) answers noSuchUser(ApiError(404, "no such user"))

    client.getUser(2L) shouldBe
        GeneratedOutcome.Err(GetUserFailure.NotFound(GeneratedApiError(404, "no such user")))
}
```

A request matches a stub when Pelican's own routing and decoding turn it into
the stubbed input, so query order and encoding do not matter. `verify` and
`calls` check what was sent. On `main` and not yet released, `stubFile` writes
the same stubs out as WireMock mapping files for a demo or a local run, and
fails when the contract no longer agrees with a committed file. See
[Stubbing somebody else's service](docs/reference.md#stubbing-somebody-elses-service).

## Clients for your callers

Callers who cannot depend on your descriptions get a generated Kotlin client
instead. The Gradle plugin's `clients` entry runs `pelican-codegen` over the
same descriptions the server uses. The client sends through a
`ClientTransport`, and the one that ships is `PekkoHttpTransport` from
`pelican-client-pekko`:

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs, PekkoHttpTransport(system))
```

Each declared response becomes a member of a sealed type the caller can match
on. On `main` and not yet released, `pelican-metrics-otel` adds
`transport.traced(sdk)`, which opens a client span for each call and sends the
current `traceparent`.

The other direction is `pelican-import`: the plugin's `endpoints` entry reads
an OpenAPI document somebody else wrote into endpoint descriptions, as Kotlin
source, which you can then serve, call or stub. See
[A generated Kotlin client](docs/generated-client.md) and
[Importing an OpenAPI document](docs/importing.md).

## Backends

The backend only affects handlers: bind the same endpoint values with another
module's binders, and the only thing that changes is the type a streaming
handler returns. 1.0 ships `pelican-pekko`. The http4k and Ktor interpreters
were complete and passing when they were taken off `main` before 1.0; commit
[`a6c20b2`](https://github.com/matthewjones372/pelican/tree/a6c20b20350dddb21ac4cbf2d22f88eb8756081e) has them, with a parity suite that
runs the same descriptions against all three and checks that they respond
identically, down to the generated OpenAPI document being the same string. `example/backends/` shows how this works: the
descriptions are in one file, with one binding file per backend.

---

# Appendix

## Longer documents

| Page | What it covers |
|---|---|
| [Cookbook](docs/cookbook.md) | Complete recipes, roughly in the order you need them: typed inputs, declared failures, forms, uploads, streaming, security, filters, testing. |
| [Reference manual](docs/reference.md) | The long form: every module, the trade-offs, and the limitations. |
| [Choosing between Pelican and the alternatives](docs/choosing.md) | When http4k's contracts, Ktor's plugins, Spring, Micronaut, Quarkus, tapir or a hand-written document are a better fit. |
| [A whole service, in one file](docs/a-whole-service.md) | Models, inputs, endpoints, handlers, store, server and docs, compiled on every build. |
| [A generated Kotlin client](docs/generated-client.md) | What callers get when they cannot use the descriptions directly. |
| [Importing an OpenAPI document](docs/importing.md) | Reading a document somebody else wrote into descriptions: what comes out and what is refused. |
| [The same endpoints, by hand](docs/by-hand.md) | The same two endpoints written directly against Pekko HTTP, for comparison. |
| [Golden files](docs/golden-testing.md) | A test that fails when a change would break existing callers, and passes when it would not. |
| [Tools a model can call](docs/mcp.md) | The endpoints as MCP tools: how they map, what is refused, and where credentials come from. |
| [A schema that resolves on its own](docs/schemas.md) | A derived JSON Schema for tools that do not read your OpenAPI document. |
| [Modules](docs/modules.md) | What each module is for and what it depends on. |
| [What it costs](docs/what-it-costs.md) | JMH measurements of the interpreter against hand-written routes, with baselines and error bars. |
| [Roadmap](docs/roadmap.md) | What is not built yet, and roughly the order it will be built in. |

## Running the examples

```bash
./gradlew build                          # all modules: tests, detekt, spotless, coverage
./gradlew :example:runReadmeExample      # the bookmarks service, on :8080
./gradlew :example:run                   # the fuller orders API (streaming, SSE, raw bodies)
./gradlew :example:runBackends           # the greetings service, through the backend seam
./gradlew :example:runCodecs             # the notes service, over a codec module it does not name
./gradlew :example:runSecured            # a filter enforcing the security the descriptions declare
./gradlew :example:runTelemetry          # meters and spans from one set of descriptions, at /admin/report
./gradlew :example:runShop               # a bookshop: three domain failures, three declared responses
./gradlew :example:runMcp                # the orders API with its tools served on /mcp
./gradlew :example:generateOrdersDocument  # the spec, with no server started
./gradlew :example:generateOrdersClient    # the Kotlin client, likewise
```

`runFirstEndpoint` and `runBookmarks` are also available, and every example
takes a port with `--args=8081`. The two generator tasks come from the
repository's own Gradle plugin and do not start a server. `pelican-openapi` and
`pelican-codegen` depend only on core, so neither needs an HTTP library.

## On main, not yet released

These landed on `main` after the 1.0.0-RC3 tag and are not on Maven Central
yet. To try them, build from `main` with `./gradlew publishToMavenLocal` (see
[Install](#install)).

- `pelican-oidc`, a module of its own, and callers in core: an endpoint
  declares who is calling, verified before the handler
  ([0061](specs/0061-who-is-calling.md)).
- Synchronous handlers run on virtual threads
  ([0058](specs/0058-a-handler-that-blocks.md)).
- `pages`, a classpath directory served beside the endpoints
  ([0059](specs/0059-pages-served-beside-an-api.md)).
- `toOutcome { }` in `pelican-arrow`, for an `Either` with several declared
  failures ([0060](specs/0060-an-either-with-several-failures.md)).
- Two failures under one status, told apart by a tag, in the server, the
  document and the generated client
  ([0063](specs/0063-two-failures-under-one-status.md),
  [0066](specs/0066-tagged-failures-in-the-document.md)).
- Golden checks that compare a `oneOf` or `anyOf` branch by branch
  ([0065](specs/0065-golden-checks-through-oneof.md)).
- `stubFile` in `pelican-test-wiremock`, including stubs templated from the
  path and the request body
  ([0062](specs/0062-stub-files-that-keep-the-contract.md),
  [0067](specs/0067-stubs-templated-from-the-body.md)).
- `traced`, a client transport that carries the trace, in
  `pelican-metrics-otel` ([0064](specs/0064-a-client-that-carries-the-trace.md)).
- `RetryScheduler`, so a test can control the waits between client retries
  ([0057](specs/0057-the-wait-a-test-can-hold.md)).
- `TestCallers`, `signedInAs` and a backend-free `inMemory()` in
  `pelican-test`.
- The library modules on `main` are compiled for Java 25, where RC3 was
  compiled for Java 21. The Gradle plugin is still compiled for Java 21.

[The changelog](CHANGELOG.md) records the breaking changes among these.

## Stability

The current release is 1.0.0-RC3, a candidate for 1.0. Its API is frozen and
checked by the build. From 1.0, the public API of the shipped modules is
stable, and breaking changes will wait for a major release. Until then, a
release candidate can still change if real use turns up problems, and any
breaking change before 1.0 is recorded in [the changelog](CHANGELOG.md).

Two things in the repository define what is covered, and both are enforced by
the build. The `.api` dump beside each module is the binary contract: if a
signature is in the dump it is covered, and `apiCheck` fails when one changes.
`StillCompilesTest` covers the reified inline part of the DSL, which a bytecode
dump cannot see. `json<T>()`, `pathParam<T>()`, `errorJson<T>()` and the rest
are compiled as source at the call site, so the suite compiles pinned call
sites against the published modules.

Not covered: anything `internal`; the exact bytes of the emitted document (use
[golden files](docs/golden-testing.md) to pin what your own callers depend
on); and the http4k, Ktor and other modules taken off `main` before 1.0, until
they return. The full statement is in the reference
manual under [Stability](docs/reference.md#stability), and every breaking
change is recorded in [the changelog](CHANGELOG.md).

## Versions

The supported Kotlin, Pekko and JDK versions are listed in the reference
manual under [Versions](docs/reference.md#versions). They are kept in one
place so the lists cannot disagree.

## License

Apache 2.0. See [LICENSE](LICENSE).
