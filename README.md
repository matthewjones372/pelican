<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/pelican-mark-dark.svg">
  <img src="docs/assets/pelican-mark-light.svg" width="88" height="88" alt="">
</picture>

# Pelican

Typed HTTP endpoints for Kotlin. Describe an endpoint once; serve it, document
it, test it and generate its client from that one description.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.matthewjones372/pelican-core?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/io.github.matthewjones372/pelican-core)
[![build](https://github.com/matthewjones372/pelican/actions/workflows/build.yml/badge.svg)](https://github.com/matthewjones372/pelican/actions/workflows/build.yml)
[![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

[Getting started](#getting-started) · [Cookbook](docs/cookbook.md) ·
[Reference manual](docs/reference.md) · [Modules](docs/modules.md) ·
[What it costs](docs/what-it-costs.md)

</div>

---

Pelican is a Kotlin library in which an HTTP endpoint is an ordinary value: its
method and path, its typed inputs, and every response it can give, successes
and failures alike. From that value Pelican derives the server route, the
OpenAPI document, a typed client for your tests and a generated Kotlin client
for your callers.

The problem it addresses is that an HTTP contract usually lives in four places.
The handler decides what the server actually accepts and returns. An OpenAPI
file, written by hand or scanned from annotations, says what it accepts and
returns. Client models say it again, and tests say it a fourth time with URL
strings and JSON literals. Each copy is maintained separately, so they drift:
a parameter is renamed in the handler but not the document, a 404 is returned
that no client knows about, a test keeps passing against a URL nobody serves.

In Pelican there is one copy. The route, the document and both clients are
read off the same endpoint value, so they cannot describe different APIs, and
the parts that the type system can check are checked by the compiler.

## One endpoint, and what follows from it

A description, then the handler that answers it. This is taken from
[`ReadmeExample.kt`](example/src/main/kotlin/example/readme/ReadmeExample.kt),
which is compiled on every build.

```kotlin
data class Bookmark(val id: Long, val url: String, val title: String, val tags: List<String> = emptyList())
data class NoSuchBookmark(val id: Long, val message: String)

val bookmarkId      = pathParam<Long>("bookmarkId", description = "The bookmark's id")
val bookmarkMissing = errorJson<NoSuchBookmark>(404, "No bookmark with that id")

val getBookmark = endpoint(bookmarkId) {
    get("bookmarks" / bookmarkId)
    summary = "Fetch one bookmark"
    json<Bookmark>() orFail bookmarkMissing
}

val route = getBookmark handledOrFail { id ->                    // id: Long, already decoded
    Bookmarks.find(id)?.let { ok(it) }                           // ok(...) has to carry a Bookmark
        ?: bookmarkMissing(NoSuchBookmark(id, "No bookmark $id")) // the 404 the endpoint declared
}
```

`getBookmark` holds no handler and does no work. It is data, and four things
are read from it.

**The handler receives decoded, typed arguments.** `id` is a `Long`. The
handler never sees a string, and a request for `/bookmarks/abc` is answered
with a 400 before the handler runs. A constraint such as
`IntCodec.between(1, 100)` on a query parameter is both the check the server
makes and the `minimum`/`maximum` in the published schema, so the document
cannot promise a range the server does not enforce.

**Every response is part of the endpoint's type.** `orFail bookmarkMissing`
changes the type the handler must return to
`Outcome<NoSuchBookmark, Bookmark>`. A handler that returns a bare `Bookmark`,
or forgets that the 404 exists, does not compile:

```
getBookmark handledOrFail { id -> Bookmarks.find(id)!! }
  e: Return type mismatch: expected 'Outcome<NoSuchBookmark, Bookmark>', actual 'Bookmark'.

getUser handledNow { id -> "a string" }
  e: Return type mismatch: expected 'User', actual 'String'.
```

These are the compiler's own messages. `DoesNotCompileTest` compiles each
mistake on every build and checks the message, so they cannot go out of date.
The consequence is that the failures a caller can receive are a closed,
declared set: the document lists them, the generated client gives the caller a
sealed type to match on, and an exception nobody declared becomes an opaque
500 with a reference in the log rather than a leaked stack trace.

**The OpenAPI document is generated from the same value the server routes
on.** `/openapi.json` (OpenAPI 3.1.0 by default, or 3.2.0) and the Swagger UI
page beside it are built from `getBookmark`, not from a parallel file or from
annotations a scanner reads. If the route changes, the document changes in the
same commit, because there is nothing else to edit.

**Tests call endpoint values, and URLs are pinned separately.** The typed test
client is built from the same description:

```kotlin
val bookmark: Bookmark = app.call(getBookmark, 1L)
app.outcome(getBookmark, 9_999L) shouldBeError NoSuchBookmark(9_999L, "No bookmark 9999")

app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"
```

The first two lines contain no path and no JSON, so renaming `bookmarkId` or
changing `Bookmark` stops the test compiling instead of turning it into a 404
at run time. That same property means a behaviour test cannot notice when the
public URL moves, because the client moves with the server. The third line is
the guard for that: a URL pin, kept in a file of its own, compares against a
literal string and is the one test that should fail on a rename.
`pelican-test-golden` goes further and fails when a change to the published
document would break existing callers, such as a new required body field.

Some mistakes are beyond the type system: a declared path parameter missing
from the path, a codec missing for a body type, a security scope the scheme
never granted. Those are checked when the endpoint value or the `Api` is
constructed, so they fail at start-up rather than on the first request.

## How it fits into a service

Pelican is a library, not an application framework. It does not own `main`,
it has no registry or annotation scanning, and nothing has to run at start-up
to discover endpoints. A set of routes is a `List` you build, split across
files or filter like any other value.

Descriptions are independent of the server. A backend module interprets them
onto a web server you can mount beside the handlers you already have:

- **Pekko HTTP** (`pelican-pekko`): the descriptions become a Pekko `Route`.
  `toRoute(system)` concatenates with your existing routes, and
  `start(system, port = 8080)` serves on an actor system the service already
  runs. This is the backend in the current release, 1.0.0.
- **Jetty 12** (`pelican-jetty`): the descriptions become a Jetty core
  `Handler`, with no Pekko or Scala on the classpath. `toHandler()` passes on
  any request it does not answer, so it sits in a `Handler.Sequence` beside
  handlers of your own. It is on `main` and not yet in a release; see
  [Getting started](#getting-started).

Because a Pelican route passes on what it does not describe, you can adopt it
one endpoint at a time in an existing service. A parity suite runs the same
descriptions against both backends and checks that they answer identically,
down to producing the same OpenAPI document; the few recorded differences are
listed under [On Jetty](docs/reference.md#on-jetty). Changing backend changes
the binder imports and the type a streaming handler returns (a Pekko `Source`
or a Kotlin `Sequence`), not the descriptions.

`pelican-core` depends on the Kotlin standard library and `jackson-core`'s
streaming parser and nothing else, and a test enforces that. Every JSON
library, server and HTTP client lives in a separate module, so a build takes
only what it uses.

### Compared with writing the handlers by hand

A hand-written handler is the most direct way to answer a request, and for a
small internal API it may be all you need. What it does not give you is a
second, machine-checked statement of what it does. The document, the client
and the tests each restate the contract, and keeping them in step is a matter
of discipline and review.

With Pelican you write the description and the handler. The decoding, the
400s for bad input, the document, the test client and the generated client
come from the description, and the compiler checks that the handler agrees
with it. [The same endpoints, by hand](docs/by-hand.md) shows two endpoints
written both ways, against Pekko HTTP with swagger annotations, so the
difference can be read rather than taken on trust.

The costs are real:

- **A DSL to learn.** Inputs, outputs and failures are Pelican's types, and
  some HTTP behaviour you would write directly is instead declared.
- **Two backends.** If your service runs on Spring MVC, Ktor, Vert.x or
  anything other than Pekko HTTP or Jetty 12, there is no module for it today.
- **An interpreter between the request and your code.** Its cost is measured
  against hand-written routes on the same backend in
  [What it costs](docs/what-it-costs.md). On the endpoints measured it is a few
  hundred nanoseconds on Pekko and below what the Jetty benchmark can resolve,
  but the default of running synchronous handlers on a virtual thread costs
  considerably more on Pekko and can be turned off.
- **A new library.** 1.0.0 is the first stable release.

[Choosing between Pelican and the alternatives](docs/choosing.md) compares it
with http4k's contracts, Ktor's OpenAPI plugins, Spring, Micronaut, Quarkus,
tapir and writing the document first, and ends with the cases where Pelican is
the wrong choice.

## Getting started

The current release is **1.0.0**, on Maven Central under the group
`io.github.matthewjones372`. Its library modules are compiled for **Java 25**,
so a service using them needs JDK 25 or newer. In 1.0.0 the server backend is
Pekko HTTP and the JSON codec is Jackson.

Pelican compiles against Pekko but ships none of it, so the build names the
Pekko it runs. Either Scala cross-build works; pick `_2.13` or `_3` and use it
on every Pekko line.

```kotlin
// build.gradle.kts
dependencies {
    // The interpreter. Brings pelican-core; compiles against Pekko HTTP.
    implementation("io.github.matthewjones372:pelican-pekko:1.0.0")
    // Pekko itself: Pelican ships no Scala cross-build, so name the one you run.
    implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-stream_2.13")
    implementation("org.apache.pekko:pekko-http_2.13:1.4.1")
    // JSON bodies with Jackson, and the schemas the document derives.
    implementation("io.github.matthewjones372:pelican-jackson:1.0.0")
    // /openapi.json and Swagger UI beside the endpoints. startWithDocs lives here.
    implementation("io.github.matthewjones372:pelican-pekko-docs:1.0.0")
    // The typed test client, and inMemory() to run it against a Pekko Api.
    testImplementation("io.github.matthewjones372:pelican-test:1.0.0")
    testImplementation("io.github.matthewjones372:pelican-test-pekko:1.0.0")
}
```

### A first endpoint

One endpoint, served with its documentation. This is the file the example
module compiles as
[`FirstEndpoint.kt`](example/src/main/kotlin/example/hello/FirstEndpoint.kt)
with the Pekko binders of
[`FirstEndpointOnPekko.kt`](example/src/main/kotlin/example/hello/FirstEndpointOnPekko.kt).

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
    println("Listening on ${server.baseUrl}, docs at ${server.baseUrl}/api-docs")
}
```

Run it and open `http://127.0.0.1:8080/api-docs` for a Swagger UI page built
from `greet`; the server listens on loopback unless given a `host`. From a
clone of this repository, `./gradlew :example:runFirstEndpointOnPekko` serves
the same thing.

And its test, with no socket:

```kotlin
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.shouldBuild

private val app = greetings().inMemory("first-endpoint")   // starts an actor system of that name

app.call(greet, "world") shouldBe Greeting("Hello, world!")  // shouldBe is Kotest's; use any matcher
app.request(greet, "world") shouldBuild "GET /hello/world"
```

[On Pekko](docs/cookbook.md#on-pekko) in the cookbook covers serving on an
actor system you already run and the Scala 3 cross-build.

### The Gradle plugin

The optional plugin, `io.github.matthewjones372.pelican`, runs the generators
as Gradle tasks: OpenAPI documents, Kotlin clients, endpoints imported from
someone else's document, and the checks against committed copies. It is
published to Maven Central rather than the Gradle Plugin Portal, so the build
needs to be told once:

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
plugins { id("io.github.matthewjones372.pelican") version "1.0.0" }
```

### Jetty, kotlinx and the BOM: on `main`, not yet released

Three things on `main` are not yet in a release:

- the Jetty 12 family: `pelican-jetty`, `pelican-jetty-docs`,
  `pelican-jetty-mcp`, `pelican-test-jetty`, `pelican-streams-jetty` and
  `pelican-client-jetty`;
- `pelican-kotlinx`, a codec for `@Serializable` payloads;
- `pelican-bom`, which pins every module to one version so a build names the
  version once.

The cookbook and the other guides on `main` are written against these, so
their dependency blocks use the BOM and their imports name
`io.github.matthewjones372.pelican.jetty`. On 1.0.0, give each module the
version `1.0.0` and import the binders from
`io.github.matthewjones372.pelican.pekko`: `pelican-jetty` has every binder
`pelican-pekko` has, under the same name, so only streaming handlers change
(a Pekko `Source` where Jetty takes a `Sequence`).

To try them now, use a snapshot. Builds of `main` are published by hand to
Central's snapshot repository as `1.0.1-SNAPSHOT`, including the BOM and the
plugin:

```kotlin
// beside mavenCentral() in your repositories { }, and in
// pluginManagement { repositories { } } if you use the plugin
maven("https://central.sonatype.com/repository/maven-snapshots/")
```

A snapshot is a moving build of `main` with no stability promise, and Central
drops snapshots after 90 days. To build one yourself instead,
`./gradlew publishToMavenLocal` installs the library modules and
`./gradlew -p tooling/pelican-gradle-plugin publishToMavenLocal` installs the
plugin into `mavenLocal()`, at a `-SNAPSHOT` of the next version.

## What else a description can say

Each of these is covered in full in the [reference manual](docs/reference.md),
with complete recipes in the [cookbook](docs/cookbook.md).

- **Inputs and validation.** Path, query, header and cookie parameters, form
  bodies and multipart uploads, with codecs for primitives, `UUID`,
  `java.time`, `URI` and enums. Refinements such as `between` and `matching`
  are enforced and published together. See
  [Refined inputs](docs/reference.md#refined-inputs).
- **Several responses.** More than one declared failure, more than one
  success (a `201` beside a `202`), and one response rendered in several media
  types chosen by `Accept`. See
  [Declared failures](docs/reference.md#declared-failures) and
  [More than one successful response](docs/reference.md#more-than-one-successful-response).
  `pelican-arrow` converts Arrow's `Either` to and from `Outcome`.
- **Streaming.** NDJSON, server-sent events, a chunked JSON array and raw
  bytes, with back-pressure from the socket to the source. See
  [Streaming](docs/reference.md#how-streaming-stays-backend-agnostic).
- **Response headers and webhooks.** Headers declared per response and checked
  when set; webhooks published under `webhooks` with a sender in the generated
  client. See [Response headers](docs/reference.md#response-headers) and
  [Webhooks](docs/reference.md#webhooks-the-calls-the-service-sends).
- **Who is calling.** Security schemes in the document, typed callers, and
  `pelican-oidc` for OpenID Connect tokens and sign-in to pages. See
  [Who is calling](docs/reference.md#who-is-calling).
- **Running in production.** Filters with typed attributes, Micrometer
  (`pelican-metrics`) and OpenTelemetry (`pelican-metrics-otel`), live and
  ready probes (`pelican-health`), body limits, CORS, and opaque 500s. See
  [Filters](docs/reference.md#filters) and
  [Errors](docs/reference.md#errors-and-what-a-caller-is-told).
- **Testing.** The typed client in memory or over a socket, URL pins,
  [golden files](docs/golden-testing.md), and `pelican-test-wiremock` for
  stubbing somebody else's API with its endpoint values. See
  [Testing](docs/reference.md#testing).
- **Clients and imports.** A [generated Kotlin client](docs/generated-client.md)
  for callers who cannot depend on your descriptions, and
  [importing an OpenAPI document](docs/importing.md) somebody else wrote into
  descriptions you can serve, call or stub.
- **Other consumers of the same descriptions.** [MCP tools](docs/mcp.md) a
  model can call, served through the same handlers, and a standalone
  [JSON Schema](docs/schemas.md) for one type.

[Modules](docs/modules.md) lists every module and what it depends on.

## Performance

The interpreter is measured with JMH against the same endpoint written
directly against each backend, and the results, their error bars and their
limits are in [What it costs](docs/what-it-costs.md). In short: on Pekko,
Pelican with its handler on the dispatcher is cheaper than an idiomatic
hand-written route and a few hundred nanoseconds behind the fastest one; on
Jetty the difference is inside the measurement's error, with about 1.1 to 1.3KB
more allocated per request. Running a synchronous handler on a virtual thread,
which is the default on both backends, is the largest cost measured on Pekko
and adds about 9.5KB per request on Jetty; a handler that never blocks can opt
out. These are in-memory measurements on one machine, not a statement
about production capacity.

## Stability

From 1.0.0, the public API of the shipped modules is stable, and a breaking
change waits for a major release. The build enforces what that covers: the
`.api` dump beside each module is the binary contract, checked by `apiCheck`,
and `StillCompilesTest` compiles pinned call sites of the reified inline DSL
(`json<T>()`, `pathParam<T>()` and the rest), which a bytecode dump cannot see.

Not covered: anything `internal`, the exact bytes of the emitted document (pin
what your callers depend on with [golden files](docs/golden-testing.md)), and
modules that are not in a release. The full statement is under
[Stability](docs/reference.md#stability), supported Kotlin, JDK and Pekko
versions are under [Versions](docs/reference.md#versions), and every change is
recorded in [the changelog](CHANGELOG.md).

## Running the examples

```bash
./gradlew build                            # every module: tests, detekt, spotless, coverage
./gradlew :example:runFirstEndpointOnPekko # the greeting above, on Pekko, on :8080
./gradlew :example:runFirstEndpoint        # the same on Jetty
./gradlew :example:runReadmeExample        # the bookmarks service
./gradlew :example:run                     # the fuller orders API: streaming, SSE, raw bodies
./gradlew :example:runShop                 # a bookshop: three domain failures, three declared responses
./gradlew :example:runSecured              # a filter enforcing the security the descriptions declare
./gradlew :example:runTelemetry            # meters and spans from one set of descriptions
./gradlew :example:runMcp                  # the orders API with its tools served on /mcp
./gradlew :example:generateOrdersDocument  # the OpenAPI document, with no server started
./gradlew :example:generateOrdersClient    # the generated Kotlin client, likewise
```

Every example takes a port with `--args=8081`.
[petshop](https://github.com/matthewjones372/petshop) and
[tweet-street](https://github.com/matthewjones372/tweet-street) are two
larger projects outside this repository that use Pelican; petshop's README says
where it helped and where it did not.

## Further reading

- [Getting started](#getting-started): installing 1.0.0 and a first endpoint.
- [A whole service, in one file](docs/a-whole-service.md): models, inputs,
  endpoints, handlers, server and docs, compiled on every build.
- [Cookbook](docs/cookbook.md): complete recipes, roughly in the order you
  need them.
- [Reference manual](docs/reference.md): every feature, the reasoning behind
  it, and its limitations.
- [What it costs](docs/what-it-costs.md): the benchmark report.
- [Roadmap](docs/roadmap.md): what is not built yet.
- License: Apache 2.0. See [LICENSE](LICENSE).
