<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/pelican-mark-dark.svg">
  <img src="docs/assets/pelican-mark-light.svg" width="88" height="88" alt="">
</picture>

# Pelican

**Type-safe HTTP for Kotlin.** Describe an endpoint once — get the server
route, the OpenAPI document and a typed client from that one description.

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
endpoint is — its path, its inputs, the types it can return — as an ordinary
Kotlin value. Pelican derives the rest from that one value: the server route,
the OpenAPI document, a typed test client, and a generated Kotlin client for
your callers.

The endpoint description is the source of truth for the HTTP contract. There
is no annotation scanning and no hand-maintained YAML, and nothing to keep in
step, because there is no second description.

It is a library rather than a framework: it does not own your `main`, and it
serves through a web stack you already run — Pekko HTTP, for 1.0. If you know
tapir from Scala, this is that idea, scoped to what Kotlin's type system can
express without implicits.

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

Change the path parameter's type, the response type or the declared error, and
the handler stops compiling.

## Why Pelican

**Your docs cannot drift.** The OpenAPI document — 3.1.0, or 3.2.0 on request —
is generated from the endpoint values your server is built from, so there is no
second source of truth to forget. See
[Which version the document says](docs/reference.md#which-version-the-document-says-and-how-to-choose)
for how to choose.

**Handlers get typed arguments.** A path parameter declared as `Long` arrives
as a `Long`. No `Params` bag, no casting, no `String.toLong()` in every
handler.

**Bad input is rejected before your code runs.** Constraints live on the input
value, so the same rule that refuses a request also appears in the schema —
`between(1, 100)` is the check and the `minimum`/`maximum`.

**Every response is part of the signature.** `orFail` puts a failure in the
endpoint's type and `or` puts a second success there — a `200 Order` beside a
`202 Accepted`. The handler names the one it is producing, and the generated
client gets a sealed type to match on.

**Tests call endpoints, not URLs — then pin the URLs on purpose.** Behaviour
tests name endpoint values, so a rename stops them compiling instead of
starting to 404. The wire contract your callers hold is pinned separately, one
line per endpoint: `app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"`.

**Your descriptions do not belong to the backend.** 1.0 ships one backend and
one codec — Pekko HTTP and Jackson — deliberately: the pair a first release can
fully stand behind. The http4k and Ktor interpreters and the kotlinx and
jsoniter codecs already exist, pass the same parity suites, and sit on the
[`multi-backend`](https://github.com/matthewjones372/pelican/tree/multi-backend)
branch until they return after 1.0. Your descriptions will not change when they
do.

**Performance is measured, not asserted.** The interpreter is benchmarked with
JMH against the same routes written by hand, with the baselines and error bars
in [what it costs](docs/what-it-costs.md). On a realistic endpoint the
difference is a fraction of a percent of the request.

## Contents

**[Getting started](#getting-started)** — install, a first endpoint, and what
the compiler catches.

**[The endpoint model](#the-endpoint-model)** — inputs and validation, declared
failures, multiple responses, streaming, and the rest of what a description can
say.

**[Serving and testing](#serving-and-testing)** — running a server, filters and
metrics, the typed test client and golden files.

**[Appendix](#appendix)** — the longer documents, the runnable examples,
[stability](#stability) and [versions](#versions).

The reference manual, with the reasoning behind each design decision, is
[docs/reference.md](docs/reference.md).

---

# Getting started

## Install

The current release is **0.2.0**, on Maven Central under
`io.github.matthewjones372`; the release candidate, **1.0.0-RC1**, is what this
page describes. Until its tag lands, `./gradlew publishToMavenLocal` builds it
from source.

```kotlin
dependencies {
    // The interpreter. Brings pelican-core and Pekko HTTP itself transitively.
    implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC1")
    // The codec module: Jackson, and the schemas the document derives.
    implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC1")
    // /openapi.json and Swagger UI beside the endpoints — startWithDocs lives here.
    implementation("io.github.matthewjones372:pelican-pekko-docs:1.0.0-RC1")
    // The typed test client.
    testImplementation("io.github.matthewjones372:pelican-test:1.0.0-RC1")
}
```

Those four lines are the whole canonical stack — everything on this page
compiles against them. [Modules](docs/modules.md) lists all eighteen and what
each depends on. New since 0.2.0: `pelican-mcp-server`, `pelican-pekko-mcp`
and `pelican-arrow`.

The Gradle plugin is `io.github.matthewjones372.pelican`. It publishes to Maven
Central rather than the Gradle Plugin Portal, so the build needs telling once:

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
plugins { id("io.github.matthewjones372.pelican") version "1.0.0-RC1" }
```

To build against unreleased changes, `./gradlew publishToMavenLocal` installs
the modules and `./gradlew -p pelican-gradle-plugin publishToMavenLocal` the
plugin. The version between tags comes from the nearest tag — a `-SNAPSHOT` of
the next version on an untagged commit — rather than from a number written
down anywhere.

## Your first endpoint

One endpoint, the server that serves it, and its tests. Everything here is
compiled — it is
[`FirstEndpoint.kt`](example/src/main/kotlin/example/hello/FirstEndpoint.kt) in
the example module, so the front page cannot drift from what runs.

```kotlin
// Needs pelican-pekko, pelican-jackson and pelican-pekko-docs — the Install
// block above, minus the test line.
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
> `./gradlew :example:runFirstEndpoint` serves this on `:8080` — the endpoint
> and a Swagger UI page for it, from the one value above.

The document at `/openapi.json` and the Swagger UI page at `/api-docs` are
built from the same value. There is no second file describing this endpoint,
and nothing scanned an annotation to find it.

The test names the endpoint rather than the URL, and then pins the URL on
purpose, because those are two different promises — one to your own code, one
to your callers:

```kotlin
private val app = greetings().inMemory("first-endpoint")

app.call(greet, "world") shouldBe Greeting("Hello, world!")
app.request(greet, "world") shouldBuild "GET /hello/world"
```

Rename `who` or change `Greeting` and the handler and the first assertion stop
compiling. The second answers a different question: whether the URL your
callers hold still exists.

## What the compiler catches

Each of these came from feeding the mistake to the compiler:

```
+(getUser handledNow { id: String -> ... })
  e: Argument type mismatch: actual type is 'Params.(String) -> User', but 'Params.(Long) -> User' was expected.

+(watchOrders streamedNow { (_, max) -> Source.single("not a tick") })
  e: Return type mismatch: expected 'Source<Tick, NotUsed>', actual 'Source<String!, NotUsed!>!'.

+(getBookmark handledOrFail { id -> Bookmarks.find(id)!! })
  e: Return type mismatch: expected 'Outcome<NoSuchBookmark, Bookmark>', actual 'Bookmark'.
```

`DoesNotCompileTest` compiles each of those on every build and asserts the
line, so a wording this page invented would fail rather than persuade.

Some constraints are beyond the type system — a path parameter missing from the
path, a scope its scheme never granted, a missing codec. Those are checked when
the endpoint value or the `Api` is constructed, so they fail at start-up rather
than on the first request:

```
GET /things declares path parameter 'stray', but the path is /things
```

---

# The endpoint model

A short tour. Each section here is one idea and an example; the
[reference manual](docs/reference.md) carries the full treatment, and the
[cookbook](docs/cookbook.md) has complete, paste-ready recipes.

## Inputs and validation

Inputs are values, declared once and reused by the route, the decoder, the
document and the test client. A refinement narrows what is accepted *and*
reaches the schema:

```kotlin
val bookmarkId = pathParam<Long>("bookmarkId", description = "The bookmark's id")
val limit      = queryParam("limit", IntCodec.between(1, 100), description = "How many to return").default(20)
val tag        = queryParam("tag", slug, description = "Only bookmarks with this tag").optional()
```

Swagger UI reads the published constraints and refuses to send a request the
server would reject. A request that gets through anyway is a 400 before any
handler runs, naming the parameter and the rule it broke.

Ready-made codecs cover the primitives, `UUID`, the `java.time` types, `URI`
and any Kotlin `enum`; your own types take three lines with
`PlainCodec.mapOrFail` and validate on the way in. Multi-valued parameters —
`?tag=a&tag=b`, `?id=1,2` — decode to typed lists. See
[Refined inputs](docs/reference.md#refined-inputs) and
[More than one value](docs/reference.md#more-than-one-value).

## Declared failures

`orFail` puts the failure in the endpoint's type, so the handler has to produce
it and the response body is the type the document promised. An endpoint can
declare several; the handler names which one it is returning:

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

The status comes from the declaration rather than the payload's type, so two
failures can share a payload. Give the failures a sealed supertype and the
`when` is exhaustive. A failure can carry response headers — a `Retry-After` on
a 429 — declared on that one failure and supplied where it is produced.

An endpoint declaring a single failure can skip the naming: `err(value)` is
`ok`'s other half and means that one declared failure. An Arrow codebase
converts at the edge instead — `service.find(id).toOutcome()`, from
`pelican-arrow`, reads a `Right` as `ok` and a `Left` as `err`. See
[Declared failures](docs/reference.md#declared-failures).

## More than one successful response

A failure is one *alternative*, not a special kind of thing. `or` declares
several successes the same way — a `201 Order` beside a `202 Queued` — and the
handler names the one it is producing with `handledOneOf`. The document
publishes both statuses with their own schemas and headers, and the generated
client hands the caller a sealed type, one member per status. See
[More than one successful response](docs/reference.md#more-than-one-successful-response).

One response can also be *written* several ways —
`negotiated(json<Report>(), media<Report>("text/csv"))` — with the caller's
`Accept` choosing the rendering. See
[One response, several renderings](docs/reference.md#one-response-several-renderings).

## Streaming

```kotlin
ndjson<Order>()             // one JSON document per line
sse<Tick>(eventName = "order", id = { it.sequence.toString() })
jsonArray<Order>()          // `[{...},{...}]`, flushed as produced
bytes()                     // opaque, never buffered
```

Handlers return the backend's own stream type — a `Source`, on Pekko — and
back-pressure runs from the socket to the source. The test suite throttles a
source and fails if the first frame does not arrive well before the last, so a
response that quietly buffers is caught as a bug. SSE streams can carry event
ids and a retry directive, so a caller can resume where it left off; a request
body can be a typed stream too, with `ndjsonIn<T>()`. See
[Streaming](docs/reference.md#how-streaming-stays-backend-agnostic).

## Cookies, forms and uploads

Cookies, form bodies, multipart text parts and file uploads are inputs like
any other: same codecs, same `optional()`/`default(v)`, same refinements, same
400s. A form body decodes against its published schema, a streamed file part is
handed over without buffering, and a buffered part declares the bound it may
cost. `or` lets one payload arrive as a form *or* as JSON, with `Content-Type`
picking the decode. See
[Cookies](docs/reference.md#cookies),
[Form bodies](docs/reference.md#form-bodies) and
[Multipart uploads](docs/reference.md#multipart-uploads).

## Response headers

Declared on the endpoint with `emits(...)`, set from the handler with
`setHeader`, published in the document with their schemas. Setting an
undeclared header throws, so the document and the wire cannot drift. A header
that belongs to one response — the `Location` on a 201 — is declared on that
response alone. See [Response headers](docs/reference.md#response-headers).

## Webhooks

OpenAPI's `webhooks` are the calls a service *sends*. A webhook is the same
description read in the other direction — a name, a method, a body, no path,
because the path belongs to whoever subscribed. The document publishes it under
`webhooks`, the generated client grows a sender for it, and binding one as a
route fails at construction. See
[Webhooks](docs/reference.md#webhooks-the-calls-the-service-sends).

## Tools a model can call

The same descriptions, read once more — as MCP tools. An endpoint already
knows its name, its arguments, their constraints and its result shape, which is
everything a tool description needs. Arguments decode through the same codecs
an HTTP request uses, calls run through the handler the route already has, and
declared failures come back as results a model can act on. `mcpServe(api)`
speaks the protocol over stdio, and `pelican-pekko-mcp` mounts it on `/mcp` —
with no MCP SDK on the classpath. See
[Tools a model can call](docs/mcp.md).

---

# Serving and testing

## Running a server

```kotlin
ordersApi().start(port = 8080)                                  // endpoints only, on 127.0.0.1
ordersApi().start(port = 8080, host = "0.0.0.0")                // every interface, said out loud
ordersApi().startWithDocs(port = 8080, docs = ordersDocs)       // plus /openapi.json and /docs
```

Loopback by default — listening on the network is a choice you write down. The
handle is `AutoCloseable` with `block()`, `stop()` and `stopAsync()`. A service
that already has an `ActorSystem` hands it over — `start(system, port = 8080)`
— and whoever created a system is who ends it.

Pelican does not have to own the port. `toRoute(system)` returns a Pekko
`Route` and binds nothing, so described endpoints go in beside routes you
already have — which is what adopting Pelican one endpoint at a time looks
like:

```kotlin
ordersApi().start(port = 8080) { system ->
    Directives.concat(health, toRoute(system))
}
```

## Filters

A filter runs around every handler and sees the request with its inputs already
decoded. Rejecting is throwing — `unauthorized()`, `forbidden()`,
`tooManyRequests(retryAfterSeconds = 3600)` — and what a filter works out
travels to the handler in a typed *attribute*:

```kotlin
val caller = attribute<Caller>("caller")

val requireToken = before { p ->
    p[caller] = tokens[p[authorization]?.removePrefix("Bearer ")]
        ?: unauthorized("Present a bearer token")
}

api(endpoints, JacksonCodecs) { filter(requireToken) }
```

A filter can read the endpoint it matched, which is what makes the
one-line integrations possible: `filter(metrics(registry))` gives Micrometer
meters tagged by method, path template and status
(`pelican-metrics`), and `filter(openTelemetry(sdk))` gives a `SERVER` span per
request named from the description (`pelican-metrics-otel`). Refusals answered
before the chain — a body over the limit, a parameter that will not decode —
are counted by `onRefusal(refusalCounter(...))`, which no filter could see. See
[Filters](docs/reference.md#filters) and [Metrics](docs/reference.md#metrics).

## Errors, limits and the rest

An unhandled exception is a 500 carrying an opaque reference; the stack trace
goes to the log with the same reference, so the two join up without leaking
internals to callers. Declared failures are unaffected. Request bodies are
bounded by `maxBodyBytes` (8 MiB by default), `covers = ...` makes an unbound
endpoint a start-up failure, and `cors("https://app.example.com")` answers
preflights from what the endpoints already declare. See
[Errors](docs/reference.md#errors-and-what-a-caller-is-told),
[Limits and startup checks](docs/reference.md#limits-and-startup-checks) and
[CORS](docs/reference.md#cors).

Descriptions carry a `KType` and no serializer, so the JSON library is one
argument: `api(routes, codecs = JacksonCodecs)`, or
`JacksonCodecs(myObjectMapper)` to bring your own mapper. See
[Choosing a JSON library](docs/reference.md#choosing-a-json-library).

## Testing

`pelican-test` interprets the same descriptions a third way, as a typed client:

```kotlin
val app = bookmarksApi().inMemory()          // no socket; or .start().client() for a real one

val bookmark: Bookmark = app.call(getBookmark, 1L)
app.outcome(getBookmark, 9_999L) shouldBeError NoSuchBookmark(9_999L, "No bookmark 9999")
```

No path strings and no hand-written JSON: rename an input and the tests stop
compiling instead of starting to 404. The assertions throw plain
`AssertionError`, so the module puts no matcher library on your classpath, and
every suite runs both in memory and over a real socket.

Because the client and the server are built from the same description, a rename
moves both ends at once — so the URL your callers hold is pinned separately,
against literals, in the one test that *should* fail on a rename:

```kotlin
app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"
app.request(listBookmarks, In2(20, Slug("streams"))) shouldBuild "GET /bookmarks?limit=20&tag=streams"
```

What nothing pins by hand is the change that costs most: a required field added
to a request body, an endpoint deleted. `pelican-test-golden` records what the
descriptions publish, one file per endpoint, and fails when a change breaks the
callers you already have:

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

A new optional parameter updates the golden and passes; a break fails. The same
check runs from Gradle as `check<Name>Document`. See
[Golden files](docs/golden-testing.md) and
[Testing](docs/reference.md#testing).

## Backends

The backend is a choice about handlers: bind the same endpoint values with
another module's binders and the only thing that changes is the type a
streaming handler returns. 1.0 ships `pelican-pekko`; the http4k and Ktor
interpreters are complete and green on the
[`multi-backend`](https://github.com/matthewjones372/pelican/tree/multi-backend)
branch, where one parity suite runs the same descriptions against all three and
asserts they answer byte for byte — down to the generated OpenAPI document
being the identical string. `example/backends/` is the seam demonstrated:
descriptions in one file, a binding file per backend.

---

# Appendix

## Longer documents

| Page | What it answers |
|---|---|
| [Cookbook](docs/cookbook.md) | Complete recipes in the order people need them — typed inputs, declared failures, forms, uploads, streaming, security, filters, testing. |
| [Reference manual](docs/reference.md) | The long form: every module, every trade-off, and the limitations spelled out. |
| [Choosing between Pelican and the alternatives](docs/choosing.md) | Where http4k's contracts, Ktor's plugins, Spring, Micronaut, Quarkus, tapir or a hand-written document are the better answer. |
| [A whole service, in one file](docs/a-whole-service.md) | Models, inputs, endpoints, handlers, store, server, docs — compiled every build. |
| [A generated Kotlin client](docs/generated-client.md) | What callers who cannot hold the descriptions get instead. |
| [Importing an OpenAPI document](docs/importing.md) | A document somebody else wrote, read into descriptions: what comes out, what is refused. |
| [The same endpoints, by hand](docs/by-hand.md) | The same two endpoints written directly against Pekko HTTP, so the difference is readable rather than asserted. |
| [Golden files](docs/golden-testing.md) | A test that fails when a change would break existing callers, and stays quiet when it would not. |
| [Tools a model can call](docs/mcp.md) | The endpoints as MCP tools: what becomes what, what is refused, where a credential comes from. |
| [A schema that resolves on its own](docs/schemas.md) | A derived JSON Schema for anything that does not hold your OpenAPI document. |
| [Modules](docs/modules.md) | What each module is for and what it depends on. |
| [What it costs](docs/what-it-costs.md) | The interpreter measured by JMH against hand-written routes, with baselines and error bars. |
| [Roadmap](docs/roadmap.md) | What is not built yet and the order it is worth building in. |

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

`runFirstEndpoint` and `runBookmarks` are there too, and every example takes a
port with `--args=8081`. The two generator tasks come from the repository's own
Gradle plugin and start nothing: `pelican-openapi` and `pelican-codegen` depend
on core alone, so neither needs an HTTP library present.

## Stability

The current release is 0.2.0. 1.0 is at release-candidate stage: the API
surface it promises is frozen and guarded now, and the promise itself — the
public API of the shipped modules is stable, and a breaking change waits for a
major release — takes effect from 1.0.

Two things in the repository say what that covers, and both fail the build
rather than being promised in prose. The `.api` dump beside each module is the
binary contract — if a signature is in the dump, it is promised, and `apiCheck`
fails when one changes. `StillCompilesTest` is the contract for the reified
inline half of the DSL, which a bytecode dump cannot see: `json<T>()`,
`pathParam<T>()`, `errorJson<T>()` and the rest are compiled against as source,
so the suite compiles pinned call sites against the published modules.

Outside it: anything `internal`, the emitted document's byte-for-byte shape —
[golden files](docs/golden-testing.md) are how you pin what your own callers
hold — and the modules on the
[`multi-backend`](https://github.com/matthewjones372/pelican/tree/multi-backend)
branch, which are not covered until they return to `main`. The full statement
is in the reference manual at [Stability](docs/reference.md#stability), and
every break is recorded in [the changelog](CHANGELOG.md).

## Versions

Which Kotlin, which Pekko, which JDK: the reference manual holds the list, at
[Versions](docs/reference.md#versions). It was copied here too until the two
copies drifted apart, so there is one of them now.

## License

Apache 2.0 — see [LICENSE](LICENSE).
