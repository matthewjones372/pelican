# Pelican — reference

The long version: every module, every trade-off, and the limitations spelled
out. Start with the [README](../README.md) if you have not read it yet.

Endpoints are values; interpreters turn them into a Pekko HTTP route, an http4k
`HttpHandler`, a set of Ktor routes, and an OpenAPI document — 3.1.0 or
3.2.0, whichever the people reading it can use.

## Modules

| Module | Depends on | Contains |
|---|---|---|
| `pelican-core` | **nothing** | endpoint descriptions, plain-value codecs, a minimal JSON tree. No HTTP library, no JSON library. |
| `pelican-openapi` | core | descriptions → an OpenAPI 3.1.0 or 3.2.0 document, in JSON or YAML, and two documents → what changed for callers |
| `pelican-codegen` | core | descriptions → a Kotlin client, as source |
| `pelican-schema` | **core** | one type → a self-contained JSON Schema 2020-12 document: pointers under `$defs`, a union's branches carrying the property that picks them. No document generator, no codec |
| `pelican-mcp` | core, schema | descriptions → MCP tool descriptions, and a dispatch that decodes a tool call into the handler the route already has. No MCP SDK, no transport |
| `pelican-client-java` | **core** | where a generated client's requests go: `ClientTransport` over the JDK's `HttpClient`. No HTTP library of its own |
| `pelican-client-pekko` | core, pekko-http | the same seam over Pekko HTTP's client, for a service that already runs one. Not `pelican-pekko`: calling is not interpreting |
| `pelican-client-ktor` | core, ktor-client-cio | the same seam over Ktor's `HttpClient`, for a service that already runs one. Not `pelican-ktor`: calling is not interpreting |
| `pelican-import` | codegen, snakeyaml-engine | an OpenAPI document → descriptions, as source. The only module that reads a document; the only one with a parser. |
| `pelican-jackson` | core, Jackson, swagger-core | the default `Codecs`: Jackson reads bodies, swagger-core describes types |
| `pelican-kotlinx` | core, kotlinx.serialization | the alternative `Codecs` |
| `pelican-jsoniter` | core, jsoniter, kotlin-reflect | a third `Codecs`, bound and described through the primary constructor |
| `pelican-pekko` | core | descriptions → Pekko HTTP `Route` |
| `pelican-pekko-docs` | pekko, openapi | serves the document and Swagger UI over HTTP |
| `pelican-http4k` | core, http4k-core | descriptions → an http4k `HttpHandler`, plus a server that streams |
| `pelican-http4k-docs` | http4k, openapi | the same two pages, on http4k |
| `pelican-ktor` | core, ktor-server-core, ktor-server-cio | descriptions → Ktor routes, with `suspend` handlers and `Flow` streams |
| `pelican-ktor-docs` | ktor, openapi | the same two pages, on Ktor |
| `pelican-metrics` | core, micrometer-core | descriptions → Micrometer meters, tagged from what the descriptions already say |
| `pelican-metrics-otel` | core, opentelemetry-api | descriptions → OpenTelemetry server spans and the specified duration histogram |
| `pelican-test` | **core** | descriptions → a typed client and assertions. Backend-agnostic; no matcher library. |
| `pelican-test-golden` | test, openapi | one golden per endpoint, failing when a change breaks callers; plus the bytes a call sends |
| `pelican-test-pekko` | test, pekko | the in-memory transport, on Pekko, and `PelicanServer.client()` |
| `pelican-test-http4k` | test, http4k | the in-memory transport, on http4k |
| `pelican-gradle-plugin` | **nothing** | the `io.github.matthewjones372.pelican` Gradle plugin: every generator above, as tasks |
| `example` | core, openapi, jackson, all three backends | the orders, bookmarks, greetings and secured services |
| `benchmarks` | core, jackson, pekko, http4k, JMH | the interpreter measured against hand-written routes. Not published, not run by `build`. |

The layering is load-bearing, not decorative, and each edge is a test:

- `pelican-core` has a test asserting its runtime classpath holds nothing but
  the Kotlin standard library.
- `pelican-openapi` asserts `org.apache.pekko` is absent from its classpath, so
  documentation can be generated in a build task with no server present. Its own
  tests supply a hand-written `SchemaSource` rather than depending on a codec.
- `pelican-pekko` names no JSON library at all; bodies go through whichever
  `BodyCodec` the `Api` was configured with — and it asserts `pelican-openapi`
  is absent from its classpath, so a service that serves only endpoints never
  compiles or ships the document generator. Publishing docs is one extra
  module and one explicit call.
- `pelican-http4k` asserts the same about `pelican-openapi`, *and* that
  `org.apache.pekko` is absent: a second backend sharing a server library with
  the first would be no evidence that the abstractions in core hold.
- `pelican-ktor` asserts all three: no `pelican-openapi`, no Pekko, no http4k.
- `pelican-metrics` asserts that what a consumer gets is core plus a meter API
  and nothing besides — no server library in particular. It reads a description
  and a `Filter`, neither of which knows which interpreter is serving it, and
  that is what lets one `metrics(registry)` line mean the same thing on all
  three backends.
- `pelican-metrics-otel` asserts the mirror image: core plus the OpenTelemetry
  API, no server library, and — the reason it is a module rather than a second
  file next door — no Micrometer. `pelican-metrics` asserts it carries no
  OpenTelemetry in the same breath. The two vendors' APIs are the same size as
  each other and neither audience asked for the other's, so a service that
  wanted meters does not ship a tracer to get them.
- `pelican-client-java` asserts the same shape on the caller's side: core, the
  JDK, and no second HTTP stack. An adapter a caller adds in order to *choose*
  a client library would be worth very little if it brought one along.
- `pelican-client-pekko` asserts the same claim with Pekko in the place of the
  JDK: core, Pekko HTTP's own closure, and nothing else. It also asserts that
  `pelican-pekko` is absent, which is the edge worth having — the interpreter
  and the transport are both Pekko, and a caller who only makes calls should
  not compile a route builder in to do it.
- `pelican-client-ktor` asserts the same claim with Ktor in the place of the
  JDK: core, Ktor's client and its own closure, and nothing else — no OkHttp
  and no Apache client, which is most of what choosing CIO for the engine is
  about. It also asserts that `pelican-ktor` is absent, which is the edge worth
  having: the interpreter and the transport are both Ktor, and a caller who
  only makes calls should not compile a route builder in to do it.
- `pelican-import` depends on `pelican-codegen` rather than on core directly,
  and shares its schema-to-Kotlin generator outright. A client generated from a
  document and a client generated from endpoint values should not disagree
  about what an `Order` looks like, and the only way to guarantee that is for
  both to be the same code. Its parser is snakeyaml-engine and nothing else:
  YAML 1.2 is a superset of JSON, so one dependency reads both, and what it
  produces is turned straight into core's `JsonValue` — the same type
  `pelican-openapi` writes documents out of.
- `pelican-test` asserts that no server library and no matcher library is on its
  runtime classpath. This module was the hole in the story for a while: it
  declared `api(project(":pelican-pekko"))` for the sake of one in-memory
  transport and one convenience function, so a Ktor or http4k service that
  wanted a typed client got Pekko HTTP and Pekko streams as well — and exported
  kotest's matchers to everyone who already had their own. Both are now
  somewhere else, and this test is what stops them coming back.

  The repository's own tests are written with kotest's matchers — that is a
  test-scope dependency of each module and never an exported one, which is why
  this claim is asked of `runtimeClasspath` rather than of `Class.forName`:
  kotest is on the test classpath here by construction, and what a consumer
  gets is the part worth holding.

## Three backends

The backend is a choice about handlers. Descriptions do not change, and neither
do the OpenAPI document, the generated client or the typed test client — they
read the endpoint values, not the server.

What differs is the type a streaming handler returns, and the type a raw body
arrives as:

| | `pelican-pekko` | `pelican-http4k` | `pelican-ktor` |
|---|---|---|---|
| entry point | `Api.toRoute(system)` / `Api.start(...)` | `Api.toHttpHandler()` / `Api.start(...)` | `Route.pelican(api)` / `Api.start(...)` |
| a handler | `(I) -> T` | `(I) -> T` | `suspend (I) -> T` |
| a stream | `Source<T, NotUsed>` | `Sequence<T>`, pulled as the body is written | `Flow<T>`, collected as the body is written |
| raw body | `handle.toSource()` | `handle.toStream()` | `handle.toChannel()` |
| an uploaded file | `file.stream()` | `file.stream()` | `file.stream()` |
| raw output | `Source<ByteString, NotUsed>` | `InputStream` | `ByteReadChannel` |
| escape hatch | `params.request: HttpRequest` | `params.request: Request` | `params.call: ApplicationCall` |
| in-memory testing | `pelican-test`'s `InMemoryTransport` | the handler itself — it *is* `(Request) -> Response` | Ktor's own `testApplication` |
| wrong method on a known path | 405, or 404 when another endpoint declares that method | 405 | 404 |

The one row that does not vary is worth noticing. A raw body is the request's
entity, which each backend already has a name for, so a handler asks that
backend for it. A *part* of a multipart envelope only exists because the
envelope was parsed, the parsing is core's, and so the stream is core's too —
which is why an upload is read the same way on all three. See
[Multipart uploads](#multipart-uploads).

Ktor is the only one of the three whose handlers suspend, because that is Ktor's
own calling convention: a handler runs inside the call's coroutine and may await
anything. A lambda that suspends nowhere still satisfies a `suspend` parameter,
so there is one set of binders rather than a blocking set and a suspending one —
and, for the same reason, no `handledBy`/`streamedBy` taking a
`CompletionStage`, which on the other two backends exist to reach concurrency
this style already has.

Core's handler type is `(Params) -> CompletionStage<Any?>`, being the most it
can say without picking a concurrency library, so `pelican-ktor` bridges the gap
in one private function: the handler runs as a child coroutine of the call and
completes a future the interpreter awaits. Cancellation travels down — a client
that disconnects cancels the handler — and failures are caught rather than left
to the coroutine builder, since a child that fails cancels its parent, and a
cancelled call cannot answer 404 to the `notFound(...)` that caused it.

`example/backends/` is the smallest version of this: `Greetings.kt` describes three
endpoints; `OnPekko.kt`, `OnHttp4k.kt` and `OnKtor.kt` bind them; and
`AllBackendsTest` asserts all three servers answer identically — including that
every wiring generates the same OpenAPI document, byte for byte.

The three bindings sit behind a deliberately thin `Backend` interface — `api()`,
`start(port)`, `stop()` — which is what lets one parameterised suite run against
all of them. It hides exactly two things: the stream type each binder demands,
and the shape of a server handle (Pekko's `stop()` returns a `CompletionStage`,
the other two return nothing). Anything more would start hiding the differences
the example exists to show, which is why `MethodMismatchTest` still reaches for
each backend's own module directly.

At scale, `example/OrdersApi.kt` and `example/http4k/Http4kOrders.kt` bind the
same endpoint list on either backend, and `ClientContractTest` — written entirely
against descriptions — runs against both, so any divergence is a failing test.

### The server underneath

`pelican-http4k` interprets an API into a plain `HttpHandler`; which http4k
`ServerConfig` runs it is `start(config = ...)`, defaulting to
`StreamingSunHttp`.

That default is this module's own, and exists because of a measurement. Ten
NDJSON rows produced 100ms apart, time to the first row:

| backend | first row | all ten |
|---|---|---|
| `StreamingSunHttp` (default) | ~150ms | ~1100ms |
| http4k `Jetty` | ~140ms | ~1080ms |
| http4k `SunHttp` | ~1120ms | ~1140ms |
| http4k `Undertow` | ~1070ms | ~1080ms |

http4k's stock `SunHttp` copies a response body to the socket without flushing,
so the JDK's chunked stream holds frames until its own 4KB buffer fills;
`Undertow` aggregates similarly. Since a streamed output is a promise about
*when* bytes leave, shipping a default that broke it would make the promise
false. `StreamingSunHttp` is http4k's `SunHttp` — whose source invites exactly
this — flushing after each write, on the JDK's own server, so the default needs
no dependency beyond http4k-core. Pass `Jetty(port)` or any other
`ServerConfig` for a service under real load.

### Bringing your own ActorSystem

`Api` is a `pelican-core` type and holds no `ActorSystem`. It cannot: core's
runtime classpath is asserted to be the Kotlin standard library and nothing
else, and one description has to be servable on http4k and Ktor as well. The
system belongs to the binding, so it is a parameter of the Pekko `start`:

```kotlin
val system = ActorSystem.create(Behaviors.empty<Void>(), "orders")   // yours: cluster, persistence, streams

val server = ordersApi().start(system, port = 8080)
val server = ordersApi().startWithDocs(system, port = 8080, docs = ordersDocs)
```

A service that is more than its HTTP layer already has a system before it has a
route, and `start()` creating a second one means two of everything an actor
system carries — dispatchers, thread pools, a scheduler — on a machine sized
for one.

The rule that comes with it: **whoever created a system is who ends it.**
`PelicanServer.stop()` unbinds the port either way, and terminates the system
only when `start` was the one that created it. Terminating a borrowed system
would take the caller's cluster and streams down with their HTTP port, and
leave them nothing to restart it with. `PelicanServer.ownsSystem` says which
kind of handle you are holding, and `InMemoryTransport.close()` has always
followed the same rule for `inMemory(system)`.

`toRoute(system)` remains the lower-level door: it returns a Pekko `Route` and
binds nothing, for a service that concatenates Pelican's route with its own and
calls `Http.get(system).newServerAt(...)` itself. `start(system)` is that,
minus the binding boilerplate, plus the `PelicanServer` handle that
`client()` and `stop()` hang off. `BorrowedSystemTest` holds the ownership
rule and `BorrowedSystemDocsTest` holds it for the documented form.

## Choosing a JSON library

Descriptions carry a `KType` and nothing else — no serializer, no mapper. The
codec is resolved when the `Api` is assembled, which is why switching JSON
libraries is one line in one file and touches no endpoint:

```kotlin
Api(routes, codecs = JacksonCodecs)              // or KotlinxCodecs, or JsoniterCodecs
ApiSpec(endpoints, schemas = JacksonCodecs)      // docs need only the schema half
```

Three interfaces carry it, all in `core/BodyCodec.kt`:

```kotlin
interface BodyCodec<T>   { fun encodeToString(value: T): String; fun decodeFromString(text: String): T }
interface CodecFactory   { fun <T> codec(type: KType): BodyCodec<T> }
interface SchemaSource   { fun schema(type: KType, components: SchemaComponents): JsonObj }
interface Codecs : CodecFactory, SchemaSource
```

`SchemaSource` is deliberately narrower than `Codecs`: generating documentation
must not require a codec that can actually serialise anything.

Codecs are resolved **once per endpoint when the route is built**, never per
request — `KType` → `JavaType` reflection is not cheap. It also means a missing
or unusable codec is a startup failure rather than a surprise on first traffic.

That the implementations agree is a test, not a claim: `CodecAgreementTest`
generates one document through Jackson and one through kotlinx.serialization and
compares them, over models covering defaults, nullability, enums, maps, nesting
and recursion. It also round-trips a value encoded by one codec through the
other. `JsoniterAgreementTest` makes the same comparison for the third module,
against Jackson, over models carrying no annotation of any kind.

A sealed hierarchy is the one payload type neither library can read off the
Kotlin — nothing in `sealed interface PaymentMethod` says which property carries
the branch or what string selects each one — so each is described from the
annotations that make it readable, `@JsonTypeInfo`/`@JsonSubTypes` for Jackson
and `@Serializable`/`@SerialName` for kotlinx.serialization. Both publish the
result the same way; see [The publishing direction](#the-publishing-direction).

`example.codecs` is all three at once: one set of endpoints and handlers, three
`Api`s differing in the `codecs` argument, and a test asserting that the bytes
and the document do not differ. `./gradlew :example:runCodecs` serves them side
by side.

Pass your own mapper, `Json` or jsoniter config when the defaults do not fit:

```kotlin
Api(routes, codecs = JacksonCodecs(myObjectMapper))
Api(routes, codecs = KotlinxCodecs(myJson))
Api(routes, codecs = JsoniterCodecs(jsoniterConfig { escapeUnicode(false) }))
```

### jsoniter, and what a library that never met Kotlin needs

`pelican-jsoniter` is the third module and the one whose library has no
serialization metadata to read: jsoniter was finished in 2018, describes no
types, and binds a JSON object the way its era did — construct the bean, then
set a field per property. A Kotlin data class survives neither half of that. It
has no no-argument constructor, so `data class Line(val sku: String, val
quantity: Int = 1)` is refused outright: `no constructor for: class Line`.
jsoniter's own answer, `@JsonCreator` on the constructor, gets the object built
and loses the defaults with it — the `quantity` nobody sent throws inside the
constructor call, because Kotlin keeps its defaults in a synthetic constructor
that nothing but `callBy` reaches.

So the binding is done in the module, over `kotlin.reflect`, and handed to
jsoniter through the two hooks its `Extension` interface offers. A payload type
is read by collecting the properties that arrived and calling the primary
constructor once, with `callBy` — the one call that applies Kotlin's defaults. A
property that is missing and merely nullable becomes null; a property that is
missing with neither a default nor a null to fall back on is an error naming the
property. Everything else — the parser, the printer, collections, maps, numbers,
strings — stays jsoniter's.

The schemas come from the same constructor, which is the point: there is no
second metadata system here to drift from the first. `java.time` values and
`UUID`s travel as the strings the document says they do, because jsoniter has no
reading of them at all and would otherwise publish `string` and write an object.
A sealed hierarchy travels under a `type` discriminator carrying the branch's
own name, described as a `oneOf` with a full `mapping`, so it publishes the same
shape as the other two modules — with no annotations to declare it, since there
is no annotation this library reads.

A value class travels as the value inside it, in both directions and in the
document. The JVM erases the wrapper out of most signatures — a `Sku` property
reflects as the `String` it wraps — so describing the wrapper would describe
something no payload ever carries.

jsoniter's own settings still apply to what the module writes: `indentionStep`
indents an object the way jsoniter indents one, and `omitDefaultValue` leaves
out what jsoniter's rule leaves out. The codegen modes are the exception — they
compile decoders with javassist, which is not a dependency here, so a config
asking for one is refused at assembly rather than failing on the first request.

Three things are worth knowing before choosing it. The library has been
unmaintained since 2018. The config must come from `jsoniterConfig { }`: a plain
jsoniter `Config` parses perfectly well and cannot bind a data class at all, so
`JsoniterCodecs` refuses one at assembly rather than failing per request. And
a payload type with type parameters — `Page<Order>` — is refused too, in both
directions and in the document: nothing carries the argument to where the
binding happens, so jsoniter would read an `Order` back as a map. Both other
codec modules bind that shape properly, and the message says so.

## Getting the OpenAPI docs

Three ways, all reading the same descriptions.

**At build time**, no server, no request:

```bash
./gradlew :example:generateOrdersDocument      # -> example/build/openapi.json
./gradlew :example:generateOrdersYamlDocument  # -> example/build/openapi.yaml
```

```kotlin
// the whole build script
plugins { id("io.github.matthewjones372.pelican") }

pelican {
    documents {
        create("orders") { specClass.set("example.GenerateOpenApiKt"); specFunction.set("ordersSpec") }
    }
}
```

There is no `main` to write and no `JavaExec` to wire: the plugin loads that
function off the module's own classpath and writes what it returns. See
[The Gradle plugin](#the-gradle-plugin).

**From descriptions, in code** — this needs only `pelican-core` and
`pelican-openapi`:

```kotlin
val spec = ApiSpec(
    endpoints = listOf(getUser, streamOrders, placeOrder),
    schemas = JacksonCodecs,
    title = "Orders",
)
spec.openApiJson()      // String
spec.openApiYaml()      // the same document, written as YAML
spec.openApi()          // JsonObj — core's own tree, if you want to post-process it

spec.openApiJson(OpenApiVersion.V3_2_0)   // the same descriptions, written against 3.2
```

**Served by the running app** — opt in with `pelican-pekko-docs`:

```kotlin
import io.github.matthewjones372.pelican.pekko.docs.Docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs

ordersApi().start(port = 8080)              // endpoints only; no OpenAPI code on the classpath
ordersApi().startWithDocs(port = 8080)      // plus /openapi.json and /docs
ordersApi().startWithDocs(port = 8080, docs = Docs(openApiPath = "/v1/openapi.json", docsPath = "/api-docs"))
```

Set either path to `null` to turn that page off. `docsRoutes(docs)` hands back
the routes on their own if the service already has a route to concat with.

### Which version the document says, and how to choose

Two are written: `3.1.0`, which is the default, and `3.2.0`, which is the
current specification and the one in which these documents are actually
correct. The choice is an argument to the renderer, a property on the Gradle
entry, or a field on `Docs`:

```kotlin
spec.openApiJson()                          // 3.1.0
spec.openApiJson(OpenApiVersion.V3_2_0)     // 3.2.0
spec.openApiYaml(OpenApiVersion.V3_2_0)     // likewise
```

```kotlin
pelican {
    documents {
        create("orders") {
            specClass.set("example.GenerateOpenApiKt")
            specFunction.set("ordersSpec")
            openApiVersion.set("3.2.0")
        }
    }
}
```

```kotlin
ordersApi().startWithDocs(port = 8080, docs = Docs(version = OpenApiVersion.V3_2_0))
```

#### Why it is a choice at all

Because the number at the top of the document is not decoration. The
specification's own versioning rule promises compatibility only within one
`major.minor` feature set — "Tooling which supports OAS 3.1 SHOULD be
compatible with all OAS 3.1.\* versions" — and reserves the right to make
non-backwards-compatible changes in a minor release. A consumer that reads 3.1
is promised nothing about a document that says 3.2.

In practice it is worse than "not promised". swagger-parser, which
`openapi-generator` and a great deal of the JVM ecosystem is built on, models
`SpecVersion.V30` and `SpecVersion.V31` and nothing after them, and hands back
`null` for a 3.2.0 document with an empty list of messages — no error, no
warning, nothing to catch. That silence is why raising the number quietly for
everybody would have been the wrong change, and why 3.1 is still what a caller
who does not choose gets.

#### Why 3.2 is nonetheless the better document

Two things Pelican says every day are things 3.1 has no vocabulary for, and
saying them wrong is not made better by everyone else saying them wrong too.

**Cookies.** `Cookies.render` joins pairs with `"; "` and passes values through
exactly as they were written — RFC 6265 already excludes `;`, `,`, space and
the control characters, and percent-decoding would corrupt a value containing
a `%`. That is precisely what 3.2 named `style: "cookie"`. The `form` that both
revisions assume at `in: "cookie"` means something else: percent-encoded values
joined by `&`. 3.2's Appendix D says so outright, that `form`'s default
`explode: true` "uses the wrong delimiter for cookies (`&` instead of `;`
followed by a single space)". Under 3.2 every cookie parameter carries
`style: "cookie"`, list or not. Under 3.1 it carries nothing, because there was
nothing true to write.

**Streams.** `application/x-ndjson` and `text/event-stream` are what 3.2 calls
*sequential media types*, and it is explicit that `schema` applies to the
complete content — the whole stream read as though the frames were an array —
while `itemSchema` applies to each item independently. What a Pelican
description knows is the frame, so under 3.2 the frame goes under `itemSchema`.
Under 3.1 it goes under `schema`, which is what everything generating streamed
responses did before `itemSchema` existed and which 3.2 now says is the wrong
field.

`sse<T>` moves further than that. 3.2 requires an implementation to work with
event data *after* it has been parsed as `text/event-stream`, and what that
parse yields is an event — `data`, and possibly `event`, `id` and `retry` — not
the payload. So a 3.2 document describes the event, with the payload inside
`data` through the `contentMediaType`/`contentSchema` pair 3.2 points at for
exactly this:

```yaml
text/event-stream:
  itemSchema:
    type: object
    properties:
      event: { type: string, const: order }
      data:
        type: string
        contentMediaType: application/json
        contentSchema: { $ref: '#/components/schemas/Tick' }
    required: [event, data]
```

`id` and `retry` are absent because `sse(...)` never sends them, on the same
principle that stops a response header being documented and not sent. `event`
is absent too where the output does not name one.

A streamed JSON array is *not* sequential under either revision — it is one
document with brackets round it that happens to arrive in pieces — so
`jsonArray<T>` keeps `schema: {type: array}` throughout.

Those three things are the whole difference. A test compares the two documents
wholesale and fails on a fourth.

#### What 3.2 added that is not emitted

Not refusals so much as fields nothing in an endpoint description answers:

- **`$self`** names the document's own URI, and nothing here says where the
  document will be published.
- **`Server.name`**, and the `servers` list is a list of URL strings.
- **`Tag.summary`, `parent` and `kind`.** A tag is a bare string on an
  endpoint; no Tag Objects are written at all, so there is nothing to hang a
  hierarchy off.
- **`Response.summary`**, alongside the description an output already carries.
- **`additionalOperations` and the `query` path-item field**, which describe
  methods `Method` does not have.
- **`Parameter` at `in: "querystring"`**, which takes the whole query string as
  one value with an `Encoding` over it; Pelican describes named parameters.
- **`prefixEncoding` and `itemEncoding`** on a multipart body, which order the
  parts positionally. `multipart(...)` names its parts, so `encoding` is the
  field that fits.
- **`SecurityScheme.deprecated` and `oauth2MetadataUrl`**, and the
  `deviceAuthorization` OAuth flow, none of which a `SecurityScheme` carries.
- **`Discriminator.defaultMapping`**, `XML.nodeType`, `Components.mediaTypes`
  and the Example Object's `dataValue`/`serializedValue`, all of which are
  about parts of a document Pelican does not write.

Nothing Pelican emits today is deprecated in 3.2. The deprecations it carries —
the Example Object's `value` for non-JSON targets, `XML.attribute` and
`XML.wrapped`, and `allowEmptyValue` — are all fields that were never written.
The singular `example` on a parameter or a response header, which *is* written,
is not among them.

### Moving from 3.0.3 to 3.1.0

Older history, kept because a document written by an earlier Pelican is still
out there. The emitter used to write `"openapi": "3.0.3"`. Since then the floor
has been `3.1.0`, whose schema dialect is JSON Schema 2020-12 rather than the
modified subset 3.0 defined for itself. Nothing about how you describe an
endpoint changed — this was entirely about what comes out the other end.

Five things a consumer will see differently:

| 3.0.3 | 3.1.0 |
|---|---|
| `{"type": "string", "nullable": true}` | `{"type": ["string", "null"]}` |
| `{"$ref": "..."}` — nullability lost | `{"anyOf": [{"$ref": "..."}, {"type": "null"}]}` |
| `{"exclusiveMinimum": 0}` — a *boolean* keyword given a number | `{"exclusiveMinimum": 0}` — the bound itself |
| `{"type": "string", "format": "binary"}` | `{"type": "string", "contentMediaType": "application/octet-stream"}` |
| a byte stream was always "binary" | `bytes(mediaType = "image/png")` documents as `contentMediaType: image/png` |

The second row is a fix, not a translation. A `$ref` could take no siblings in
3.0, so `nullable` had nowhere to go and both schema sources dropped it: a
nullable object property was documented as if it were never null. It now says
so. If you generate clients from this document, expect a property to change
from non-optional to optional there — the document was wrong before, and your
generated type was wrong with it.

The third row is the same fix in the other direction. `positive()` has always
written `exclusiveMinimum` as the number, because that is what JSON Schema
means by it; under the 3.0 emitter that was invalid, since 3.0 wanted
`minimum: 0` with `exclusiveMinimum: true` beside it. The bytes on the wire did
not change. What changed is that they are now correct.

`example` on a schema is deprecated in 3.1 in favour of an `examples` *array*.
Pelican never emitted one — a codec's `example` goes on the **parameter**,
where 3.1 still accepts the singular form, and on a response header, likewise.
So there is nothing to migrate unless you put `example` into a schema yourself
through `withFacets`; write `examples: [...]` there instead. Nothing here
synthesises an `examples` array for you, because the only example Pelican knows
about belongs to the parameter and is already on it.

`webhooks` is a 3.1 feature and *is* emitted, from the `webhooks` a spec
carries — see [Webhooks](#webhooks-the-calls-the-service-sends).

**3.0 is not on the list of versions you can ask for, and will not be.** Note
that this is a different question from 3.1 against 3.2, above: those two differ
in three fields at the surface of the document, and 3.0 differs in how every
schema in it is spelled. A version argument would have to reach the schema
sources, because nullability is spelled
*inside* a schema and not around it — so either core's `SchemaSource` grows an
OpenAPI version parameter, a type whose whole purpose is that documentation can
be generated without core knowing what OpenAPI is, or `pelican-openapi` gains a
second pass that rewrites schemas it did not produce. The second is a second
emitter, derived from the first by pattern-matching over its output, and it is
the kind of thing this library exists to avoid: two documents from one set of
values, only one of which anybody runs against real tooling. It also could not
be faithful, since 3.0 cannot express a nullable reference at all.

If you have a consumer that reads only 3.0, `spec.openApi()` hands you the
document as core's own `JsonObj` before it is rendered, and down-converting a
known-shaped document outside the library is a smaller and more honest job than
maintaining a second emitter inside it. Swagger UI, Redoc, Stoplight and
openapi-generator all read 3.1, which is the reason the default is where it is.

### A `servers` entry pins "Try it out"

Swagger UI sends "Try it out" requests to the URLs in the spec's `servers` list,
falling back to the origin the page was loaded from when there is none. A
hardcoded entry is therefore a trap for a locally-run service:

```kotlin
Api(routes, servers = listOf("http://localhost:8080"))   // "Try it out" always calls localhost
```

Open that page on `http://127.0.0.1:8080/api-docs` and every call is
cross-origin — `localhost` and `127.0.0.1` are different origins — so the
browser blocks it and Swagger UI reports a network failure. A `cors(...)` naming
both spellings would rescue it, but leaving `servers` empty is the fix: the page
then calls the origin it was loaded from and nothing is cross-origin at all.

Leave `servers` empty for anything you browse locally; set it when you are
publishing a spec that describes a service somewhere else.

The UI normally fetches the document from `Docs.openApiPath`, so the page and the
`curl`-able spec cannot drift. Switch the spec endpoint off and keep the docs
page, and the document is embedded in the page instead rather than leaving it
pointed at nothing:

```kotlin
api.startWithDocs(docs = Docs(
    openApiPath = null,         // no /openapi.json
    docsPath = "/api-docs",     // still works; the spec ships inside the page
))
```

An `Api` (handlers included) can hand back its description half with
`api.spec()`, so a test can assert the served spec matches the generated file.

### One operation served somewhere else

OpenAPI lets an operation carry a `servers` block of its own, and it usually
means what it says: uploads go to an upload host, reads go to a replica, one
route has been moved and the rest have not. An endpoint can say it:

```kotlin
val importOrders = endpoint(userId, importFile) {
    post("users" / userId / "orders" / "import")
    servers("https://uploads.example.com")
    json<ImportResult>(status = 201)
}
```

**Routing ignores it, and has to.** A server serves what it serves; an endpoint
able to move a route to another host would be a description deciding where a
request lands, which is the one thing a description must not do. Binding this
endpoint to a handler routes `/users/{userId}/orders/import` on the server you
started, exactly as it would without the line. `AllBackendsTest` asserts that on
all three backends.

What honours it is the two readings that are about somewhere else:

- **The document.** `pelican-openapi` publishes `servers` on the operation, in
  the same Server Object shape as the document-level list, written by the same
  function so the two cannot come to disagree. Swagger UI reads the innermost
  one it finds, so this pins "Try it out" for that operation the way the section
  above describes for the whole document — worth knowing before declaring one on
  a service you browse locally.
- **A generated client.** The method for that operation sends its call to that
  URL instead of the client's own base — baked into the generated source, the
  way the path is, and its KDoc says so. `OrdersClient(baseUrl = ...)` still
  points every *other* method; this one was told where it is served. If every
  operation in a spec names a server, the generated client stops requiring a
  base URL at all, because nothing would read it.

Several URLs are kept in the order the document gave them, since a document is
worth republishing as it was read. A client takes the first, as it does with the
API's own list.

`pelican-test`'s `ApiClient` deliberately does *not* read it. A `RequestSpec` is
a method, a path and a body and names no host: the in-memory transport has none,
and a live transport is pointed at the one server the suite is asserting about.
Following a per-operation URL would send one call in the suite to a host nothing
is running. A generated client honours it because it calls a service somebody
else runs; a test client calls the service under test.

## A schema that resolves on its own

`SchemaSource` describes a type as JSON Schema 2020-12, which is the dialect
OpenAPI 3.1 embeds — but what it publishes is a *fragment* of a document.
Pointers are absolute to `#/components/schemas/`, and a sealed hierarchy leaves
the property that tells its branches apart in OpenAPI's `discriminator`, a
keyword JSON Schema does not have.

Inside the document both are correct. Handed to anything else — a validator, a
registry with its own layout, a tool description a model reads — the first is a
dangling reference and the second is worse: a branch schema the validator
accepts and the codec that described it then refuses, for want of a property
that belongs to no Kotlin type and that all three codecs synthesise when
encoding.

`pelican-schema` is that pass, and it is core-only:

```kotlin
val schemas = StandaloneSchemas(JacksonCodecs)

schemas.schema(typeOf<PaymentMethod>())
// { "$ref": "#/$defs/PaymentMethod",
//   "$defs": { "PaymentMethod": { "oneOf": [ {"$ref": "#/$defs/Card"}, … ] },
//              "Card": { "type": "object",
//                        "properties": { "number": {"type": "string"},
//                                        "expiry": {"type": "string"},
//                                        "method": {"const": "card"} },
//                        "required": ["expiry", "number", "method"] } } }
```

Every pointer is rebased onto `$defs`, including the ones inside a
`discriminator` mapping — bare strings rather than `$ref` objects, which a
naive walk carries straight past. Then each branch is given the property that
picks it, as a `const`, required, and the `discriminator` is dropped: it now
says nothing the branches do not.

The property and the value are read rather than derived, which is what lets one
pass cover three sources that agree on almost nothing else — Jackson takes them
from `@JsonTypeInfo` and `@JsonSubTypes`, kotlinx.serialization from
`@JsonClassDiscriminator` and `@SerialName`, jsoniter from the class's own name
under a `type` property. The test builds each branch's smallest acceptable
payload out of the schema alone and decodes it through the codec that wrote the
schema, for all three.

Two things are refused rather than half-described. kotlinx.serialization's open
polymorphism registers its subclasses at run time, so no closed schema of it is
honest; make the hierarchy `sealed`, or describe the property as one branch. And
a class that is a branch of two hierarchies picking it differently would need
both properties at once, which is a payload neither codec writes.

The emitted OpenAPI document is untouched by any of this: `#/components/schemas/`
is where its schemas actually are, and `discriminator` is how 3.1 says which
branch is which — the generated client and the importer both read it. See
[docs/schemas.md](schemas.md).

## Tools a model can call

An endpoint already says everything an MCP tool description has to say. What is
normally thirty hand-written lines per tool — an `inputSchema` built as a JSON
literal, then `arguments?.get("qty")?.jsonPrimitive` in the handler, neither
checked against the service — is derived:

```kotlin
val tools = ordersSpec().mcpTools(options)         // descriptions only
val dispatch = ordersApi().mcpDispatch(options)    // descriptions, and callable
dispatch.call("placeOrder", arguments)             // CompletionStage<ToolResult>
```

The name is `operationName`, so a tool, the document's `operationId` and the
generated client's method are one string. Path and query parameters are named
arguments through the same `openApiSchema()` the document uses, which is what
puts `between(1, 100)` in front of a model as `minimum`/`maximum` rather than
as a 400 it has to discover. The body is one `body` argument, its types under
`$defs` beside it — `pelican-schema`, whose stamp pass is why a union branch
arrives carrying the property that says which branch it is. An `outputSchema`
is published only where exactly one JSON success declares its type, since
`structuredContent` is validated against it the moment one exists.

Dispatch decodes arguments through the codecs and refinements HTTP uses, builds
the `Params` the endpoint declared, and runs the handler the route runs, filters
included. A declared failure, a missing argument and a value a refinement
rejects all come back as a `ToolResult` carrying `isError` and a sentence the
model can act on. What nobody declared still throws.

`pelican-mcp` carries no MCP SDK and no transport: `McpTool` and `ToolResult`
are its own values, so a tool list can be derived, asserted and recorded as a
golden with no server on the classpath. Serving them over stdio and Streamable
HTTP is separate, and not built yet.

What a tool call cannot carry is refused where the tools are derived rather
than at the call: a streamed answer, a multipart or raw body, a cookie
parameter, and a required header with no value behind it — a header being
something a model asked for would invent, so `McpOptions(headers = ...)` is
where a credential comes from. Response headers do not survive a tool result
at all, which is worth knowing before pointing a model at a service that says
`Retry-After` on its 429. [docs/mcp.md](mcp.md) has the mapping table, a worked
example and every refusal with its reason.

## Webhooks: the calls the service sends

OpenAPI 3.1 added a top-level `webhooks` map — operations the provider *sends*
rather than serves. A subscriber registers a URL out of band, and the document
says what will arrive there. Pelican describes one with `webhook(...)`:

```kotlin
val orderPlacedEvent = jsonBody<Order>(description = "The order that was just placed")
val hookSignature = headerParam<String>("X-Signature")

val orderPlaced = webhook("orderPlaced") {
    body(orderPlacedEvent)
    header(hookSignature)
    summary = "Sent to a subscriber when an order is placed"
    empty(status = 204)
}

ApiSpec(endpoints = allEndpoints, schemas = JacksonCodecs, webhooks = listOf(orderPlaced))
Api(endpoints = ordersRoutes, codecs = JacksonCodecs, webhooks = listOf(orderPlaced))
```

An `Endpoint` was already a description rather than a route: a method, inputs,
outputs, and no mention of a server. A webhook is that same description read in
the other direction, which is why the block is the same block — the same
`body(...)`, `header(...)`, `query(...)`, the same declared failures, the same
`security(...)`. What it is not is a route.

### A webhook has no path, and that is the whole design

`webhook(...)` takes a name and a method, and there is no `post("...")` to call.
The name is the identity — it is the key OpenAPI files the operation under, and
what the generated sender is called. The path is *the subscriber's*, and this
document has never seen it, so there is nothing here to write down. Writing one
anyway is refused where it is written:

```kotlin
webhook("orderPlaced") { post("hooks" / "orders"); empty(status = 204) }
// The webhook 'orderPlaced' declares the path /hooks/orders, and a webhook has none
```

So is `servers(...)`, for the same reason: the host it reaches belongs to
whoever subscribed. The destination is supplied when the call is *sent*, by
whoever is sending it.

### It cannot be served

`webhooks` is a field of its own on `Api` and `ApiSpec`, beside `endpoints` and
not among them. The three interpreters build their routes from `endpoints`, so
there is nothing for them to look at. Two more things hold the line:

- A `Webhook`'s operation carries a `webhookName`, and `Api(...)` and
  `ApiSpec(...)` refuse one in the endpoints list. `Webhook.operation` is public
  because `pelican-openapi` and `pelican-codegen` are separate modules and have
  to read it — Kotlin's `internal` stops at the module boundary — so `webhook
  handledNow { ... }` will compile. It fails at construction, naming the
  webhook, rather than quietly serving `POST /`.
- `AllBackendsTest` asserts the 404 on Pekko, http4k and Ktor, and the same
  suite asserts the document still declares it. The alternative was a second
  description model with its own inputs, outputs and DSL — the same code with
  the arrows reversed, and every future feature written twice.

`pelican-test`'s `ApiClient` refuses one too. `Webhook.operation` is an
`Endpoint<*, *>`, so `client.call(...)` will not take it — a star projection
cannot be passed where the input type has to be known — and the check
underneath is there for anyone who casts past that. A test client calls the
service under test, and the service under test does not serve webhooks.

### What is generated

The client generator grows one method per webhook, on the same class and out of
the same emitter that writes the endpoint methods:

```kotlin
fun orderPlaced(url: String, body: Order, xSignature: String) {
    val response = text(request(Method.POST, "", origin = url, standingHeaders = emptyMap(), ...))
    if (!response.succeeded()) failed(Method.POST, url, response)
}
```

The destination is the first parameter because the document does not know it.
Nothing is appended to it — the URL a subscriber gave is the whole address, and
a client inventing a path on somebody else's host would be a bug. And the
client's standing `headers()` are deliberately *not* sent: those are the
credential it presents to the API, and a subscriber is not the API. What a
receiver wants instead is declared on the webhook and arrives as a typed
parameter, as `X-Signature` does above.

### The response is the receiver's

The output model is reused as it stands, because reading a receiver's response
is reading a response. What changes is *who* is promising it — a subscriber the
document's author does not control — so a declared 204 says what a receiver is
expected to do rather than what this service guarantees, and a declared failure
is a hint rather than a contract. The generated sender reads it exactly as it
reads any response, `Outcome` and all.

One output kind is refused. `ndjson<T>()`, `sse<T>()`, `jsonArray<T>()` and
`bytes()` are declared in terms of `StreamOf`/`ByteStream`, phantom markers
whose only job is to type a *handler* that produces the stream in the backend's
own type. A webhook has no handler on this side, so the marker would stand for
something that cannot exist — and on the reading end it would leave a sender
holding an open connection to a subscriber. Declare what a receiver returns: an
`empty(204)`, or a small `json<T>()`.

### `servers` and `security` are not inherited

The specification is silent on both, and the silence is not an oversight worth
guessing past. Root `servers` gives "connectivity information to a target
server" and root `security` applies "across the API" — neither can mean a
webhook, whose request goes to a URL a subscriber chose and carries whatever
credential *that* subscriber asked for.

So a webhook inherits neither. It says what it requires with `security(...)` or
says nothing, and where it said nothing the document says nothing: a
`security: []` would have claimed the receiver wants no credential, which is a
claim nobody made. A scheme that only a webhook requires is still declared under
`components.securitySchemes`, since schemes are collected from the requirements
that reach them.

### Importing them

A document's `webhooks` is read into `webhook(...)` declarations and a
`<name>Webhooks` list, and the round trip closes: `:example` publishes a
document with one in it, imports it, and compares the two documents on every
build. Two refusals are specific to this direction, both recorded per operation
so `exclude` gets you past them: `servers` under a webhook, which has no
reading, and a streamed response, for the reason above. An `operationId` is
required exactly as it is for a route, and the two share a namespace — both
become top-level values in one generated file.

## The Gradle plugin

The document and the client are both readings of the same values, and both are
build tasks:

```kotlin
plugins { id("io.github.matthewjones372.pelican") version "0.1.0" }

pelican {
    documents {
        create("orders") {
            specClass.set("com.example.OrdersSpecKt")
            specFunction.set("ordersSpec")
        }
    }
    clients {
        create("orders") {
            specClass.set("com.example.OrdersSpecKt")
            specFunction.set("ordersSpec")
            packageName.set("com.example.generated")
        }
    }
}
```

There is a third kind of entry, going the other way:

```kotlin
pelican {
    endpoints {
        create("orders") {
            document.set(layout.projectDirectory.file("orders.yaml"))
            packageName.set("com.example.orders")
        }
    }
}
```

Each entry names its tasks — `generateOrdersDocument`, `generateOrdersClient`,
`checkOrdersClient`, `generateOrdersEndpoints` — so a module talking to three
services generates three clients without three build scripts.

It publishes to Maven Central rather than the Gradle Plugin Portal, and
`plugins { }` does not look there by default, so
`pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }` in
`settings.gradle.kts` is what lets a build resolve it. To run an unreleased
build of the plugin instead, `./gradlew -p pelican-gradle-plugin
publishToMavenLocal` installs it and `mavenLocal()` in the same block finds it.

### How it finds the spec

The spec is Kotlin in your project, so the only way to have the generated
output agree with the running service is to run that code. `specClass` and
`specFunction` name a no-argument function returning an `ApiSpec`: a top-level
one (whose class is the file name plus `Kt` — `OrdersSpec.kt` is
`OrdersSpecKt`), a member of an `object`, or a member of a class with a
no-argument constructor.

It is loaded off `classpath`, which defaults to `main`'s runtime classpath.
That default carries its own task dependencies, so generating compiles first
without anybody writing a `dependsOn` — and it is why `pelican-codegen` or
`pelican-openapi` has to be a dependency of the module: the plugin reaches them
through *your* classpath rather than shipping its own copy. Nothing in the
plugin compiles against Pelican, which is what leaves the plugin's version and
the library's free to move independently.

The work runs in a Gradle worker with classloader isolation, so your Jackson
and Gradle's are not the same Jackson.

### What each entry takes

| Property | Applies to | Default |
|---|---|---|
| `specClass` | both | — required |
| `specFunction` | both | `spec` |
| `classpath` | both | `main`'s runtime classpath |
| `packageName` | clients | — required |
| `clientName` | clients | the spec's title: `Orders` → `OrdersClient` |
| `baseUrl` | clients | the spec's first server |
| `includeHidden` | clients | `false` — hidden endpoints are left out, as they are left out of the document |
| `codec` | clients | unset: `jackson`. `kotlinx` annotates the payload types for the other library. See below |
| `outputDir` | clients | `build/generated/pelican/<name>` |
| `format` | documents | `JSON`; `YAML` writes the same document the other way |
| `openApiVersion` | documents | unset: `3.1.0`. `"3.2.0"` writes the current specification instead. See [Which version the document says](#which-version-the-document-says-and-how-to-choose) |
| `outputFile` | documents | `build/generated/pelican/<name>/openapi.<format>` |
| `baseline` | documents | unset. A committed document to check against; setting it registers `check<Name>Document` and wires it into `check`. See below |
| `document` | endpoints | — required: the OpenAPI document to read |
| `packageName` | endpoints | — required |
| `exclude` | endpoints | empty: `operationId`s to leave out. See below |
| `discriminator` | endpoints | none: `discriminator("Payment", property = "kind")` states which property tells an undiscriminated `oneOf`'s branches apart. See below |
| `handlers` | endpoints | unset: `pekko`, `http4k` or `ktor` writes stubs |
| `codec` | endpoints | unset: `jackson`. The same setting a client entry takes. See below |
| `outputDir` | endpoints | `build/generated/pelican/<name>` |

An `endpoints` entry has no `specClass` or `specFunction`, which is the whole
difference between the two directions stated as a property list: it reads a
document rather than compiled descriptions, so there is no spec for it to load.
Its `classpath` needs `pelican-import` on it rather than the module's own code —
usually a configuration of its own, since nothing the module *runs* needs the
importer:

```kotlin
val pelicanImport: Configuration by configurations.creating
dependencies { pelicanImport("io.github.matthewjones372:pelican-import:0.1.0") }

pelican { endpoints { create("orders") { classpath.setFrom(pelicanImport) } } }
```

### Checking against the document callers hold

A `documents` entry that names a `baseline` gets a second task,
`check<Name>Document`, wired into `check`:

```kotlin
pelican {
    documents {
        create("orders") {
            specClass.set("com.example.GenerateOpenApiKt")
            outputFile.set(layout.buildDirectory.file("openapi.json"))
            // The document your callers already have, committed.
            baseline.set(layout.projectDirectory.file("api/openapi.json"))
        }
    }
}
```

It compares what the descriptions publish now against that file and fails the
build when a difference is one an existing caller cannot survive:

```
> Task :example:checkOrdersDocument FAILED
openapi.json — 2 changes break callers.

  GET /users/{userId}
    ✖ `nickname` in the 200 response (application/json) is gone
        a caller reading it gets nothing

  GET /users/{userId}/orders/legacy
    ✖ the operation is gone
        every caller still holding it gets a 404
```

The classification is the same one the golden files make — see
[Golden files](#golden-files) — so a project can take either or both: the task
answers `./gradlew check` without a test suite, and the goldens answer per
endpoint inside one. Pointing both at the same committed file is the tidiest
arrangement, and is what `example` does.

A compatible difference is printed and does not fail, so the task is also the
short answer to "what does this release change for callers".

### Where the output goes, and what that changes

Under `build/`, the generated directory belongs to the task: it is a tracked
output, up to date when nothing changed, and emptied when it is not — so a
renamed client cannot leave the old one behind.

Point `outputDir` at a source root instead and the client becomes a file in
your repository, reviewable in a diff. The plugin treats that as the different
thing it is. The directory is *not* declared as an output, because declaring it
would make every task that compiles those sources depend on this one, and
nothing else in the directory is deleted. What turns on instead is
`check<Name>Client`, wired into `check`: it regenerates into a scratch
directory, compares, and fails when the committed client is no longer what the
descriptions produce, naming the task that fixes it.

That is how this repository generates its own example client — into
`example/src/test/kotlin`, compiled and run against a real server by
`GeneratedKotlinClientTest`, with `checkOrdersClient` on `check` so it cannot
drift.

### Which codec the client is annotated for

A generated client is otherwise free of any JSON library: the payload types are
plain declarations, and the `Codecs` is the caller's, passed to the constructor
the way a server is handed one. A sealed hierarchy is the exception, for the
reason it is the exception everywhere in this document — nothing in `sealed
interface Payment` says which property carries the branch or what string
selects each one — so a spec with a `oneOf` in it makes the client generator
name a library:

```kotlin
create("orders") {
    specClass.set("com.example.OrdersSpecKt")
    packageName.set("com.example.orders")
    codec.set("kotlinx")   // or "jackson", the default
}
```

The setting is `endpoints`' one, spelled and defaulted the same way, because it
is the same decision made in two places: a client generated for a service on
kotlinx.serialization and annotated for Jackson is a client whose payload types
that service cannot read. A spec with no union generates the same file either
way, and `kotlinx` is not free in the same way `jackson` is — it has no
reflective fallback, so every generated payload type carries `@Serializable`.

`pelican-kotlinx`'s `GeneratedClientUnionTest` is what stands behind that
claim: it generates a client for a kotlinx service, builds a payload out of the
discriminator and branch names the *generated file* declares, and decodes it
with the real `KotlinxCodecs`.

### Whether its methods block or suspend

The other setting on a client entry, and the same shape of decision:

```kotlin
create("orders") {
    specClass.set("com.example.OrdersSpecKt")
    packageName.set("com.example.orders")
    callStyle.set("suspending")   // or "blocking", the default
}
```

`suspending` makes every generated method a `suspend` method and the file
depend on `org.jetbrains.kotlinx:kotlinx-coroutines-core`; nothing else about
it moves. Why it is one shape per file rather than both on one class, and what
a cancelled coroutine does to a call in flight, is in
[Blocking or suspending](#blocking-or-suspending).

### Without the plugin

Both generators are ordinary functions, and a build that would rather call them
itself still can: `spec.openApiJson()`, `spec.openApiYaml()` and
`spec.writeKotlinClient(sourceRoot, packageName)` — the last of which takes
`codec = CodecAnnotations.KOTLINX` as a second arity rather than a defaulted
parameter, so that the signature the plugin looks up by name does not move
under an older plugin. The plugin is those calls with the classpath, the
up-to-date checks and the staleness gate already wired.

## The transport a generated client sends with

A generated client builds a request and reads a response. What carries it in
between is a `ClientTransport`, which lives in `pelican-core`:

```kotlin
fun interface ClientTransport {
    fun send(request: ClientRequest): CompletionStage<ClientResponse>
}
```

`ClientRequest` is a method, an assembled and already-encoded URL, headers in
the order the client wrote them, an optional per-request timeout, and a body
that is `Empty`, `Text` or `Streaming`. `ClientResponse` is a status, headers,
and a body that has not been read yet. Both are core's own types: no
`java.net.http`, no Ktor, no Pekko, and nothing on core's runtime classpath but
the Kotlin standard library, which is still the test it was.

The reason there is an interface here at all is the reason there are three
server backends. A service that already runs Ktor, and has already tuned one
Ktor `HttpClient` engine, should not acquire a second HTTP stack because it
generated a Pelican client. The server side settled that argument with one
description and three interpreters; this is the same answer facing the other
way.

### Choosing one

Two adapters are written. `pelican-client-java` is the one over the JDK's own
`HttpClient`; `pelican-client-pekko` is the one over Pekko HTTP's client and
`pelican-client-ktor` the one over Ktor's, for a service that already runs one
of those and would rather not start a second HTTP stack to call out of. A
generated client finds whichever is there without being told:

```kotlin
dependencies { implementation("io.github.matthewjones372:pelican-client-java:0.1.0") }

val client = OrdersClient("https://orders.internal", JacksonCodecs)
```

It is a `ServiceLoader` provider, so adding the module is the whole of choosing
it — core cannot name an adapter it does not depend on. With none on the
classpath the client says so when it is constructed, and with more than one it
asks to be told which, since nothing there could pick for you.

Which is the one case finding-by-classpath cannot serve, and it is worth
knowing what it looks like before you meet it. Put both adapters on one
classpath and every client built without a named transport fails where it is
constructed:

```
Several transports are on the classpath — [..JavaHttpTransport, ..PekkoHttpTransport]
— and nothing here can say which one this client should use. Pass one.
```

The way through is to name the transport at each construction site. That is
what `example`'s own suite does: it compiles both adapters in order to run the
generated client over each, so every `OrdersClient` there is built with the one
it means. Nothing is lost but the defaulted argument, and the failure is loud
and immediate rather than a client that quietly sends on the wrong stack.

Handing one over is the other spelling, and the one to reach for when the
process already has a client worth sharing:

```kotlin
val client = OrdersClient(
    "https://orders.internal",
    JacksonCodecs,
    JavaHttpTransport(HttpClient.newBuilder().executor(pool).build()),
)
```

Everything else about the client is unchanged: `timeout` is still a constructor
parameter, and it reaches the transport on every `ClientRequest` rather than
being configured into one engine, because a client's slow report and its cheap
lookup share a connection pool.

### Why a `CompletionStage`

The generated code is written against the interface before anyone has chosen a
transport, so the shape of `send` cannot vary per adapter without needing a
generator per adapter. Given one shape it has to be the widest one, because the
conversion only runs in one direction: an asynchronous transport serves a
blocking caller with a `join`, while a blocking transport serves an
asynchronous client by tying up a thread per call, which is most of what an
asynchronous client is for. A `suspend` function could not live in core at all,
since coroutines would be a third-party dependency.

It is also the shape core already uses for exactly this job in the other
direction: `ServerEndpoint.invoke` is a `(Params) -> CompletionStage<Any?>`.

What the generated methods do with the stage is the next section: a blocking
client joins it, a suspending client awaits it, and the interface underneath is
the same interface either way. That is the point of choosing the widest shape —
the two call surfaces are two readings of one transport rather than two
transports.

### Blocking or suspending

The generated client has one call surface, and which one is decided when it is
generated:

```kotlin
create("orders") {
    specClass.set("com.example.OrdersSpecKt")
    packageName.set("com.example.orders")
    callStyle.set("suspending")   // or "blocking", the default
}
```

Everything else about the file is the same file. The class name, the method
names, the parameters and their defaults, the payload types, the sealed
failures, the `Streamed<T>`: all of it comes from the descriptions, and none of
it comes from the call shape. What changes is the keyword on each method and
what the file waits on:

```kotlin
// blocking
fun getUser(userId: Long): Outcome<GetUserFailure, User>

// suspending
suspend fun getUser(userId: Long): Outcome<GetUserFailure, User>
```

**One or the other rather than both.** A class carrying both would have two
methods per endpoint, spelled differently enough to tell apart — which is the
one thing a client with one method per endpoint under the endpoint's own name
should not have. It would also put kotlinx.coroutines on the classpath of every
caller that generated a client, including those that will never call a
suspending method, and leave a blocking method within reach of a coroutine that
calls it by accident and parks a dispatcher thread for the length of an HTTP
call — which is the cost the suspending surface exists to avoid.

The usual objection to picking one is that it splits the audience. It does not
split this one, because the file is generated in the *calling* project rather
than published from the described one: each caller generates the surface it
wants, from the same descriptions, and a repository that genuinely wants both
generates two entries into two packages. This one does exactly that, so that
both are compiled and run against a real server by its own suite.

**Where the coroutines live.** Not in `pelican-core`, which has the Kotlin
standard library on its runtime classpath and nothing else, and not in a module
of their own either. `suspend` is a language feature rather than a dependency,
and the only thing the generated file needs from the library is the bridge from
a `CompletionStage`, which is one function:

```kotlin
private suspend fun exchange(request: ClientRequest): ClientResponse =
    transport.send(request).await()
```

So a suspending client needs `org.jetbrains.kotlinx:kotlinx-coroutines-core`
beside `pelican-core`, and a blocking one needs what it always needed. The
generated file says so in its own header.

**Cancellation.** `await` resumes with what the transport raised rather than
with the `CompletionException` a stage wraps around it — the same unwrapping
the blocking form does by hand — and a coroutine cancelled while it is waiting
cancels the `CompletableFuture` underneath it. An adapter has to carry that the
rest of the way: `JavaHttpTransport` cancels the exchange the response was
derived from, because cancellation travels down a chain of stages and not back
up it, and a stage cancelled without that would leave the request running with
nobody left to read it.

**What still blocks.** Reading a body is a socket read wherever it happens. The
generated suspending client reads a whole body inside `withContext(
Dispatchers.IO)`, so it is not the caller's dispatcher that waits for it. A
`Streamed<T>` cannot be handled the same way, because the caller decides when to
ask for the next element: iterate one inside `withContext(Dispatchers.IO)`, or
turn it into a `flow { }` with `flowOn(Dispatchers.IO)`.

### Streams cross both ways

The seam would not be worth much if it could only carry a `String`. It carries
what the descriptions already promise:

- A multipart file part is a `ClientRequest.Body.Streaming`, which is a
  function returning an `InputStream` rather than a stream, so a transport that
  has to send the request twice — a redirect, a retry — has a way to ask for
  the bytes again. Nothing buffers the upload. Whether asking twice *works* is
  a fact about the function: one that opens a file by name can be asked again,
  and the generated client's own — a raw body is the stream its caller handed
  over, and a file part is an `UploadedFile`, which holds one stream and hands
  that same one out — cannot. See [Retrying](#retrying-and-what-is-safe-to-retry).
- `ndjson`, `sse`, `jsonArray` and `bytes()` responses arrive as an unread
  `ClientResponse.body`, and `Streamed<T>` decodes off it as elements land,
  exactly as before. A call that is not streaming reads the body whole, once,
  into a `TextResponse` — a declared failure and the throw for an undeclared
  status both need it, and only one of them can be the first reader of a
  stream.
- Response headers cross as a list of pairs and are read back without regard to
  case, which is what a declared failure carrying a `Retry-After` needs.

This is also the reason the SPI is not `pelican-test`'s `Transport`. That one is
blocking and carries a `String` body on purpose, because a test asserts on a
result it already has — which is exactly why the typed test client
[cannot upload binary](#what-isnt-here). Right taste, wrong constraints.

### On Pekko HTTP

`PekkoHttpTransport` takes an actor system, or does without one:

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs, PekkoHttpTransport(system))
```

A service that already runs Pekko passes the system it has, and calls made
through it get that system's dispatchers, its configuration and its shutdown. A
caller that does not run Pekko passes nothing and never has to learn that an
actor system was involved: the module keeps one for that case, started on the
first request rather than in the constructor, shared by every transport that
asked for none, and configured `daemonic` so that a transport nobody was handed
a close for cannot be what keeps a finished process alive. Nothing in the
adapter terminates a system — two transports sharing one must not be able to
shut each other down — so a caller who wants that control is the caller who
passed one in.

Both body crossings are `StreamConverters`. A `Body.Streaming` becomes a
`Source` built from the `open` function, so an upload is read at the speed the
socket drains it and a request Pekko materialises a second time asks for the
bytes a second time rather than sending a stream already consumed. The response
entity's `Source[ByteString]` is run into `StreamConverters.asInputStream()`,
which hands back a stream fed by a bounded queue: a slow reader backpressures
the connection instead of filling memory, and closing the stream cancels the
source. A caller who neither reads nor closes is the one case that cannot be
covered from here — the stream stays materialised, backpressuring, until the
pool's idle timeout fails it.

`Content-Type` and `Content-Length` need saying separately, because Pekko keeps
neither in its header list. They belong to the entity, and a header added under
either name is dropped as the request renders. So the adapter reads them off
the `ClientRequest` and builds the entity from them — a streamed body whose
length the caller knew is sent sized rather than chunked, which is the
difference between an upload a proxy will size-check and one it may refuse —
and puts them back into `ClientResponse.headers` on the way out. A round trip
through this adapter carries the same two headers a round trip through the JDK
one does, once each.

The per-request timeout is the one thing that does not map exactly, so it is
worth saying what it does instead of implying it maps. Pekko's timeouts are
connection-pool settings, and handing per-request settings to `singleRequest`
keys a new pool per distinct value, so the deadline is imposed on the stage
rather than on Pekko. That bounds the arrival of the response head — which is
what the JDK adapter's `HttpRequest.timeout` bounds too — and not the reading
of a streamed body, which stays governed by the pool's own idle timeout: an
`sse` response outliving the client's thirty-second default is the endpoint
working rather than failing. What is raised on expiry is a
`java.util.concurrent.TimeoutException` naming the call, rather than the JDK's
`HttpTimeoutException`, so a caller catching a timeout by type catches the one
its own transport raises.
### Retrying, and what is safe to retry

Nothing retries unless somebody wrapped a transport in something that does:

```kotlin
val client = OrdersClient(
    "https://orders.internal",
    JacksonCodecs,
    ClientTransport.default().retrying(),
)
```

`retrying(policy)` is `RetryingTransport(this, policy)`, and both live in
`pelican-core` beside the interface they decorate — no library, so every
adapter inherits them. Retrying is a decorator rather than generated code
because that is what the seam is for: retries, request logging and per-call
metrics are the same shape, a transport wrapped around a transport, and none of
them wants a line per operation in a file somebody regenerates. It also means
the behaviour is visible where it was chosen, in the constructor call, rather
than buried in a client that quietly sends twice.

The default is no retries at all, which is what a client built without that
wrapper does. A retry a caller did not ask for turns one failed call into
several, and the call it multiplies is the one already arriving at a service in
trouble.

`RetryPolicy()` is the policy you get by naming none, and every default in it
is narrow, because the cost of retrying something that was not transient is
paid by the server rather than by whoever chose the default:

| Setting | Default | Why |
| --- | --- | --- |
| `maxAttempts` | `3` | Two retries. The first covers the pooled connection the server closed while it was idle; the second covers a retry that landed on the same unhealthy node. A fourth is queueing work against a service that has now failed three times |
| `initialBackoff`, `backoffMultiplier`, `maxBackoff` | `100ms`, `2.0`, `2s` | Doubling reaches a wait that matters within two retries without a first retry a caller would notice. The ceiling is there so that raising `maxAttempts` does not silently put a minute inside somebody's request |
| `jitter` | `0.5` | Half of each wait is random. Clients that failed together compute the same backoff at the same instant and re-form the same herd; randomising *all* of it would let the wait collapse to nothing |
| `statuses` | `408, 429, 502, 503, 504` | Each says the request did not get a fair hearing. **Not 500**: the common cause is an unhandled exception in a handler, and sending the same request again produces the same exception with the work done twice |
| `methods` | `GET, HEAD, PUT, DELETE, OPTIONS` | HTTP's own idempotent set, which is all a description knows about whether a second send does a second thing. A POST that carries an idempotency key is safe and its caller is the one who knows that: name `Method.POST` here |
| `retryStreamedBodies` | `false` | Whether `open()` can be called again is a fact about the function that was passed, and nothing here can see it. On, the request is handed to the transport again unchanged and the transport opens a fresh stream for itself |
| `honourRetryAfter`, `retryAfterCap` | `true`, `10s` | A server that names a number knows something no curve here does, and waiting less than it asked is the one answer that is certainly wrong. Past the cap the policy stops retrying rather than waiting: the server has said it will not be ready inside anything the caller would call a call |
| `failures` | `it is IOException` | A refused connection, a reset, a request that timed out — the socket-level accidents a second attempt may genuinely not repeat. A bug or a refusal survives being sent again |

Only the delta-seconds form of `Retry-After` is read. The HTTP-date form would
have to be compared against this machine's clock, and importing the skew
between two clocks into a wait we already have a defensible value for buys
nothing.

Three details that are easy to get wrong, and are asserted by tests rather than
promised here. A response that is going to be retried has its body closed
first, because it is a live connection until somebody closes it. The wait is
scheduled rather than slept through, so a policy that waits two seconds costs a
timer entry and not a parked thread — which is what the asynchronous shape of
`send` was for. And cancelling what the caller holds cancels both the exchange
in flight and the retry that was going to follow it.

### On Ktor

`KtorHttpTransport` takes an `HttpClient`, or does without one:

```kotlin
val client = OrdersClient("https://orders.internal", JacksonCodecs, KtorHttpTransport(http))
```

A service that already runs Ktor passes the client it has, and the calls go out
through that client's engine, its plugins and its connection pool. Closing it
stays that service's business — nothing in the adapter closes a client it was
handed, because two transports sharing one must not be able to shut each other
down. A caller that does not run Ktor passes nothing and never has to choose an
engine: the module keeps a CIO client for that case, built on the first request
rather than in the constructor, shared by every transport that asked for none,
and closed by nobody, which is why its threads have to be — and are — daemons.

CIO is the default engine because it is Ktor's own networking rather than a
second HTTP stack wearing a Ktor interface: adding this adapter adds no OkHttp
and no Apache client, and a service already using `pelican-ktor` has most of
what CIO needs on the classpath already, since that module ships
`ktor-server-cio`. An engine anyone prefers is a client away — build the
`HttpClient` with it and hand that over.

#### A suspending client behind a `CompletionStage`

Ktor's client suspends and `ClientTransport` does not, and the bridge is the
part of this adapter worth reading. Each `send` launches a coroutine and
returns a `CompletableFuture` that the coroutine completes when the response
head arrives; no thread waits anywhere, and the caller's `join` is the only
blocking in the picture. The coroutine is launched in a scope built from the
client's own context with a `SupervisorJob` under the client's job, so the
adapter starts no dispatcher and keeps no scope of its own: a closed client
cancels the calls made on it, and one failed exchange is one failed exchange
rather than the end of the others.

Cancellation runs both ways across that seam, and both are needed. Cancelling
the stage cancels the coroutine, which unwinds Ktor's `execute` block and
releases the connection rather than leaving it open for a response nobody will
read — including the race where the cancellation arrives while the head is
still in flight, which is why the coroutine checks whether its
`CompletableFuture.complete` was the one that won. In the other direction, an
exchange that fails before the head arrives fails the stage. Neither can happen
twice, because a `CompletableFuture` completes once.

#### Streams, in both directions

`prepareRequest(...)` and `execute { }` are what leave the response body on the
socket: inside that block the body is a live `ByteReadChannel`, and Ktor
releases the connection as soon as the block returns. So the block does not
return until the caller has finished with the stream — the coroutine hands the
`ClientResponse` over and then waits, and closing the body, or reading it to
its end, is what lets it go. A caller who does neither is the one case this
cannot cover; the exchange stays open, holding its connection, until the client
is closed or a timeout ends it.

The channel reaches the SPI as an `InputStream` through Ktor's own
`toInputStream()`, wrapped for two reasons. One is the release just described.
The other is a defect worth knowing about if you write this bridge yourself:
`InputStream.read(b, off, 0)` must return zero, and Ktor's bridge waits for
content before it looks at the length, so the zero-length read that every
`readNBytes` ends with blocks until the next chunk arrives. A caller taking a
fixed number of bytes off an `sse` stream would wait for a chunk it had already
been handed the bytes of. The wrapper answers that read itself.

Going out, a `Body.Streaming` becomes a `WriteChannelContent` that opens the
stream when the connection is ready to take the bytes, and opens it again if
Ktor sends the request a second time after a redirect — which is what `open`
being a function is for. Writing to a full channel suspends, so an upload is
read at the speed the socket drains it and nothing is held but one buffer. A
caller who said how long the body is gets a sized request rather than a chunked
one, so the `Content-Length` they wrote is the one that goes out.

#### `Content-Type`, `Content-Length` and repeats

Ktor renders those two off the body rather than out of the header list, and
prefers the body's copy where both exist. So the adapter reads them off the
`ClientRequest` and builds the content from them, which is the difference
between a declared type arriving once and arriving twice — or not at all.
Coming back they need nothing: Ktor hands over the response headers as they
were received, both among them, and adding either back from the body would be
what doubled it.

One rendering does differ from the JDK adapter's, and it is worth knowing
rather than discovering. A header a caller wrote twice goes out once, carrying
both values separated by a comma, because that is what Ktor's engines do with
repeats — the form RFC 9110 makes equivalent for every list-valued header. The
generated client's own cookies are unaffected: it joins them into one `Cookie`
header itself, with the `; ` that header requires.

#### Deadlines, and the one thing that does not map

A `ClientRequest.timeout` becomes Ktor's per-request `requestTimeoutMillis`,
and what it bounds is not quite what the JDK adapter's `HttpRequest.timeout`
bounds: Ktor's request timeout ends the whole exchange, the reading of a
streamed body included, where the JDK's bounds the arrival of the response head
and leaves the body alone. Given a timeout, an `ndjson` or `sse` response that
outlives it is cut off rather than left running. That is Ktor's semantics and
the adapter does not paper over it; a caller streaming a long response through
this transport should leave the per-request timeout unset, exactly as Ktor's
own SSE client does.

Two consequences follow from the same place. The first is that the client this
module keeps installs `HttpTimeout` with an infinite request timeout, because
CIO's own default is fifteen seconds and it is a deadline on the whole
exchange: left alone it would cut off every response that stayed open longer,
including the ones whose callers set no timeout at all. A client you hand over
keeps whatever deadline you configured on it, that fifteen seconds included.

The second is that a request's timeout is a *capability*, and only the
`HttpTimeout` plugin turns a capability into a cancellation. A client handed
over without that plugin installed would drop the deadline in silence, which is
not a thing an adapter may do to a promise the SPI makes — so where the client
cannot honour it, the adapter imposes it on the stage instead, and raises the
`HttpRequestTimeoutException` Ktor would have raised, so that a caller catching
a timeout by type catches it whichever of the two imposed it. That fallback
bounds the arrival of the head only, like the JDK adapter's.

Ktor's client has no size limit to lift: nothing in it caps a response body
read as a channel, so a `bytes()` response larger than the process crosses on
the strength of never being buffered. What bounds a large response there is
time, which is the paragraph above.

Two smaller decisions round it out. Every request is sent with
`expectSuccess = false`, whatever the handed-over client was configured with,
because a declared failure is a status this client is expected to *read* rather
than an exception to be thrown at it. And the method is `HttpMethod.parse`,
which mints an unknown method rather than refusing it.

### Writing another one

An adapter is small: the three here run to about 250 lines each including the
comments, and in every one of them nearly all of what took thought was the
stream bridge and the cancellation rather than the mapping. A fourth — OkHttp,
Apache, something a house already runs — starts by reading whichever of the
three is closest in shape. `pelican-client-java` is the plainest, since the
JDK's `sendAsync` is already the shape `send` wants; `pelican-client-pekko` and
`pelican-client-ktor` are the ones to read for how a streaming client's
laziness survives the crossing into an `InputStream`.

## Importing an OpenAPI document

Every other module here reads endpoint values and writes something else.
`pelican-import` is the one that reads a document somebody else wrote, and
writes the values. Two situations want it: calling an API you do not own, and
building a service against a spec agreed before the code.

Both get the same file. What is generated describes an API and does not run
one — no server library is named in it — so the descriptions serve a generated
client and a server equally, and the choice is made afterwards by what you do
with them.

### What comes out

```
pelican { endpoints { create("orders") { document.set(file("orders.yaml")); packageName.set("com.example.orders") } } }
```

`generateOrdersEndpoints` writes `OrdersEndpoints.kt`: security schemes, then
inputs, then response headers, then declared failures, then the payload types,
then the schemas those came from, then one `endpoint(...)` per operation, then
the list of them and two `ordersSpec()` functions holding the lot.

The order is load-bearing rather than tidy. Top-level values in Kotlin
initialise in source order, so an endpoint naming a failure declared below it
would read a null at class-init time — everything an endpoint mentions is
declared above it.

### The schemas come too

`ordersSpec()` takes no argument, and that is not a convenience. The generated
file carries the document's schemas verbatim, as a `SchemaSource` of its own,
so an imported description publishes the document it was imported from — and a
client can be generated from it with no JSON library present anywhere in the
build. A codec re-deriving those schemas from the generated Kotlin classes
would produce something very close and not the same thing, and every difference
would be the imported document quietly saying something the original did not.

`ordersSpec(JacksonCodecs)` is the same descriptions with the payload types
described from the classes instead. That is the one to reach for once the
classes are the source of truth — a service that has edited them since the
import, and whose document should say what it now serves.

It is also what makes the round trip below worth running: with the document's
own schemas, the two documents are the same document.

Payload types come from the same generator the client generator uses, so an
imported `Order` and a generated client's `Order` cannot drift apart. A
constraint in the document becomes a refinement rather than a comment:
`minimum: 1` on a query parameter is `IntCodec.atLeast(1)`, which rejects a
zero *and* documents `minimum: 1` when the document is published again.

### Strict, and why

An operation that uses something Pelican cannot describe fails the import. It
does not generate an endpoint with `Any?` in it and a note.

The reason is what the two halves are for. A generated client answering "I do
not model this" with `Any?` is still a working client, and honest about what it
knows — that is why `pelican-codegen` degrades rather than fails. An *import*
degrading produces something else: a handler taking `Any?`, a document that no
longer says what the original said, and no sign that anything was lost. The
import is the moment the two descriptions are supposed to be the same one, and
a silent weakening at exactly that moment is the failure mode worth ruling out.

What it refuses, and what each one would have cost:

| In the document | Why there is no description for it |
|---|---|
| A streamed 2xx beside another 2xx | Both are read where both are values — see [More than one successful response](#more-than-one-successful-response) — but naming one alternative is what produces it, and a stream is produced in the server library's own type, which core cannot name. Document the stream as the only 2xx, or move the other statuses to an operation of their own |
| Two media types for one response, or two *schemas* for one body | A response carries one payload rendered one way. A request body may carry one payload several ways — see [One body, several encodings](#one-body-several-encodings) — but a different schema under each media type is several payloads, and the handler is given one value of one type. Publish one schema under both, or describe the alternatives as a `oneOf` with a `discriminator` |
| `oneOf` of several shapes with no `discriminator` | A union nothing says how to read. The decoder would have to try each branch and take the first that parsed, which is wrong on the first payload two branches both accept. Where you know the missing fact, `discriminator(...)` in the build file states it — see [below](#the-discriminator-a-document-did-not-write-down) |
| `anyOf` of several shapes | A payload may satisfy two `anyOf` branches at once and a Kotlin value is one class or the other, so a sealed hierarchy would say something narrower than the document does |
| `allOf` of schemas that disagree about a property | Merging would have to pick a winner, and the generated class would then accept payloads the document rejects |
| A `discriminator` with no branches to discriminate | Neither spelling of a hierarchy is there: no `oneOf` listing the branches, and no schema declaring an `allOf` of this one |
| A union branch nothing selects | Written inline, unmapped, and declaring no `const` for the discriminator property. A class no payload can reach would be a contract nobody wrote |
| A `oneOf` branch that is itself a discriminated `oneOf` | Two type properties on one payload, and no JSON library reads a type at two levels — see [Two levels of hierarchy](#two-levels-of-hierarchy). Write one `oneOf` over the leaf schemas with one `discriminator` |
| `not` | A schema defined by what it excludes. There is no Kotlin type for "anything but this" |
| A parameter that is an object, or a list of them | A Pelican input decodes one value from one string. A list *of values* reads fine — see [More than one value](#more-than-one-value) |
| A parameter under `content` | It carries a whole document rather than a value; that is a request body |
| `deepObject`, or a `style` and an `explode` that contradict each other | `deepObject` spreads an object over several names, and the rest name a separator that the `explode` beside them makes meaningless |
| A list constrained by `minItems`, `maxItems` or `uniqueItems` | A refinement narrows what one value decodes to and can say nothing about how many arrived, so the constraint would be republished and enforced by nobody |
| Two *streamed* file parts in a multipart body | The same rule `endpoint(...)` enforces at class-init: reading stops at the first, so it can be streamed. A document with several is imported as `bufferedFile` parts and one streamed one — see [Two files, one of them streamed](#two-files-one-of-them-streamed) |
| `callbacks` | A request the service makes *during* an operation, to a URL taken out of that operation's own payload through a runtime expression — `{$request.body#/callbackUrl}`. Nothing in an endpoint description evaluates one. A `webhooks` entry is the case that *is* imported: one call, to a URL a subscriber registered, which a description can say and a sender can make |
| `servers` under a webhook | A webhook is sent to the URL a subscriber registered, and OpenAPI says nothing about what a Server Object beside it would mean. See [Webhooks](#webhooks-the-calls-the-service-sends) |
| A streamed response on a webhook | The response is what the *subscriber* sends back to a call this service made, and nothing here consumes a stream from a subscriber |
| A `$ref` to another host | A build that fetches a URL to know what to generate cannot be reproduced. Bundle or vendor the document — or name the host on purpose and pin what it served, which is [below](#allowing-a-host-on-purpose) |

Each failure names the operation, the position in the document, and what to do.
They are collected rather than thrown one at a time — the decision a reader has
to make is one decision about the whole list — with one problem reported per
operation, since the rest of what an operation says is being read through the
first thing that could not be described.

### Unions

A `oneOf` with a `discriminator` becomes a sealed interface and one data class
per branch:

```yaml
Payment:
  oneOf:
    - { $ref: '#/components/schemas/Card' }
    - { $ref: '#/components/schemas/Bank' }
  discriminator:
    propertyName: kind
    mapping:
      card: '#/components/schemas/Card'
      bank_transfer: '#/components/schemas/Bank'
```

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = Card::class, name = "card"),
    JsonSubTypes.Type(value = BankTransfer::class, name = "bank_transfer"),
)
sealed interface Payment

data class Card(val number: String) : Payment
data class BankTransfer(val iban: String) : Payment
```

The `discriminator` is what makes this possible, and its absence is what is
still refused. Kotlin can hold a union either way; a *decoder* cannot. Without
one it would have to try each branch and keep the first that parsed, which is
wrong on the first payload two branches both accept — and wrong silently. What
a reader who already knows the missing fact can do about that is
[below](#the-discriminator-a-document-did-not-write-down).

#### What each branch is called

In this order, and every one of them read out of the document so that the same
document generates the same names every time:

1. **The `discriminator.mapping` key.** It is the one name the document gives
   the branch *as a branch*, and it is the word a reader matching on the wire
   value already has in their head. Where the key and the referenced component
   disagree — `card` pointing at `CardPayment` — the key wins and the component
   is generated under it, so there is still exactly one Kotlin type per schema.
   A key that would collide with another component's name is left alone: one
   schema with two names is what is being avoided, and two schemas with one
   name is worse.
2. **The referenced component's name**, for a union whose mapping is implicit.
3. **`<Parent>Variant<n>`**, positional, for a branch the document wrote out
   inline and named neither way. Positional so that it does not move when a
   sibling's properties change.

The value on the wire is a separate thing and is never derived: it is the
mapping key, or the component name an implicit mapping selects, or a `const`
the inline branch declares for the discriminator property. A branch with none
of those is refused — nothing selects it, and generating a class no payload can
reach would be inventing a contract.

The discriminator property itself is *not* a property of the branch classes.
The hierarchy carries it. Two places holding one value is two places for them
to disagree, and kotlinx.serialization refuses the pair outright.

#### Two levels of hierarchy

Kotlin has `sealed interface Electronic : Payment`, and it is a perfectly
ordinary thing to write. What it is *not* is a second value on the wire, and a
document that says otherwise is refused:

```yaml
Payment:
  oneOf: [{ $ref: '#/components/schemas/Cash' }, { $ref: '#/components/schemas/Electronic' }]
  discriminator: { propertyName: kind, mapping: { cash: '...Cash', electronic: '...Electronic' } }
Electronic:
  oneOf: [{ $ref: '#/components/schemas/Card' }, { $ref: '#/components/schemas/Bank' }]
  discriminator: { propertyName: type, mapping: { card: '...Card', bank: '...Bank' } }
```

That describes `{"kind": "electronic", "type": "card", ...}` — the payload's
type spread over two properties — and neither JSON library reads one. Jackson
resolves the *declared* type's type id and no other; a second `@JsonTypeInfo`
below the root is ignored on the way in and preferred on the way out, so a
service annotated that way cannot read back what it wrote. It has been a
wontfix in jackson-databind since 2013, restated four times, most recently on
[#2957](https://github.com/FasterXML/jackson-databind/issues/2957).
kotlinx.serialization goes further and makes a second `@JsonClassDiscriminator`
under one hierarchy a compile error, because
[its own documentation](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-class-discriminator/)
says a hierarchy has exactly one; a nested sealed hierarchy is flattened
instead, its `SealedClassSerializer` registering the subclasses of a sealed
subclass as its own.

So the refusal is not a gap. A sealed interface extending another would have
generated cleanly and decoded nothing, which is the silent weakening the whole
import is arranged against. What both libraries *do* is flatten, and the
flattening is what the document should say:

```yaml
Payment:
  oneOf: [{ $ref: '...Cash' }, { $ref: '...Card' }, { $ref: '...Bank' }]
  discriminator: { propertyName: kind, mapping: { cash: '...Cash', card: '...Card', bank: '...Bank' } }
```

One level, every leaf reachable, and the Kotlin nesting — if the classes want
it — kept on the Kotlin side where it costs the wire nothing. That is what both
of this repository's codecs publish for a nested hierarchy; see
[The publishing direction](#the-publishing-direction).

#### `codec`

The annotations above are the one thing in a generated file that belongs to a
particular JSON library, because they are the one thing the Kotlin cannot say
on its own. Which library they are for is a setting:

```kotlin
create("orders") {
    document.set(file("orders.yaml"))
    packageName.set("com.example.orders")
    codec.set("kotlinx")   // or "jackson", the default
}
```

Jackson by default, because `pelican-jackson` is the default codec module. A
document with no union generates the same file either way — nothing is written
unless a hierarchy is generated. `kotlinx` is not free in the same way:
kotlinx.serialization has no reflective fallback, so choosing it puts
`@Serializable` on every generated payload type.

A `clients` entry takes the same setting under the same name; see "Which codec
the client is annotated for" above. One decision, one spelling — a service that
imports its descriptions and publishes a client for them chooses its library
once and says so twice.

#### The other spelling

OpenAPI 3.0 had no way to list a hierarchy's branches, so it wrote the relation
the other way up: a parent carrying the `discriminator`, and one child per
branch declaring `allOf: [$ref parent, ...]`. That is read too, by looking for
the schemas that point at the parent. It is what swagger-core emits for an
annotated Jackson hierarchy and what a great many published documents say, so a
reader that could not follow it would be a reader half the world's specs are
closed to.

Nothing this library *writes* says it any more — see "The publishing direction"
below — but the two are not the same decision. What to accept from a document
somebody else published and what to publish yourself never are.

Without an explicit `mapping`, the value that selects a branch is the branch's
own schema name, which is what OpenAPI defines an implicit mapping to be. That
is a real loss where the producer meant something else, and it is the reason
the publishing direction writes the mapping out rather than relying on it.

#### The `discriminator` a document did not write down

A `oneOf` with no `discriminator` is very common in documents nobody in your
building owns, and until now it cost the whole operation. The reasoning for
refusing it is not being overturned — a decoder that tries each branch and
keeps the first that parsed is wrong, silently, on the first payload two
branches both accept. What changes is *who says* which branch a payload is.
The document did not; a reader who knows can, in the build file:

```kotlin
create("orders") {
    document.set(file("orders.yaml"))
    packageName.set("com.example.orders")
    discriminator("Payment", property = "kind")
}
```

Per schema, written down, reviewed once — the same shape as the `exclude` list
and for the same reason. A global "guess at unions" switch would have been
less typing and would have answered a different question: it says "and
whatever else turns up", where this says "this union, told apart by this
property". A `oneOf` the hints do not name still fails.

What the hint does is write the `discriminator` into the document before
anything reads it. Nothing downstream learns a hint existed: the union is read
by the same function that reads every union, the branches are named by the
same rule, and the schemas the generated file publishes carry the
`discriminator` and its mapping exactly as a document that had stated it
would. A hinted import and the same document with the `discriminator` written
in generate the same file, byte for byte, and `HintsTest` asserts that as one
comparison.

##### Addressing the schema

A component name is the easy half. A `oneOf` written out under a property has
no name at all, and a hint that could only reach the named ones would leave
the other half of the problem where it was. So the address is a JSON pointer,
with two shortenings:

| Written | Means |
|---|---|
| `Payment` | `#/components/schemas/Payment` — no slash, so a component |
| `Order/properties/payment` | relative to `#/components/schemas`, for a union under a named schema |
| `#/paths/~1payments/post/requestBody/content/application~1json/schema` | from the root, for a union written at the endpoint. RFC 6901 escaping, since a path template is made of slashes |

The pointer addresses the document *as Pelican reads it*: bundled, so a schema
pulled in from another file is under `components/schemas` with the name it had
there, and converted, so a 2.0 document is addressed under `components/schemas`
rather than `definitions`.

##### Where each branch's value comes from

Naming the property does not say what travels in it, and none of it is
invented:

1. **A `const` — or a single-valued `enum` — the branch declares for that
   property.** The document has stated the value there. This wins over the
   schema's name, and that is not the rule a documented `discriminator`
   follows: OpenAPI's "an unmapped branch is selected by its own schema name"
   is a rule for filling in a `discriminator` the document *claimed*, and this
   document never claimed one. A branch saying `kind: { const: card }` under a
   component called `CardPayment` travels as `card`, and publishing
   `CardPayment` instead would be confidently wrong rather than merely vague.
   The branch is then named `Card`, by the same rule a `mapping` key names one.
2. **The name of the schema the branch points at**, where it declares no
   constant. That is not invention either — it is the one name the document
   gives the branch.
3. **Nothing.** A branch written inline that declares no constant is refused.
   It has no name to fall back on, and `PaymentVariant2` would be a wire value
   nobody wrote: a client sending it would send a string the service has never
   heard of. Give it a `const`, or point it at a named schema.

The `mapping` is written out in full for every branch that is a reference,
which is what makes the published document say the same thing again. A branch
written inline is not in it and cannot be — a `mapping` value is a reference —
and its `const` is where its value already was.

##### When a hint is wrong

Each one fails the build naming the hint as the build file writes it, the
position it addresses, and what to do. They are collected, because the edit a
reader makes is one edit to one block:

| The hint | What it is told |
|---|---|
| Addresses nothing | There is no schema there, and how a schema is addressed |
| Addresses something that is not a `oneOf` of several branches | What is there instead — a plain object, an `allOf`, an `anyOf` that stays refused |
| Names a property no branch declares | That, and the properties the branches *do* declare, so a typo is one glance from fixed |
| Produces two branches with one value | Which value, and where each branch's value came from |
| Points at a branch written inline that states nothing | The position of that branch, and the two ways to name it |

##### A hint that is no longer needed

It fails, and an unused `exclude` does not. That is a difference between what
the two say rather than an inconsistency. An `exclude` naming an operation that
is not there has weakened nothing: everything still in the document is still
held to the same standard. A hint is a standing claim about a payload format,
and once the document states its own `discriminator` — or nothing reaches the
schema any more, because the operations that did are excluded — that claim is
checked against nothing. A claim nobody checks is the silent weakening the
whole module is arranged against.

`anyOf` of several branches is untouched by any of this and stays refused. A
payload may satisfy two `anyOf` branches at once; a Kotlin value is one class
or the other, so a sealed hierarchy would say something narrower than the
document does, and no hint changes that.

### `allOf`

`allOf` of several schemas is flattened into the one class they describe
together, named for the enclosing schema or the property it sat under. Where
two of them declare the same property differently the import fails, naming the
properties: merging would have to pick a winner, and the class that came out
would accept payloads the document rejects.

### The publishing direction

Both codecs publish a sealed hierarchy the same way: `oneOf` over the branches,
a `discriminator` naming the property that tells them apart, and a full
`mapping` from each wire value to the schema it selects.

```yaml
PaymentMethod:
  oneOf:
    - { $ref: '#/components/schemas/Card' }
    - { $ref: '#/components/schemas/BankTransfer' }
  discriminator:
    propertyName: method
    mapping:
      card: '#/components/schemas/Card'
      bank_transfer: '#/components/schemas/BankTransfer'
```

The `mapping` is the half of a hierarchy a reader cannot reconstruct.
`bank_transfer` selecting a schema called `BankTransfer` is a fact that lives in
one annotation and nowhere else in the Kotlin, and OpenAPI's rule for a document
that does not say it is that the value *is* the schema name — so a document
missing the mapping is not vague about the wire format, it is confidently wrong
about it, and every client generated from it encodes a payload the service
rejects.

`KotlinxCodecs` has always written it: a kotlinx descriptor carries the serial
name of every branch, `@SerialName("card")` right there in the metadata.

`JacksonCodecs` describes types with swagger-core, which writes the 3.0 spelling
above and no `mapping` at all — the names in `@JsonSubTypes` never reach its
schema model. So `pelican-jackson` rewrites the hierarchies it produced, from
the annotations themselves: `Unions.kt` reads `@JsonTypeInfo` and
`@JsonSubTypes` off the classes swagger-core described, and writes the union out
as a `oneOf` with every branch named. What the parent held is pushed down into
the branches rather than dropped, because a `oneOf` branch is the whole payload
— a property declared on the sealed interface belongs in each class that
inherits it.

Which spelling to publish was the choice, and `oneOf` won on three counts: the
documents are 3.1 or later, where it is the native spelling; the 3.0 spelling
has nowhere to put a `mapping`, which is the fact being rescued; and two codecs
publishing one shape means a service can swap JSON libraries without its
published document changing shape underneath its readers. Matching classes back to the components
swagger-core named them under is what makes it possible at all, and it has to
hold for a hierarchy reached anywhere — a property of a payload, an element of a
list, a branch of another hierarchy — not only for one an operation names at the
top. `KotlinAwareModelResolver` records the pairing as each type is resolved,
which is the only place both halves are ever in one hand.

A hierarchy under a hierarchy publishes flat, and that is the same decision one
level further down. Jackson follows `@JsonSubTypes` transitively but resolves
the *declared* type's type id and no other, so a class two levels down travels
under the root's property with its own name — `kind: "card"`, never
`kind: "electronic"` and never a second property beside it. The document says
exactly that: one `oneOf` over the leaves, and the level between them described
by the leaves it stands for rather than listed as a branch nothing can be.
kotlinx.serialization flattens a nested sealed hierarchy the same way, so the
two codecs still publish one shape. The nesting stays in the Kotlin, which is
where it was ever real; the reasoning, and what happens to a document that says
otherwise, are under
[Two levels of hierarchy](#two-levels-of-hierarchy).

Seven shapes are refused rather than published as something they are not, each
naming the class:

| `@JsonTypeInfo` says | Why there is no document for it |
|---|---|
| `include` is anything but `As.PROPERTY` or `As.EXISTING_PROPERTY` | The type is not a property of the payload — it is a wrapper object, an array position, an external field — and a `discriminator` can only name a property |
| `use` is anything but `Id.NAME` or `Id.CLASS` | Neither gives each branch a name that can be written down. `Id.DEDUCTION` puts nothing on the wire at all |
| No `@JsonSubTypes` beside it | The document would declare a type property and never say which values it takes. Jackson needs the list too |
| Two branches with one name | Two branches selected by one value is a payload no decoder can place, at one level of a hierarchy or across two |
| A second `@JsonTypeInfo` below the root | Jackson reads one type id per payload: the inner one is ignored when the root is read and preferred when a branch is written, so the service cannot read back what it wrote. Keep the root's and leave `@JsonSubTypes` alone |
| A concrete class that also carries `@JsonSubTypes` | It is a payload and a level at once, so the document would need a `oneOf` branch that is itself a `oneOf` — the shape `pelican-import` refuses on the way in. Make it abstract, or move its subtypes up beside it |
| An abstract branch with no `@JsonSubTypes` of its own | No payload can be one, so the value naming it selects nothing |

A sealed hierarchy is also the one payload type in the example that carries an
annotation: see `PaymentMethod` in `example/src/main/kotlin/example/Model.kt`,
which is served by a real endpoint, published, imported back, and generated into
the checked-in client on every build.

### The exclude list

Strict is the right default for a document you own and an obstacle in one you
do not, so there is a way through, and it is deliberately not a switch:

```kotlin
create("orders") {
    document.set(file("orders.yaml"))
    packageName.set("com.example.orders")
    exclude("uploadReceipt", "searchAnything")
}
```

Per operation, by `operationId`, written in the build file and reviewed once. A
global `lenient = true` would have been less typing and would have answered a
different question — it says "and whatever else turns up", where this says
"these two, and the third one still fails".

Excluding an operation also excludes the schemas only it reached. `components`
is a library, and a type nobody uses is not worth failing an import over, nor
worth generating a class for — so an `anyOf` in one corner of a document costs
that corner and nothing else. An `anyOf` inside a schema the *rest* of the
document uses is a different matter, and fails outright: no list of operations
to leave out would be an honest answer to it.

Losing the operation is the blunt way through, and one refusal has a narrower
one:
[`discriminator(...)`](#the-discriminator-a-document-did-not-write-down)
supplies the fact an undiscriminated `oneOf` is missing instead of giving up
the operations that reach it. It is the same kind of statement — per schema
rather than per operation, in the same block, reviewed the same way — and the
two are treated differently in exactly one respect: an `exclude` that matches
nothing is left alone, and a hint that matters to nothing fails.

### operationId is required

The document says it is optional. The import does not, and reports every
operation missing one rather than deriving names.

An `operationId` names the generated endpoint value, the generated client's
method and the handler stub — the three things a caller and a maintainer type.
Derived from the method and the path they would be `getUsersByUserIdOrders`,
and they would all change the day somebody reorganises a route, which is a
rename of half the generated file caused by an edit that changed no contract.
Adding `operationId` to a document you own is a small edit that makes the
generated names yours.

### References

References to other files are followed. A schema pulled in from another file
keeps the name it had there, so a spec split across files generates the names
its author chose rather than names invented from where each type happened to be
used — and a local `#/...` inside a pulled-in file is read against *that* file,
which is the bug worth knowing about in anything that merges documents.

References to another host are refused. Following one would mean a build that
fetches a URL to know what to generate: a different result on a different day,
a failure offline, and untrusted content reaching a code generator. Bundle the
document first, or vendor the file it needs beside it.

That reasoning is not overturned by anything below, and it stays the default:
a `$ref` to another host fails an import that has said nothing about it.

#### Allowing a host on purpose

Sometimes neither bundling nor vendoring is available — the document is
published in pieces by somebody else, and rewriting their `$ref`s is a fork of
their spec you now maintain. So the host can be named, and naming it is a line
in the build file rather than a switch:

```kotlin
create("orders") {
    document.set(file("orders.yaml"))
    packageName.set("com.example.orders")
    allowRemote("https://schemas.example.com")
}
```

Per host, reviewed once, in the same block and the same spirit as `exclude`
and `discriminator`. What it does *not* do is make the build trust that host
every morning. It makes it trust what that host served once:

```
orders.refs.lock          the URL and hash of every document fetched — commit it
orders.refs.lock.d/       the documents themselves, named by their hash — commit it too, or not
```

`updateOrdersEndpointsLock` writes both. Every later build reads the lockfile,
and a URL whose bytes no longer hash to what is recorded **fails**, naming the
URL and both hashes, rather than generating different code from a document
somebody else edited.

##### The entry is an origin, not a URL prefix

`example.com`, `https://example.com`, `http://mirror.internal:8080`. A prefix
match is how an allowlist is got past — `https://good.example` is a prefix of
`https://good.example.evil.test` — so scheme, host and port are compared, and
a path in the entry is refused because a path is not a boundary the far end
respects.

A bare host means **https**, the only scheme fetched without being asked for.
`http://` has to be written out. That is not a hoop: an internal mirror with no
certificate is a real thing, and the point is that plain HTTP appears in a diff
as a decision rather than as a default nobody noticed.

##### Redirects are never followed

Not to another host, and not to the allowed one either. A host that can
redirect is a host that can move the document out of the list the build allows,
which is an allowlist the far end gets to rewrite. Following a *same-origin*
redirect would have been safe and is still not done, because refusing buys
something: the `$ref` ends up naming the URL the document really lives at,
which is the URL the lockfile should have recorded in the first place. The
failure names the `Location` it was given.

##### Transitive references

A fetched document referring to its neighbour is the ordinary shape of a
published spec, so the closure is followed. A relative `$ref` is read against
the document it was written in — `./common.yaml` beside
`https://host/specs/api.yaml` is `https://host/specs/common.yaml` — and every
URL reached, at any depth, is checked against the allowlist and gets its own
line in the lockfile. A fetched document naming a *second* host is followed
only if that host was allowed too.

That is what makes the review surface the whole of what the build reads, rather
than only the first hop.

##### What is hashed, and where it goes

The bytes that arrived. Not the parsed tree re-rendered, not the YAML with its
whitespace settled: a hash over a normalised form records what this module's
parser understood rather than what the far end sent, so two different byte
streams would share a hash — and the day the parser starts reading one of them
differently, the lockfile would already have said they were the same document.
Hashing the bytes is also what lets a cached copy be checked without parsing
it.

The lockfile is one line per URL, sorted by URL so that a diff shows the URL
that moved at the start of the line, under a header saying what the file is:

```
# Pelican remote reference lock. Commit this file.
#
# Every URL the `orders` import fetched, and the SHA-256 of the bytes that came back.
# A build checks each one and fails if it no longer matches, rather than generating
# different code. Regenerate with `updateOrdersEndpointsLock`.
#
# <url>  sha256:<hex>
https://schemas.example.com/common.yaml  sha256:3b8f…
https://schemas.example.com/errors.yaml  sha256:9ac1…
```

Sorted rather than in the order the document was walked: walk order is where a
`$ref` happened to sit, and moving one between operations would rewrite the
whole file without changing a fact in it.

Beside it, `orders.refs.lock.d/` holds the fetched documents themselves, each
named by its own hash. Content-addressed rather than named after its URL,
because the lockfile already pairs the two — and a URL turned into a filename
is a URL that has to be escaped, which is a path-traversal question nobody
should have to answer to run a build. A changed document shows in the diff as
one file gone and one arrived, which is what it is.

##### Offline builds

Commit the `.d` directory and the build makes no request at all. The cache is
read first, hashed, and checked against the lockfile — so CI with no network
generates exactly what the last person to run the update task reviewed.

Leaving it out of the repository also works, and is a different trade: the
lockfile still makes the build reproducible, but the build then needs the host
to answer. The failure for an unreachable host says which of the two you are
in.

Note the consequence of reading the cache first: with the cache committed, a
document changing upstream does *not* fail the build, because the build never
looks. That is the correct order — the pinned copy is the input — and noticing
upstream drift is the update task's job, which is exactly when somebody is
looking.

##### Updating the lockfile

```
./gradlew updateOrdersEndpointsLock
./gradlew updateOrdersEndpointsLock --accept-changes
```

Nothing depends on this task; `build` does not reach it. It prints one line per
URL added, changed or dropped, because the person reading that output is
deciding whether to commit it, and "3 references updated" is not something
anybody can review.

Adding a URL nobody had recorded is free — it is new review surface either way,
and it shows up in the diff as such. **Changing** a hash already in the file is
the supply-chain event the lockfile exists for, so it refuses without
`--accept-changes`, printing every URL with its old and new hash. "Just re-run
the update task" is how a hash check gets neutered; this is the second
deliberate word that stops it being a reflex.

##### When it refuses

Each names the position in the document and what to do:

| What happened | What it says |
|---|---|
| A remote `$ref` and no `allowRemote` | The original refusal, plus the third way out: name the host on purpose, and every URL it reaches is recorded with a hash you commit |
| A host the build file did not name | Which hosts it does allow, and the `allowRemote(...)` line that would add this one |
| An allowed host reached over plain HTTP | That the host is allowed over https only, and how to say `http://` on purpose if that is what is meant |
| Any other scheme | That only https is fetched — a `file:` or `data:` URL is not a document a build reads |
| A credential in the URL | That the URL would be written into a committed lockfile. The URL itself is *not* repeated back; a refusal is read in consoles, CI logs and issue trackers |
| The host cannot be reached | The underlying error, and that a committed cache needs no network at all |
| Anything but 200 | The status, and that a document a build reads has to be there every time |
| A redirect | The status and the `Location`, and to write that URL into the `$ref` — whether or not its host is allowed |
| Not a document | The parser's own message, which names the line, prefixed with which `$ref` reached it. An HTML sign-in page lands here |
| A fragment naming nothing | The same "nothing at that pointer" a file on disk gets. It fails during the update too, so the lockfile is never written from a document nobody could read |
| A URL not in the lockfile | That nothing is fetched which has not been recorded, and the task that records it |
| A cached file that does not hash to its own name | That the cached copy was edited or corrupted, and to delete it and update |
| A lockfile line that is not one | The line number and the shape a line has |

##### What has *not* changed

There is no path that fetches and uses content without checking it against a
recorded hash. The update task is the single exception and it is a task of its
own, run on purpose, guarded again for the case that matters. An older
`pelican-import` on the task's classpath — one whose `importEndpoints` has no
allowlist in its signature, and therefore no hash check either — is not fallen
back to while a host is allowed; the plugin refuses and says which module to
upgrade.

### Three dialects, one shape

3.2, 3.1, 3.0 and Swagger 2.0 are all read. 2.0 is converted first — bodies are
parameters there, media types hang off the operation, schemas live under
`definitions` — and 3.0's `nullable: true` becomes 3.1's `"null"` among the
types, which is the spelling everything downstream reads. A 2.0 document and
its 3.0 twin generate identical descriptions; `VersionsTest` asserts exactly
that, because it is the claim the module makes.

Nothing is decided in the conversion that the mapping would decide differently.
A 2.0 operation that `produces` two media types becomes a response offering
two, and is refused there — the same fact about the same operation gets the
same message it would have got in a 3.x document.

3.2 needs no conversion pass, because what changed at the top is a number the
importer never switched on: it reads objects, and takes any document that
carries an `openapi` field. What it did need is the three places 3.2 says
something new, and it reads all three — a streamed response's frame from
`itemSchema` as readily as from `schema`, an `sse<T>`'s payload out of the
`contentSchema` inside a described event, and a cookie's `style: "cookie"` as
the same list `form` used to stand for. Which matters here rather than in the
abstract: the example generates its own document and imports it back, so a
Pelican document written against 3.2 has to survive the round trip.

### The judgement calls

Three places where the document does not say enough, and something had to be
chosen:

- **A JSON array is read as one document, not a stream.** Pelican can describe
  either and they document identically as `type: array`. Reading a response
  whole is the safe half: a handler returning a list works for a caller
  streaming it, and a streaming handler does not work for a caller expecting a
  whole document.
- **A templated server URL is substituted with its declared defaults.** That is
  reading the document rather than guessing at it — the default is what the
  document says the server is when nobody chooses. The document's list and an
  operation's own are read by the one function, so a `{region}` means the same
  thing wherever it sits.
- **A response with no `description` gets the status's reason phrase.** The
  field is required by the spec, and a missing one is not worth failing over.

### Handler stubs

`handlers.set("pekko")` — or `http4k`, or `ktor` — writes a second file with
one `TODO()` per operation, bound with the right binder for each output kind:
`handledOrFail` where failures are declared, `streamedNow` for NDJSON and SSE,
`bytesNow` for a byte stream, `handledWith` where there is no body. It compiles
immediately and throws the moment a request reaches something unwritten, which
is the honest state of a service nobody has written yet.

It is written once and never overwritten. After the first run it is not
generated code any more. Inside `build/` the whole directory belongs to the
task and is emptied on each run, so the write-once rule only protects a source
root you chose.

### Generated source in a source root

Pointing `outputDir` at a source root works the same way it does for the
client: the directory is not a tracked output, and the file is one you commit.
Linters will find it, though, and a generated file is not the place to argue
with them — this repository excludes its own generated import from detekt in
one line, and the path is the one *inside* the source root rather than the
`build/generated` one a reader expects:

```kotlin
tasks.withType<dev.detekt.gradle.Detekt>().configureEach { exclude("com/example/orders/**") }
```

### The round trip

`:example` does this on every build. It publishes `openapi.json` from its
endpoint values, generates descriptions back out of it, compiles them into its
test source set, and compares what those descriptions publish against the
document it started with. Three things are checked at once, and the quietest is
the compiler: generated Kotlin that does not compile fails here rather than in
somebody's project.

It is compared twice. Once through `JacksonCodecs`, where the payload schemas
on the far side are re-derived from the *generated* Kotlin classes — the claim
being that the types that came out describe the payloads that went in — and
once through the imported document's own schemas, where the two documents are
compared whole: every path, every parameter, every schema, down to the examples
and the `minimum` on a query parameter.

Two things are left out of the second comparison, and both are worth knowing.
Key order, because a JSON object is a map and the two documents are built by
walking different things. And what a *successful* response is called: Pelican
writes that from the output kind — "A newline-delimited JSON stream", "No
content" — so it is not something an endpoint carries, and the one endpoint
whose streamed JSON array came back as a whole one differs there. That is the
judgement call above, showing up exactly where it should.

## Hiding an endpoint

`hidden = true` keeps an endpoint out of the document. It is still routed, still
served, still bound to a typed handler — only the description is unpublished:

```kotlin
val reindex = endpoint(apiKey) {
    post("internal" / "reindex")
    hidden = true
    empty(status = 202)
}
```

It hides the description, not the door. A path nobody wrote down is still a path
anyone can guess, so the credential check stays exactly as it was.

## Security schemes

Schemes are values too, declared once and referenced by the endpoints that need
them. The scheme carries the scopes it can grant, so asking for one it never
declared fails when the endpoint value is built — at class-init time, not on the
first request, and not silently in a mismatched annotation.

```kotlin
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
    security(oauth, "orders:write")     // <- the scope, checked against the scheme
    json<Order>(status = 201)
}
```

Scopes can be bare names when there is nothing to say beyond the name —
`scopes = listOf("orders:read", "orders:write")`. Swagger UI lists a checkbox
per scope either way; the map form just puts a description beside each one.

`bearerAuth()`, `basicAuth()`, `apiKeyHeader("X-Api-Key")`, `apiKeyQuery(...)`,
`apiKeyCookie(...)`, `openIdConnect(url)`, `oauth2ClientCredentials(...)`,
`oauth2Password(...)` and `oauth2Implicit(...)` are all there too, and
`oauth2(flows)` takes several flows under one name.

Set an API-wide requirement and let endpoints opt out of it:

```kotlin
Api(routes, codecs = JacksonCodecs, security = listOf(oauth.requires("orders:read")))

val health = endpoint {
    get("health")
    noSecurity()        // documented as public: `security: []`
    text()
}
```

Only referenced schemes reach `components.securitySchemes`, so a scheme used
solely by a hidden endpoint is not published either. Two different schemes under
one name is an error rather than a document where half the padlocks lie.

**Documented, not enforced.** Nothing here validates a token or a scope; that
stays in the handler, or in a filter in front of the server. What this buys is a
correct document and a working Authorize button.

### Authorizing in Swagger UI

Give the docs page an OAuth client of its own and "Try it out" sends a real
token:

```kotlin
ordersApi().startWithDocs(
    port = 8080,
    docs = Docs(
        docsPath = "/api-docs",
        oauth = DocsOAuth(clientId = "orders-docs-ui", scopes = listOf("orders:read")),
    ),
)
```

The redirect target is served next to the page at
`/api-docs/oauth2-redirect.html` — register exactly that URL with the identity
provider. The page resolves it against the origin the reader is actually on, so
`localhost` and `127.0.0.1` each work without configuration.

There is no client secret to set. The docs page is a public client running in a
browser, so a secret shipped to it is not secret; PKCE replaces it and is on by
default (`DocsOAuth(usePkce = false)` if your provider cannot do PKCE).
`additionalQueryStringParams` covers providers that want an extra parameter on
the authorize request — Auth0's `audience`, for instance.

A worked example of all of this — a basic-auth operator login and an external
provider with scopes side by side, the redirect URL printed at start-up, and
**one filter** that reads these very requirements back off the endpoints and
rejects a caller who cannot meet them — is
`example/src/main/kotlin/example/secured/SecuredReports.kt`
(`./gradlew :example:runSecured`). See [Filters](#filters) for the shape of it.

## Response headers

A header on the way out is declared the way an input is declared on the way in:
once, as a value.

```kotlin
val location   = responseHeader<String>("Location", "Where the new order lives")
val rateLeft   = responseHeader<Int>("X-RateLimit-Remaining")
val retryAfter = responseHeader<Long>("Retry-After").optional()

val placeOrder = endpoint(newOrder) {
    post("orders")
    emits(location, rateLeft)
    errorResponse(429, "Slow down", retryAfter)
    json<Order>(status = 201)
}
```

`emits(...)` puts them on the success response in the document, with their
schema, their description and whether they are always sent. It is also the
list `setHeader` checks against:

```kotlin
placeOrder handledNow { req ->
    val order = Store.create(req)
    setHeader(location, "/orders/${order.id}")
    setHeader(rateLeft, quota.remaining)
    order
}
```

Two consequences worth stating.

**Handlers gained a receiver.** Every binder now takes `Params.(I) -> R` rather
than `(I) -> R`. Existing lambdas are unchanged — `handledNow { id -> ... }`
still compiles, and destructuring still works — but `this` is now the request's
`Params`, so a typed handler reaches `setHeader`, `endpoint`, attributes and the
backend's own request without giving up its typed inputs.

**Setting an undeclared header throws.** The same bargain a `queryParam` makes:
what the document promises and what the wire carries are the same value, so
they cannot drift. For the genuinely undocumented — a one-off `X-Debug-*`, a
header a proxy in front expects — `setRawHeader(name, value)` says so out loud.

Headers a handler set go on whatever response comes back, including an error
response. A correlation id that vanished exactly when something went wrong
would be a correlation id worth nothing.

That is also why a header belonging to *one* response is not declared with
`emits(...)`. A `Retry-After` named there would be documented on the success and
settable on every response the endpoint sends, so the first handler to set it
before returning a 201 ships an undocumented header on an order that was placed.
A header that belongs to one failure is declared on that failure instead —
`errorResponse(429, "...", retryAfter)` for a failure that is only documented,
and `errorJson<T>(429, "...", retryAfter)` for one the handler returns. See
[Declared failures](#declared-failures).

`ApiException` carries its own headers for failures raised deep in a handler,
where no endpoint description is to hand — which is what `unauthorized(challenge
= ...)` and `tooManyRequests(retryAfterSeconds = ...)` use.

## Filters

Something that runs around every handler: authentication, rate limiting, a
request log, a timer.

```kotlin
Api(routes, JacksonCodecs, filters = listOf(stamping, requireToken))
```

The first in the list is the outermost — it sees the request first and the
result last. The chain is folded once when the route is built, not per request,
alongside the codec resolution and the CORS policy.

A filter sees the request **after** its inputs are decoded, so `p[userId]` is a
`Long` rather than a string to parse a second time. Rejecting is throwing:

```kotlin
val requireToken = before { p ->
    p[caller] = Tokens.check(p.request) ?: unauthorized("Present a bearer token")
}
```

`before { }` is the common shape — look, throw to reject, return to let through
— and exists because "remember to call `next`" is the step that is easy to
forget and impossible to see missing. The full form is there when the result
matters:

```kotlin
val timing = Filter { params, next ->
    val started = System.nanoTime()
    next(params).thenApply { it.also { metrics.record(params.endpoint, System.nanoTime() - started) } }
}
```

`after { params, result, error -> ... }` covers the log-what-happened case
without the fold, and `onlyWhen { endpoint -> ... }` narrows a filter to the
endpoints it applies to.

### The status, on the way out

`after` is told what the handler *returned*, which is one step short of what an
access log or a metric wants. A handler answering `notFound(id)` is a 404 and a
handler answering `noSuchOrder(ApiError(...))` is also a 404, but nothing about
either value says so: the number is on the endpoint's declaration, because that
is where a response's status is written down. `afterStatus` does that reading:

```kotlin
val accessLog = afterStatus { params, status, _ ->
    log.info("{} {} -> {}", params.endpoint?.method, params.endpoint?.pathSpec?.template, status)
}
```

The status is worked out by `Endpoint.statusFor(result, error)` in
`pelican-core`, which is the same module that decides what each declared
response and each throwable becomes. That matters more than the convenience
does. A filter runs *inside* the interpreter — the chain returns, and only then
is a response built — so a filter cannot be handed the status the interpreter
rendered, and something has to work it out from the description instead. Doing
that once, in core, beside the code that renders it, is what stops the metric
and the response from drifting apart; doing it three times, once per backend, or
once per service that wants a request count, is what guarantees they eventually
will. `MetricsAcrossBackendsTest` in the example holds all three interpreters to
that agreement by comparing what a filter was told against what came back over
the socket.

Two things `afterStatus` sees that `after` does not, and one that neither can:

- **A refusal raised further in.** Rejecting is throwing, and `before` throws
  where it stands rather than failing a stage, so a throwable from a filter
  leaves the chain past anything built on `handle` alone. A 401 is exactly the
  request a log or a rate-of-refusals graph exists for, so `afterStatus` catches
  that case. Writing the full `Filter { }` form and wanting the same, call
  `attempt(params, next)` rather than `next(params)`: it hands back the rest of
  the chain as a stage that fails rather than throws, and swallows nothing.
- **A request that matched no description.** `afterStatus` says nothing about
  one, because the status is read off the endpoint and there is no endpoint to
  read it off. In a served request that never happens; a hand-built `Params` in
  a unit test is the case.
- **A response that fails while it is being written.** A codec that throws
  half-way through, or a handler naming a success the endpoint never declared,
  becomes a 500 *after* the chain has unwound. A filter records the status the
  handler asked for rather than the one the caller received. It is rare, it is a
  bug in the service rather than in a request, and the alternative would be
  holding the chain open until the last byte is on the wire — which would change
  what a filter is.

### Attributes

What a filter works out has to reach the handler, and going back to the raw
request a second time is how the two come to disagree.

```kotlin
val caller = attribute<Caller>("caller")

val requireToken = before { p -> p[caller] = check(p) ?: unauthorized() }

fileReport handledNow { req -> Reports.file(p[caller].subject, req) }
```

Reading an attribute nothing set throws and says so: a handler that reads one is
relying on a filter having run, and a missing filter is a wiring mistake worth
hearing about rather than a null to propagate. `find(key)` is the non-throwing
read for a filter that is genuinely optional.

### Enforcing what the document already says

The security chapter above ends at "documented, not checked". A filter is where
that stops being the end of the story — not because Pelican learned what a token
means, but because the requirement is a value on the endpoint and something else
can read it:

```kotlin
fun enforceDeclaredSecurity(apiWideDefault: List<SecurityRequirement>) = before { p ->
    // Null means the endpoint said nothing and inherits the API's default;
    // an empty list is `noSecurity()`, and deliberate.
    val required = p.endpoint?.security ?: apiWideDefault
    if (required.isEmpty()) return@before

    val who = callerOf(p) ?: unauthorized(challenge = """Bearer realm="reports"""")
    if (required.none { who.satisfies(it) }) forbidden("Needs " + required.joinToString(" or "))
    p[caller] = who
}
```

A list of requirements means *any one will do*, which is how OpenAPI reads it
and therefore how it has to be read here. `example/secured/SecuredReports.kt` is
this, complete and tested, with an operator basic-auth login and an external
OAuth provider side by side.

## Metrics

`pelican-metrics` is one filter and one dependency: `pelican-core` plus
Micrometer.

```kotlin
Api(routes, JacksonCodecs, filters = listOf(metrics(registry)))
```

That is the whole of the instrumentation. There is no per-route registration and
no tag list to keep in step with the router, because every dimension is read off
the `Endpoint` the request matched:

| Tag | Where it comes from |
|---|---|
| `method` | `endpoint.method` |
| `path` | `endpoint.pathSpec.template` — `/orders/{orderId}`, never the id |
| `operation` | `endpoint.operationId`, or `unnamed` |
| `status` | what the interpreter is about to answer with |
| `deprecated` | `endpoint.deprecated` |

Two meters carry them: `http.server.requests`, a counter, and
`http.server.request.duration`, a timer. A Micrometer timer publishes a count of
its own, so the counter looks redundant until the timer is aggregated —
percentile histograms are configured per meter and are often turned off for
cheapness, and a service that has done so still wants the rate of 5xx. The names
follow the OpenTelemetry semantic conventions rather than Micrometer's own,
which is why `pelican-metrics-otel` below publishes its histogram under the
name this timer already carries: a service moving from one to the other keeps
its dashboards.

The `path` tag is why this is worth a module rather than a paragraph of advice.
A meter tagged with the request's *path* grows a time series per order id, which
is how a monitoring bill and then a monitoring outage are made; a meter tagged
with the request's *template* has one series per route. Pelican has the template
because the route was built from it, so nothing has to reverse-engineer it from
the URL that arrived — and nothing has to remember to.

`deprecated` is there because announcing that an endpoint is going away is only
half the conversation. The other half is whether anybody is still calling it,
and that should be a query rather than a survey of downstream teams.

`metrics(registry, prefix = "orders.http")` moves both names, for a service
already publishing something under the defaults — a servlet container's own
instrumentation, say. Everything else Micrometer answers better than a parameter
here could: percentiles, SLO boundaries, common tags and dropping a meter
altogether are all `MeterFilter`s on the registry.

Two things to know before drawing conclusions from the numbers:

- **Put it first in the list.** Filters run outermost-first, so a `metrics(...)`
  listed after an authentication filter never hears about the requests that
  filter refuses. Outermost is where anything measuring the whole request
  belongs.
- **Requests that never reach a filter are not counted.** A path parameter that
  will not decode, a body over `maxBodyBytes`, a `Content-Type` nothing
  declared: these are answered before the chain is entered, so no filter sees
  them and neither do the meters. The 4xx rate here is therefore the rate of
  *handled* 4xx. Closing that gap means metering inside each interpreter, which
  is three implementations of what is currently one, and it has not been done.

`example/src/main/kotlin/example/metrics/MeteredOrders.kt` is a service wired
this way — a 200, a declared 404, a 201 and a deprecated endpoint, with
`/admin/meters` rendering what was recorded. Run it with
`./gradlew :example:runMetrics`.

### OpenTelemetry, from the same descriptions

`pelican-metrics-otel` is the same idea through the other vendor's API, and it
carries traces as well as metrics:

```kotlin
Api(routes, JacksonCodecs, filters = listOf(openTelemetry(sdk)))
```

That produces a `SERVER` span per request and records the
`http.server.request.duration` histogram the semantic conventions specify. The
span is named `GET /orders/{orderId}` — `{method} {http.route}`, which is what
the conventions ask a server span to be called — and both halves of that name,
like every attribute below, are read off the endpoint:

| Attribute | Where it comes from |
|---|---|
| `http.request.method` | `endpoint.method` |
| `http.route` | `endpoint.pathSpec.template` |
| `http.response.status_code` | what the interpreter is about to answer with |
| `error.type` | the status as a string, when it is a 5xx |
| `pelican.operation_id` | `endpoint.operationId`, or `unnamed` |
| `pelican.deprecated` | `endpoint.deprecated` |

`http.route` is the attribute worth the module. A general-purpose agent
instrumenting Pekko or http4k sees a routing tree it has no way to name, so it
either leaves the route off — which costs every per-endpoint view a trace
backend offers — or falls back to the request's own path and produces one
distinct operation per order id. Pelican has the template because the route was
built from it.

The last two attributes are in a namespace of their own because the conventions
have no key for either, and neither adds a dimension: both are a function of the
method and the route, which are attributes already, so a metric split by them
has exactly the series it had before.

Span status follows the conventions rather than intuition. It is left **unset**
for a 4xx on a server span — a declared 404 is the endpoint doing its job, and
an error rate that counts it is measuring how often callers ask for things that
do not exist — and set to `ERROR` for a 5xx. A 5xx that came from a throwable
also records that throwable as a span event, which is the one place its message
is both useful and safe: `renderError` deliberately keeps it out of the response
body, so without the span it goes nowhere at all.

#### Which conventions, and checked against what

The attribute names above are the *stable* HTTP names, the ones adopted when
those conventions were declared stable — `http.request.method` rather than
`http.method`, `http.response.status_code` rather than `http.status_code`,
`url.path` rather than `http.target`. They were read from the OpenTelemetry
specification rather than from memory, at
[HTTP spans](https://opentelemetry.io/docs/specs/semconv/http/http-spans/),
[HTTP metrics](https://opentelemetry.io/docs/specs/semconv/http/http-metrics/)
and the
[HTTP attribute registry](https://opentelemetry.io/docs/specs/semconv/registry/attributes/http/),
which is also where the span-status rule and the recommended bucket boundaries
come from. Anything emitting the older spellings is emitting attributes the
registry now marks deprecated.

#### Continuing a caller's trace

An inbound `traceparent` should continue the caller's trace rather than start a
new one, and doing that needs a header nobody declared:

```kotlin
val pekkoHeaders = object : TextMapGetter<Params> {
    override fun keys(carrier: Params) = carrier.request.headers.map { it.lowercaseName() }
    override fun get(carrier: Params?, key: String) =
        carrier?.request?.getHeader(key)?.map { it.value() }?.orElse(null)
}

Api(routes, JacksonCodecs, filters = listOf(openTelemetry(sdk, incomingHeaders = pekkoHeaders)))
```

Six lines, written once per service, and they are the one thing this module
cannot write for you. `Params` carries the inputs the endpoint *declared*, and
an incoming trace context is not part of an API's contract — it should not
appear in its OpenAPI document — so the only route to the header is
`Params.underlying`, which is the backend's own request object. Naming that type
is exactly what a filter working identically on three interpreters must not do.

Left out, the parent is `Context.current()` instead, which is not a stub: a
service running the OpenTelemetry Java agent already has the caller's context
current on the request thread, so the span becomes a child of the agent's and
adds the route the agent could not know to a trace it had already joined
correctly. Note also that an SDK's default propagator is a no-op one — a service
that never calls `setPropagators` extracts nothing however good its getter is.

What was deliberately **not** done:

- **Nothing is injected on the way out.** Pelican does not add `traceparent` to
  a response, and it has no client side to add one to a request it makes.
  Outbound propagation belongs to whichever HTTP client the service calls with,
  and every one of them already has an instrumentation for it.
- **Baggage is extracted but not read.** Whatever propagators the SDK is
  configured with run, so `baggage` arrives in the context if a service
  registered that propagator; nothing here turns any of it into span
  attributes, because which baggage entries are safe to record is a decision
  about a particular deployment.
- **The context is not carried across the asynchronous boundary.** The span is
  made current only for as long as this module holds the request thread; a
  handler returning a `CompletionStage` completes wherever its own executor
  decides. A handler that wants to nest a span reads `params[otelContext]` and
  passes it to `setParent`, which is one line and is reliable.

#### The same two blind spots

Nothing about OpenTelemetry closes the gaps described above for the meters, and
the new documentation should not read as though it did:

- **Requests answered before the chain is entered are invisible.** A path
  parameter that will not decode, a body over `maxBodyBytes`, a `Content-Type`
  nothing declared: no filter is asked, so there is no span and no measurement.
  The 4xx rate here is the rate of *handled* 4xx, on both instruments.
- **A response that fails while it is being written** becomes a 500 after the
  chain has unwound, so the span carries the status the handler asked for rather
  than the one the caller received.

One more, particular to spans: the attributes the conventions mark required for
a server span and this module does not set — `url.path`, `url.scheme`, and the
recommended `server.address`, `client.address` and `network.*` — are left off
rather than guessed. Every one of them is a property of the socket rather than
of the description, and a filter that behaves identically on three interpreters
is looking at the description. A service that wants them supplies them from a
filter that knows its own backend, or runs the agent, whose server span is the
one carrying them.

#### Why a second module

`pelican-metrics` promises a consumer core plus a meter API and nothing else. A
consumer asking for OpenTelemetry should get core plus the OpenTelemetry API and
nothing else, and Micrometer is not "nothing else". One module carrying both
would have to put each vendor's API in front of the audience that did not ask
for it, or make both `compileOnly` — which would take Micrometer off the
classpath of every service already calling `metrics(registry)` and turn a
working deployment into a `NoClassDefFoundError`. Two modules, a
`NoOtherDependenciesTest` in each, and neither audience pays for the other.

`example/src/main/kotlin/example/tracing/TracedOrders.kt` is a service wired
this way, with `/admin/traces` rendering the spans it produced and a deliberate
500 to show what a span says that a response body does not. Run it with
`./gradlew :example:runTracing`.

## Errors, and what a caller is told

Which throwable becomes which response is decided in `pelican-core`
(`renderError`), so the three backends cannot drift apart about it:

| Throwable | Response |
|---|---|
| `ApiException` (`notFound`, `forbidden`, …) | its own status, message and detail |
| `DecodeFailure` | 400, naming the parameter and the constraint |
| `BodyDecodeFailure` | 400, "Malformed request body" |
| `PayloadTooLarge` | 413 |
| anything else | 500, a reference, and nothing else |

The last row is the one that changed. An exception's message is written for
whoever is debugging and may name a table, a host, a query or a file; it used to
be the `detail` of the 500. Meanwhile Pelican catches the throwable, so the
server underneath never logged it — the message went to exactly the wrong
audience, and only that one.

Now:

```
500 {"status":500,"error":"Internal server error","detail":"Reference: f3ef2bdef43b"}
```

```
ERROR io.github.matthewjones372.pelican.pekko - Unhandled failure in POST /reports [ref f3ef2bdef43b]
java.sql.SQLException: connection to db-primary.internal:5432 refused
```

The reference is in both, so they join up. Logging is per-backend, through
`slf4j-api` — an API rather than a binding, so the application still picks the
implementation. Two settings on the `Api` change it:

- `exposeInternalErrors = true` puts the throwable back in the body. For a local
  run or a fixture; leaving it on in production is how a stack trace ends up in
  someone else's browser.
- `onServerError = { reference, endpoint, throwable -> ... }` replaces the log
  line with your own, for the fields your aggregator wants.

Declared failures are untouched: a 404 you described still carries the payload
you described, because that is not a surprise.

## Limits and startup checks

```kotlin
Api(
    routes, JacksonCodecs,
    maxBodyBytes = 2 * 1024 * 1024,
    covers = allOrderEndpoints,
)
```

`maxBodyBytes` defaults to 8 MiB. An unbounded request body is a way to run a
service out of memory with a single request, and a library that leaves the limit
unset ships that as the default — so there is one. A body over it is a 413
raised before any codec sees it: each backend checks the declared
`Content-Length` first, and falls back to truncating the read for a chunked
request that declared none. A `rawBody()` stream is exempt, because nothing
holds it whole, and so is a multipart body's streamed part. What a multipart
body *does* hold — its text parts and its `bufferedFile` parts — shares this
same number as one budget, on top of whatever bound each buffered part declared
for itself.

Two mistakes are now caught when the `Api` is constructed rather than on the
request that trips over them:

- **Two endpoints bound to the same method and path.** The second can never be
  reached, because the router stops at the first match. Always a mistake, and
  previously a silent one. No opting in.
- **A declared endpoint that was never bound.** `covers` takes the list the
  spec is generated from, and every entry must appear in `endpoints`. Matching
  is by identity, not equality, so a structurally identical twin does not
  satisfy it. A test that binds a deliberate subset passes `covers = emptyList()`
  to say so.

## CORS

Set it on the `Api` and the interpreters answer preflights and add the
`Access-Control-*` headers. Unlike the security schemes above, this one is
enforced rather than merely described — it is a rule about browsers, and a
description a browser never sees would enforce nothing:

```kotlin
Api(routes, JacksonCodecs, cors = cors("https://app.example.com", "http://localhost:5173"))
```

What that origin may *do* is not configured a second time. It is read off the
endpoints:

| the answer | where it comes from |
|---|---|
| `Access-Control-Allow-Methods` | the methods declared on that path |
| `Access-Control-Allow-Headers` | the `header(...)` params those endpoints declare, `Content-Type` where they take a body, and the credential header their security scheme names |
| `Access-Control-Allow-Origin` | the request's origin, echoed once the policy allows it |
| `Vary: Origin` | every response from a listed or predicate policy, including one that carries no other `Access-Control-*` — a cache that keyed on the URL alone would serve a browser the answer it stored for a curl |

So a `header(...)` added to an endpoint is a header the browser may send from
that moment, and a `bearerAuth()` on it is `Authorization` allowed — with
nothing else to edit and no way for the two lists to disagree. A preflight for
`GET` is not told about the `Content-Type` a `POST` on the same path needs,
because it is answered from the endpoints for the method being asked about.

```
OPTIONS /echo
Origin: https://app.example.com
Access-Control-Request-Method: POST

204
Access-Control-Allow-Origin: https://app.example.com
Access-Control-Allow-Methods: POST
Access-Control-Allow-Headers: X-Trace-Id, Content-Type
Access-Control-Max-Age: 600
Vary: Origin
```

The rest is a handful of named arguments:

```kotlin
cors(
    "https://app.example.com",
    additionalAllowedHeaders = listOf("X-Tenant"),   // added to the derived set, never instead of it
    exposedHeaders = listOf("X-Request-Id"),         // response headers a script may read
    allowCredentials = true,                         // cookies, or an Authorization the browser attaches
    maxAgeSeconds = 600,                             // how long a browser may cache the preflight
)

corsAnyOrigin()                                      // `*`, for a public read-only API
cors(CorsOrigins.Matching("https://*.example.com") { it.endsWith(".example.com") })
```

`additionalAllowedHeaders` adds rather than replaces on purpose: a list shorter
than what your own endpoints declare is a list that breaks them. Credentials and
`*` together throw where the value is written, because a browser refuses that
pairing and finding out at runtime is worse.

Three details worth knowing:

- **The headers go on errors too.** A 400 without them reaches the script as a
  bare network error rather than the explanation the server wrote.
- **A refused origin is still served.** CORS is a browser's rule, not a
  credential check; withholding the header is what blocks the *read*. A `curl`
  and a `fetch` see the same server. Rejecting a caller outright is
  authentication, and stays where authentication was.
- **A refused *preflight* is a 403**, carrying the usual error body saying which
  check failed — the origin, or a method that path never describes.

The document is covered too. `pelican-*-docs` serves `/openapi.json` and the
Swagger UI page with the same headers, since a browser tool reading the spec
cross-origin is blocked by the same rule as one calling an endpoint; reading
either is a plain `GET`, so there is nothing to preflight there.

An endpoint that declares `OPTIONS` for itself keeps it; the preflight route is
only added to paths that do not. There is no per-endpoint CORS: the policy is
one value on the `Api`, since a browser asks about a path rather than about an
operation.

`CorsPolicy` is where the decisions live, in `pelican-core`, so the three
backends serve one implementation rather than three — `CorsPolicyTest` holds the
decisions and `example/backends/CorsTest` asserts each backend puts them on the
wire.

## Everything is a value

There is no registry and no builder. Descriptions are values, implementations
are values, and an `Api` is a list of them plus some settings:

```kotlin
val routes = listOf(
    getUser      handledNow  { id -> Store.user(id) ?: notFound("No user $id") },
    streamOrders streamedNow { (id, max, status, trace) -> Source.from(...) },
    cancelOrder  handledWith { (_, _, key) -> ... },
    echo         bytesNow    { body -> body.toSource() },
)

val api = Api(routes, codecs = JacksonCodecs, title = "Orders")
api.start(port = 8080)
```

`routes` is an ordinary `List<ServerEndpoint>`, so it can be split across files,
filtered by feature flag or concatenated — nothing has to happen inside a block.

## Typechecked endpoints

`endpoint(a, b, ...)` declares the input list once. It registers each parameter
for decoding and documentation **and** fixes the handler's signature.

```kotlin
val userId = pathParam<Long>("userId")
val limit  = queryParam<Int>("limit").default(25)
val status = queryParam<OrderStatus>("status").optional()
val trace  = headerParam<String>("X-Trace-Id").optional()

val streamOrders = endpoint(userId, limit, status, trace) {
    get("users" / userId / "orders")
    ndjson<Order>()
}

streamOrders streamedNow { (id, max, status, trace) ->
    //                      Long  Int  OrderStatus?  String?
    Source.from(Store.orders(id, max, status))
}
```

No `query(...)` or `header(...)` call — listing them on `endpoint` did that. One
declaration site, and the handler receives exactly what was declared. There is
an overload per arity up to six.

What the compiler catches (verified by feeding it each mistake):

```
+(getUser handledNow { id: String -> ... })
  e: Argument type mismatch: actual 'Function1<String, User>', expected 'Function1<Long, User>'

+(watchOrders streamedNow { (_, max) -> Source.single("not a tick") })
  e: Return type mismatch: expected 'Source<Tick, NotUsed>', actual 'Source<String, NotUsed>'

+(streamOrders handledNow { _ -> aUser })
  e: Return type mismatch: expected 'StreamOf<Order>', actual 'User'

+(getUser handledNow { id -> "a string" })
  e: Return type mismatch: expected 'User', actual 'String'
```

What it does **not** catch, honestly:

- **Trailing inputs can be dropped.** Kotlin lets you destructure a prefix of a
  data class, so a four-input endpoint accepts `{ (id, max) -> ... }`. The two
  you bind are still correctly typed; you just can't see the rest.
- **Two inputs of the same type can be swapped.** `endpoint(userId, orderId)` as
  two `Long`s will accept a handler that reads them backwards. Fix it with value
  classes and `PlainCodec.map` (or `mapOrFail`, which also validates — see
  [Refined inputs](#refined-inputs)):

  ```kotlin
  @JvmInline value class UserId(val value: Long)
  val userId = pathParam("userId", LongCodec.map(::UserId, UserId::value))
  ```

  The parameter still documents as `integer/int64`; only Kotlin sees the wrapper.
- **Path/input mismatches** are checked when the endpoint value is constructed,
  not at compile time. Declaring a path parameter that isn't in the path, or
  capturing one nothing declares, throws immediately at class-init:

  ```
  GET /things declares path parameter 'stray', but the path is /things
  GET /things/{ignored} captures 'ignored' in its path but never declares it as
  an input, so no handler could read it
  ```

No arguments means no inputs: `endpoint { }` is an `Endpoint<Unit, R>` and its
handler is given nothing, because there is nothing to give it.

Past six inputs there is no overload, and tuples have stopped paying for
themselves anyway. Ask for the whole bag by name — `endpoint(lensInputs) { }`
with `query(...)`/`header(...)`, handler receives `Params`, read by key. The
trade is that reading an undeclared key throws at request time instead of
failing to compile, which is why it is a name rather than the default.

## Refined inputs

A `PlainCodec` decides what a path segment, query parameter or header *parses
as*. Refining it narrows what is accepted, and — this is the half that usually
goes missing — writes the constraint into the document, so the schema states
what the server enforces:

```kotlin
val limit = queryParam("limit", IntCodec.between(1, 100), description = "How many to return").default(20)
```

```json
{ "name": "limit", "in": "query", "required": false,
  "description": "How many to return",
  "schema": { "type": "integer", "format": "int32", "minimum": 1, "maximum": 100 } }
```

`?limit=0` is a 400 before any handler runs:

```json
{"status":400,"error":"Invalid parameter","detail":"Cannot decode '0' for 'limit': expected a value between 1 and 100"}
```

Built in: `nonEmpty()`, `nonBlank()`, `minLength(n)`, `maxLength(n)`,
`matching(regex)`, `atLeast(n)`, `atMost(n)`, `between(lo, hi)`, `positive()`.
Each documents itself — `minLength`, `pattern`, `minimum`, `maximum`, and
`exclusiveMinimum` for `positive()`, which under 3.1 carries the bound rather
than a boolean saying that some other keyword is exclusive.

### Your own types

`mapOrFail` turns a rejected value into the same 400, rather than an exception
somewhere inside a handler:

```kotlin
@JvmInline value class Slug(val value: String)

val slug: PlainCodec<Slug> = StringCodec
    .matching(Regex("[a-z0-9-]{1,40}"), "a slug: lowercase letters, digits and dashes")
    .map(::Slug, Slug::value)
    .describedAs("A URL-safe tag", example = "streams")

val tag = queryParam("tag", slug, description = "Only bookmarks with this tag").optional()
```

The handler now receives a `Slug?`, not a `String?` — which is also the fix for
two inputs of the same primitive type being read in the wrong order.

For a guarantee that survives being passed on, make the type unconstructable
except through the codec. `NonEmptyString` ships that way:

```kotlin
NonEmptyString.of("")      // null — the only constructor
queryParam<NonEmptyString>("q")
```

### Documenting the type, not each use

`describedAs(description, example)` attaches documentation to the codec, so
every parameter built from it is described the same way without repeating
itself. A parameter's own `description` still wins:

```kotlin
val apiKey = headerParam("X-Api-Key", StringCodec.nonEmpty().describedAs("The caller's API key", example = "let-me-in"))
```

```json
{ "name": "X-Api-Key", "in": "header", "required": true,
  "description": "The caller's API key", "example": "let-me-in",
  "schema": { "type": "string", "minLength": 1 } }
```

`withFacets(jsonObj { ... })` adds schema keywords without changing what is
accepted, for anything the helpers do not cover.

### Ready-made codecs

`String`, `Int`, `Long`, `Double`, `Boolean`, `UUID` and any Kotlin `enum` were
already resolved by `queryParam<T>(...)`. Added: `Instant` (`format:
date-time`), `LocalDate` (`date`), `LocalDateTime`, `URI` (`uri`) and
`NonEmptyString` — each with a real 400 for input it cannot parse, rather than
a 500 from `parse` throwing.

An `enum` is worth spelling out, because it is the case where taking the
`String` and checking it in the handler is the tempting mistake:

```kotlin
enum class Genre { FICTION, ESSAYS, SCIENCE, CRIME, POETRY, HISTORY }

val genre = queryParam<Genre>("genre", description = "Only this genre").optional()
```

```json
{ "name": "genre", "in": "query", "required": false,
  "description": "Only this genre",
  "schema": { "type": "string", "enum": ["FICTION", "ESSAYS", "SCIENCE", "CRIME", "POETRY", "HISTORY"] } }
```

`?genre=BANANA` is a 400 raised before the handler runs, naming the parameter
and what it expected; `?genre=crime` decodes, because matching is
case-insensitive against the constant names. What is *published* is the
constants as written. The handler is handed a `Genre?`, so there is no branch
in it for a word that is not one — which is the difference between a validation
rule the document carries and one only the handler knows about.

## More than one value

`?tag=a&tag=b`, `?id=1,2`, `X-Feature: beta,dark`. A parameter that carries
several values is declared by saying how the values are told apart, and what
one of them decodes to does not change — so the codec, its refinements and the
schema it publishes are the same ones a single-valued parameter uses.

```kotlin
val tags   = queryParam<String>("tag", description = "Only these tags").repeated().optional()
val ids    = queryParam("id", LongCodec.positive()).commaSeparated().optional()
val sort   = queryParam<String>("sort").pipeSeparated().optional()
val fields = queryParam<String>("fields").spaceSeparated().optional()

val search = endpoint(tags, ids, sort, fields) {
    get("orders")
    ndjson<Order>()
}

search streamedNow { (tags, ids, sort, fields) ->
    //                 List<String>?  List<Long>?  List<String>?  List<String>?
    Source.from(Store.search(tags.orEmpty(), ids.orEmpty()))
}
```

The handler receives `List<Long>?`, not a `String` it has to split — and
`?id=1,0` is a 400 naming the element that failed, because the refinement is on
the element:

```
{"status":400,"error":"Invalid parameter","detail":"Cannot decode '0' for 'id': expected a positive value"}
```

The spelling differs by location, because the encodings that are honest differ
by location:

| Declared | Wire | OpenAPI |
|---|---|---|
| `queryParam<T>(...).repeated()` | `?tag=a&tag=b` | `form`, `explode: true` — the default, so neither keyword is written |
| `queryParam<T>(...).commaSeparated()` | `?tag=a,b` | `form`, `explode: false` |
| `queryParam<T>(...).spaceSeparated()` | `?tag=a%20b` | `spaceDelimited` |
| `queryParam<T>(...).pipeSeparated()` | `?tag=a\|b` | `pipeDelimited` |
| `headerParam<T>(...).commaSeparated()` | `X-Tags: a,b` | `simple` — the default for a header |
| `cookieParam<T>(...).repeated()` | `Cookie: tag=a; tag=b` | `form`, `explode: true` — the default for a cookie |

There is no `headerParam(...).repeated()`, and no comma-separated cookie.
A header field has one name, and RFC 9110 already defines two lines of the same
field as meaning the one comma-joined field — so a comma-separated header
*reads* both spellings and there is nothing left for a second declaration to
say. A cookie is the opposite case: RFC 6265 excludes the comma from a cookie
value, so the joined form is a header the next proxy is entitled to mangle, and
repeating the pair is the encoding that survives.

The schema is `type: array` with the element's own schema — refinements
included — under `items`:

```json
{ "name": "id", "in": "query", "required": false, "explode": false,
  "schema": { "type": "array", "items": { "type": "integer", "format": "int64", "exclusiveMinimum": 0 } } }
```

`style` and `explode` are written only where they differ from what OpenAPI
already assumes at that location. A reader who meets one of them is therefore
entitled to conclude that something out of the ordinary is being said, which is
worth more than spelling out the default everywhere.

### Absent is null, not empty

An absent list reads as `null`; `required` still means the caller has to send
one. That is the decision the rest follows from, and it is made this way
because an empty list cannot be sent: `?tag=` carries no element, and neither
does a header that is not there. Reading absence as the empty list would leave
`required` with nothing to mean, and a handler with no way to tell "the caller
filtered by nothing" from "the caller did not filter".

Where a handler does not need the distinction, `.default(emptyList())` says so
in the one place it is being decided:

```kotlin
val tags = queryParam<String>("tag").repeated().default(emptyList())   // List<String>
```

An occurrence carrying nothing contributes no element, so `?tag=` — what a form
submits for a field nobody filled in — is the same as sending nothing, and
`?id=1,,2` is `[1, 2]`. A required parameter whose occurrences all turn out
empty is the same 400 an absent one gives.

Space around a separator is padding rather than content: `X-Feature: beta, dark`
is two values, because RFC 9110 makes the space optional in every list-bearing
header and reading `" dark"` as an element would be a decode failure nobody
could see in the header. An element that really does begin or end with a space
cannot travel joined at all — `repeated()` is the declaration that carries one.

### Encoding, and the separator

Writing a list is the inverse of reading one, which is what the test client and
the generated client both do. An element containing the separator its style
joins on is refused where it is written rather than sent:

```
'tag' joins its values with ',', and one of them contains it: 'a,b'.
Declare the parameter as repeated(), or encode the element so that it cannot.
```

The same goes for an element padded with the space a reader would strip.

An element carrying *nothing* is refused under every style, `repeated()`
included, because the reading rule that makes `?tags=` mean "a field nobody
filled in" is what an empty element runs into: an occurrence carrying nothing is
not an element, so `?tag=&tag=a` is two occurrences and a one-element list.
Preserving the empty one on the way in was the other way out of this, and it
would have handed every handler an element no caller meant to send for the sake
of the one codec — `StringCodec` — where an empty element is a value at all.

The alternative would be a list that came back a different length from the one
that went out, with nothing downstream able to tell.

## Cookies

A cookie has always been describable here as a *security scheme* —
`apiKeyCookie("session")` draws the padlock and puts the requirement in the
document. That covers the credential case and nothing else, and plenty of
cookies are not credentials: a locale, a feature flag, an A/B bucket, a consent
choice. Those wanted the treatment a header gets.

```kotlin
val locale  = cookieParam<String>("locale", description = "Which language to answer in").default("en")
val session = cookieParam("session", sessionId).optional()

val preferences = endpoint(locale, session) {
    get("preferences")
    json<Preferences>()
}
```

Same codecs, same `optional()`/`default(v)`, same refinements, same 400 for a
value that does not decode, and `in: cookie` in the document. There is nothing
new to learn because there is nothing new here.

The parsing is in `pelican-core` (`Cookies`), not in each backend, and that is
deliberate. All three servers have a cookie API and the three disagree in small
ways — one unquotes a `"value"`, another does not; one splits on `,` as well as
`;`. A cookie parameter is supposed to decode to the same value whichever
server is underneath, so the splitting happens once and the backends only hand
the header over. `CookiesTest` is where those decisions are written down.

Two details worth stating:

- **The first spelling of a name wins**, within a header and across several.
  RFC 6265 orders the more specific cookie first, so a later duplicate is the
  one to drop.
- **Values travel literally.** RFC 6265 already excludes `;`, `,`, a space and
  the control characters from a cookie value, so there is nothing to escape —
  and percent-decoding by default would quietly corrupt a value that contains a
  legitimate `%`. A codec that produces a character a cookie cannot carry fails
  in `Cookies.render`, where a client can still do something about it.

CORS does not add `Cookie` to `Access-Control-Allow-Headers`, however many
cookie parameters an endpoint declares. It is a forbidden header name: a script
cannot set it, the browser attaches it itself, and whether it does at all is
`allowCredentials`. Granting permission for something nobody can ask for would
be noise.

## Form bodies

```kotlin
data class SignIn(val user: String, val remember: Boolean, val visits: Int)

val credentials = formBody<SignIn>(description = "The sign-in form")

val signIn = endpoint(credentials) {
    post("sign-in")
    json<Session>()
}

signIn handledNow { form -> Session(form.user, form.remember, form.visits) }
```

`application/x-www-form-urlencoded` in the document, a data class in the
handler, and a 413 for an oversized one exactly as for a JSON body — it is a
strict read like any other. An endpoint that takes the same payload as JSON
*and* as a form says so with `or`; see [One body, several
encodings](#one-body-several-encodings) below.

The interesting part is in the middle. A form is a list of string pairs and
nothing else: `visits=3` is three characters whether the property is an `Int`,
a `String` or an enum. Something has to decide, and the honest answer already
exists — the schema the document publishes for `SignIn`. `formCodec` reads it,
turns the pairs into a JSON document of that shape, and hands *that* to the
configured `BodyCodec`.

The alternative was to hand the pairs to the codec and let it sort them out,
and it fails on the project's own terms: Jackson coerces `"3"` into an `Int`
happily and kotlinx.serialization refuses, so a form body would decode
differently depending on a choice that is supposed to change nothing.
`CodecAgreementTest` now reads one form through both and gets the same value.

That trip needs to read JSON as well as write it, which is why `pelican-core`
gained a small `parseJson`. It is not offered as a general-purpose parser and
nothing else uses it: bodies still go through the configured codec, because a
second JSON library in core is exactly the coupling this module exists not to
have.

The shaping is resolved once per endpoint at route-build time, alongside every
other codec, so:

- **A field that will not decode is the same 400 a query parameter gives**,
  naming the field and what was expected, rather than whatever the JSON library
  would have said about a document the caller never wrote.
- **`remember=on` is `true`.** Boolean fields go through the same `BooleanCodec`
  a query parameter uses, which is what an HTML checkbox needs.
- **An empty value for a non-string field is absence.** An untouched number
  input submits `""`, which is not a number; treating it as absent is what lets
  the type's own default apply instead of answering 400 for a field nobody
  filled in.
- **A field the schema does not describe is dropped.** Browsers send the name of
  the button that was clicked and whatever hidden inputs the page had; refusing
  those would make an ordinary HTML form impossible to point at an endpoint.
- **Only scalars and arrays of scalars.** A nested object would need a bracket
  convention nobody agrees on — `user[name]` in PHP, `user.name` in Spring —
  and inventing a fourth is worse than saying no. Saying no happens when the
  endpoint is bound, not on the request that trips over it.

### One body, several encodings

A page with no JavaScript on it posts a form; the same page's script posts JSON;
both are a `SignIn`. `or` is how a description says so:

```kotlin
val credentials = formBody<SignIn>(description = "The sign-in details") or jsonBody<SignIn>()

val signIn = endpoint(credentials) {
    post("sign-in")
    json<Session>()
}

signIn handledNow { form -> Session(form.user, form.remember, form.visits) }   // SignIn
```

One payload, several encodings, and that boundary is the whole feature. The
handler is given one value of one type, so what a request's `Content-Type`
selects is a *decode* and never a payload — the codecs the `Api` is configured
with already know how to read a `SignIn` out of either. Alternatives carrying
different types do not compile past the `or`, and a document offering a
different schema per media type is still refused on import, because that is a
union of payloads wearing a content map and `oneOf` with a `discriminator` is
how a union is said.

The document publishes one `content` entry per encoding, all with the same
schema, which is what a content map with several entries has always meant.

- **The request's `Content-Type` picks the codec**, matched on the media type
  with its parameters stripped, and resolved once per endpoint when the route is
  built — so an encoding the payload type cannot be read from is a startup
  failure rather than a 500 for whichever caller happened to choose it.
- **A media type the endpoint did not declare is a 415** naming the ones it did.
  So is no `Content-Type` at all: with a choice on offer, the header is the only
  thing that says which decode was meant, and guessing would read a body as
  something the caller never said it was.
- **A body with one encoding still ignores the header**, exactly as it always
  did. There is nothing to choose between, so the header carries no information
  the reader needs, and a 415 there would refuse callers that have been posting
  JSON with no `Content-Type` since before there was an alternative. A body that
  will not decode explains itself better than a refusal to look at it would.
- **The generated client sends the first**, for the same reason it calls the
  first of several `servers`: a client sends exactly one `Content-Type`, and the
  document's order is the document's answer. The typed test client does too, and
  `sending("application/json")` is how a suite asserts on the other one.

## Multipart uploads

```kotlin
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

Parts are `ParamKey`s, not fields read out of a body object. That is what makes
them ordinary inputs: list them on `endpoint(...)` and the handler receives them
typed and in order, a text part decoded by its own `PlainCodec` and a file part
as an `UploadedFile`. The `MultipartBody` holding them is assembled for you —
there is nothing to declare twice, and a description that declares parts *and*
a body of another kind fails when it is built.

In the document it is an object with one property per part: a text part carries
its codec's schema, refinements included, and a file part is `format: binary`.
What a file part expects to carry reaches the `encoding` block, which is what
tells Swagger UI's file picker what to offer. A `bufferedFile` publishes its
bound as `maxLength`, so what the server will hold is part of the contract
rather than something a caller discovers by being refused — and an import reads
it back into the same declaration.

### The envelope is parsed by core

Not by each backend, and that is the load-bearing decision. http4k-core has no
multipart support at all, Pekko's is a stream of its own shape and Ktor's is a
suspending one — three parsers would be three sets of behaviour to reconcile:
which part wins when a name repeats, what an absent `filename` means, whether a
text field is trimmed. One parser is one answer, and `MultipartTest` is where
that answer is written down.

It costs `pelican-core` about two hundred lines and no dependency. The subtle
half is that a part's body is a live window on the request: bytes are handed
over until the boundary, holding back the last few in case a delimiter straddles
two reads, and checking what *follows* a match — a boundary of `b0undary` makes
`\r\n--b0undaryish` a prefix match and not a boundary at all. Without that
check a part whose content contains a longer boundary-like line is silently cut
in half.

The backends supply an `InputStream` and nothing else: http4k's body already is
one, Pekko's comes from `StreamConverters.asInputStream` and is read on the
system's dispatcher rather than on the routing thread, and Ktor's comes from
`receiveChannel().toInputStream()` on `Dispatchers.IO`. That last one is the
honest cost of one parser instead of three: on the backend whose whole calling
convention is suspending, reading an upload blocks a thread.

### Two files, one of them streamed

A thumbnail beside a video, a signature beside a document, a checksum beside an
archive: an upload form with two files on it is ordinary, and it used to be a
description this library refused. `bufferedFile` is what makes it sayable.

```kotlin
val caption   = textPart("caption", StringCodec.nonEmpty())
val thumbnail = bufferedFile("thumbnail", maxBytes = 256 * 1024, contentType = "image/png")
val video     = filePart("video", contentType = "video/mp4")

val upload = endpoint(caption, thumbnail, video) {
    post("uploads")
    json<Uploaded>(status = 201)
}

upload handledNow { (caption, thumbnail, video) ->    // String, UploadedFile, UploadedFile
    Uploaded(caption, thumbnail.bytes().size, video.stream().transferTo(disk))
}
```

The handler is handed an `UploadedFile` either way, and that is deliberate:
where the bytes are is a decision the *description* makes, so a handler that
stops caring does not have to be rewritten. What differs is what it cost to get
there, and the declaration is where that is written down.

- **`maxBytes` has no default.** A default is exactly the number nobody would
  have looked at, and a buffered part is a caller-controlled allocation on every
  request. Naming it is the price of the feature, and it is one line at the
  place where somebody is choosing to pay it.
- **A part over its bound is a 413 naming the part** and the number its own
  declaration gave. Everything held in memory also shares one budget —
  `Api(maxBodyBytes = ...)` — so six parts declaring a megabyte each cannot add
  up to six megabytes of one request; where *that* is what ran out, the message
  names `maxBodyBytes` instead, because a caller told about what was left of a
  budget would go looking for a number nobody wrote down.
- **The refusal is delivered rather than dropped.** The rest of the envelope is
  read, up to a bounded overrun, before the 413 goes out: bytes left unread are
  bytes the client is still writing, and answering into a half-read upload gives
  it a broken pipe instead of the status that explains itself. This is the same
  bargain, and the same 64 KiB, the Pekko interpreter makes for a strict body.

### What it will not do

`UploadedFile.stream()` on a `filePart` is the request's own body, so nothing
holds it. Two consequences follow, and both are enforced rather than hoped for:

- **The streamed part has to be the last part on the wire.** Reading stops
  there, so anything sent after it has not been seen and never will be. A part
  still missing when it arrives is a 400 that says exactly this. An HTML form
  satisfies the rule by putting its last `<input type="file">` last, and both
  clients here write the parts in the server's own reading order whatever order
  they were declared in.
- **One streamed part per endpoint, declared last.** A second could only be
  reached by holding the first, and holding one silently is what a streaming
  upload exists not to do — so declaring two is a startup failure that names
  `bufferedFile` as the way out.
- **Nothing is declared after it** — not a second file, and not a text part
  either. Declaration order is what a caller reads the envelope's order off, so
  a part listed after the streamed one is one an HTML form or a `curl` would
  send where nothing reads it. On the wire a *required* part missing that way is
  a 400 that says why; an optional one is quieter still — the handler simply
  gets its default, and neither end can see that the caller sent something else.
  The description is refused when the endpoint is built, naming the parts that
  follow. The two clients here reorder rather than write such a request, which
  saves them and not whoever is reading the description for themselves.

The size limit works the way it does for `rawBody()`: the streamed part is
exempt, because nothing holds it whole. An upload larger than the limit is
served; a *field* or a buffered part larger than what bounds it is a 413.

## Declared failures

The success type has always been checked. Errors were documentation: a
`errorJson<Problem>(404, ...)` put a schema in the document and nothing made
the server produce it, so the spec could promise one shape while the handler
threw something else.

`orFail` closes that. Declare the failure as a value, list it on the output,
and it becomes part of the endpoint's type:

```kotlin
val noSuchUser = errorJson<ApiError>(404, "No user with that id")
val badApiKey  = errorJson<ApiError>(401, "Missing or bad API key")

val getUser = endpoint(userId) {
    get("users" / userId)
    json<User>() orFail noSuchUser
}                                  // Endpoint<Long, Outcome<ApiError, User>>

val placeOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders")
    json<Order>(status = 201).orFail(badApiKey, noSuchUser)
}
```

The binder for that shape takes a handler returning an `Outcome`, so the
failure has to be produced rather than thrown:

```kotlin
getUser handledOrFail { id ->
    Store.user(id)?.let { ok(it) } ?: noSuchUser(ApiError(404, "No user $id"))
}

placeOrder handledOrFail { (id, key, req) ->
    when {
        key != expected        -> badApiKey(ApiError(401, "Bad API key"))
        Store.user(id) == null -> noSuchUser(ApiError(404, "No user $id"))
        else                   -> ok(Store.create(id, req))
    }
}
```

Invoking the declaration is what produces the failure, so the status comes from
the declaration rather than from the payload's type — which is why two failures
can carry the same type, as `placeOrder`'s 401 and 404 do. The body is written
by the configured codec, as the declared type: the response is the one the
document promised.

What the compiler catches:

```
+(getUser handledOrFail { id -> Store.user(id)!! })
  e: Return type mismatch: expected 'Outcome<ApiError, User>', actual 'User'

+(getUser handledOrFail { _ -> otherFailure(OtherProblem("no")) })
  e: Return type mismatch: expected 'Outcome<ApiError, User>', actual 'Outcome<OtherProblem, Nothing>'

+(getUser handledNow { id -> Store.user(id)!! })
  e: Return type mismatch: expected 'Outcome<ApiError, User>', actual 'User'
```

With several payload types the error parameter infers to their common
supertype, so a sealed hierarchy of problems makes the handler's `when`
exhaustive as well.

#### What widening `E` stops the compiler catching

That inference has a cost, and it is worth knowing before a sealed hierarchy is
shared between endpoints. `Outcome` is covariant in `E`, so once `E` has widened
to the hierarchy, *every* failure of that hierarchy fits — including ones this
endpoint never declared:

```kotlin
val quote = endpoint(basket) {
    post("cart" / "quote")
    json<Receipt>().orFail(emptyBasket, unknownBook)      // E infers to ShopError
}

quote handledOrFail { basket ->
    badEmail(ShopError.BadEmail("a@b", "nope"))           // compiles. Never declared here.
}
```

The second example above is caught only because `otherFailure` carries a type
outside the hierarchy. Inside one, nothing says so until the response is being
written, and then it is an `UndeclaredResponse`: a 500 with a reference, the
whole story in the log through `onServerError`.

A single `orFail(failure)` pins `E` exactly and keeps the compile-time check, so
an endpoint with one declared failure never has this problem. For the rest, the
protection is that the mapping from a domain failure to a declared response is
written once — `ShopError.declared()` in `example/shop` — and read by every
endpoint that shares it, so there is one place to be wrong rather than one per
handler. What that shape cannot express is that `quote` declares two of the
three, which is why the refusal exists at all.

### One error model, not two

That sentence is the whole of what `example/shop` is for, because the mistake it
prevents is the one everybody writes first:

```kotlin
placeOrder handledOrFail { req ->
    runCatching { ok(desk.place(req)) }
        .getOrElse { e -> badOrder(Problem(codeOf(e), e.message ?: "Bad order")) }
}
```

This compiles, serves, and publishes a single 400 for an empty basket, a book
nobody stocks, and an undeliverable address alike. Three failures a bookshop
genuinely has become one shrug, and a caller reading the document cannot tell
which happened or which is worth retrying. The library did not push anyone
there: `orFail(emptyBasket, unknownBook, badEmail)` was always available. What
pushed is that the domain signalled by *throwing*, which hands the HTTP layer a
`Throwable` and no list of what it might be — and the only honest thing to write
against that is a catch-all.

So the fix is upstream of the endpoint. Have the domain **return** its failures
as a sealed hierarchy, and make that hierarchy the payload type of the declared
responses:

```kotlin
sealed interface ShopError {
    data class EmptyBasket(val message: String) : ShopError
    data class UnknownBook(val bookId: String, val message: String) : ShopError
    data class BadEmail(val email: String, val message: String) : ShopError
}

val emptyBasket = errorJson<ShopError.EmptyBasket>(400, "The basket has nothing in it")
val unknownBook = errorJson<ShopError.UnknownBook>(404, "No book with that id")
val badEmail    = errorJson<ShopError.BadEmail>(422, "That address will not take a receipt")

val placeOrder = endpoint(newOrder) {
    post("orders")
    json<Order>(status = 201).orFail(emptyBasket, unknownBook, badEmail)
}
```

`E` infers to `ShopError`, so the mapping is a `when` with no `else` — and a
fourth branch added to the hierarchy stops it compiling until it has a status of
its own:

```kotlin
fun ShopError.declared(): Outcome<ShopError, Nothing> = when (this) {
    is ShopError.EmptyBasket -> emptyBasket(this)
    is ShopError.UnknownBook -> unknownBook(this)
    is ShopError.BadEmail    -> badEmail(this)
}
```

Two things follow that the `runCatching` version cannot have. The statuses say
different things — 400 for a malformed request, 404 for a shelf that has no such
id, 422 for a request that is well formed and still not actionable — and the
payloads carry what a caller would act on: *which* book was not stocked, *which*
address was refused, rather than one `message` string to parse. Both are visible
in the published document, which is where a bookshop is interesting.

Nothing here is a library feature. It is the ordinary consequence of having one
error type rather than two, and the reason to write it down is that the shorter
wrong path is genuinely shorter.


The binders are named apart from the total ones — `handledOrFail`,
`handledByOrFail`, `streamedOrFail`, `streamedByOrFail` — rather than
overloaded, because a lambda's return type is inferred *after* overload
resolution, and `(I) -> T` cannot be told apart from `(I) -> Outcome<E, T>` at
the call site.

A failure is one *alternative* rather than the only kind. The same machinery
declares several successful responses — see
[More than one successful response](#more-than-one-successful-response), whose
`handledOneOf` is this binder under the name that reads right when the
alternatives are not failures.

Streaming works the same way: `ndjson<Order>() orFail noSuchUser` is a
`Outcome<ApiError, StreamOf<Order>>`, which is a failure decided before the
first element rather than mid-stream.

Tests read them back typed, through the same descriptions:

```kotlin
when (val result = app.outcome(getBookmark, 9_999L)) {
    is Outcome.Ok  -> error("expected a failure, got ${result.value}")
    is Outcome.Err -> result.error shouldBe NoSuchBookmark(9_999L, "No bookmark 9999")
}
```

### A failure that carries headers

A 429 is a body saying what happened and a header saying when to come back, and
both halves are part of the same response. The headers are listed on the
declaration, and the handler supplies their values where it returns the failure:

```kotlin
val retryAfter = responseHeader<Long>("Retry-After", "Seconds to wait")
val throttled  = errorJson<ApiError>(429, "Too many requests", retryAfter)

val placeOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders")
    json<Order>(status = 201).orFail(badApiKey, noSuchUser, throttled)
}

placeOrder handledOrFail { (id, key, req) ->
    when {
        key != expected -> badApiKey(ApiError(401, "Bad API key"))
        overQuota(id)   -> throttled(ApiError(429, "Slow down"), retryAfter of 30L)
        else            -> ok(Store.create(id, req))
    }
}
```

`retryAfter of 30L` is typed by the header's own codec — a `Retry-After`
declared as a `Long` takes a `Long`, and `retryAfter of "soon"` does not
compile. The values go on the *failure* rather than through `setHeader` for the
reason above: `setHeader` writes onto the request, and the interpreters put what
it wrote on whatever response comes back, so a header meant for the 429 would
have followed the 201 out of the same handler.

Three things are checked when the failure is produced:

- a header this failure never declared throws, the same bargain `setHeader`
  makes;
- a **required** header the call left out throws, naming it. This is stricter
  than the success path, where a missing required header is reported by
  `missingRequiredHeaders()` and not enforced — and it can be, because the two
  are not the same situation. A handler setting headers one at a time is never
  finished until the response is built, so nothing can tell mid-handler whether
  a promise is broken or merely not kept yet. This call is the whole answer, so
  everything needed to tell is in hand. The failed call is a 500 rather than a
  429 with nothing to act on;
- `responseHeader(...).optional()` is how a description says a header is only
  sometimes sent. Leaving that one out is fine, and it is simply not sent.

The arity is additive: a failure that declares no headers is returned as it
always was, `noSuchUser(ApiError(404, "No user $id"))`.

It reaches every interpreter. All three backends write the headers on that
response and no other; `pelican-openapi` publishes them under that status
alongside its body; a generated client reads them back as properties on the
typed failure; and `app.outcome(...)` hands them back decoded:

```kotlin
val refused = app.outcome(placeOrder, input) as Outcome.Err
refused.error shouldBe ApiError(429, "Slow down")
refused[retryAfter] shouldBe 30L
```

Nullable on the reading end, whatever the document promised: a server that left
a required header off is a finding for the test to make, not a reason for a
client to throw away the failure that did arrive. A header that arrived and
does not decode — `Retry-After: soon` under a `responseHeader<Long>` — reads
null for the same reason, which is also how a generated client's header
properties have always parsed.

What this does **not** cover, honestly:

- **Throwing still works, and is still unchecked.** `notFound(...)` and any
  other escaping exception become an `ApiError`, as before. `orFail` is opt-in
  per endpoint; nothing forces an endpoint to declare its failures.
- **A failure declared elsewhere with the same payload type still type-checks.** Two
  endpoints declaring `ApiError` can each hand out the other's value. The route
  checks it at response time and answers 500 rather than sending an
  undocumented status.
- **The success status is not in the type.** `json<Order>(status = 201)` and
  `json<Order>()` are the same type to a handler. Where an endpoint declares
  both, naming the one it is producing is what tells them apart — see
  [More than one successful response](#more-than-one-successful-response) — but
  that is identity at runtime, not a distinction the compiler makes.
- **A declared failure's headers are checked at the call, not by the compiler.**
  `throttled(problem)` with the `Retry-After` left out compiles and throws where
  it is produced. Making it a compile error would mean an `ErrorOutput` typed by
  the headers it declares — one type per arity — and `orFail(a, b, c)` holds its
  failures in one list, so every one of those types would have to erase back to
  the same thing to go in it. The check would have to be repeated at the point
  it was erased, which is the check that is already there.

### The one response an endpoint cannot produce

OpenAPI's `default` says "and anything else": whatever arrives under a status
this operation did not enumerate looks like *this*. Most published documents
have one, and it is usually the most useful sentence in the `responses` map —
it is what tells a caller that an unlisted failure is still a `Problem` and not
an HTML error page from a proxy.

It is describable, and it is not returnable:

```kotlin
val getUser = endpoint(userId) {
    get("users" / userId)
    defaultJson<ApiError>("Any other failure, rendered as an ApiError")
    json<User>() orFail noSuchUser
}
```

`defaultResponse(description, vararg headers)` for one with no body,
`defaultJson<T>(...)` for one that carries a payload. Both are statements and
neither hands anything back — unlike `errorJson<T>(...)`, whose whole point is
the value a handler returns. There is nothing to pass to `orFail`, nothing a
binder sees, and no way for a handler to answer with it. That is not an
omission: a handler answers with a status, and "some other status" is not one.
It reaches the document, `ErrorSpec.status` is null there rather than an `Int`,
and `pelican-openapi` writes it under the `default` key.

An endpoint may declare one. A second would not be published beside the first —
`default` is a single key in the response map — so it would silently replace it,
and the check that refuses it runs when the endpoint value is built.

What is still true is that `default` describes nothing Pelican *does*. The 500
an escaping exception becomes is rendered by `renderError`, the same way it was
before, and declaring a `default` neither changes that response nor promises
anything about it. It writes down what the service already does with the
statuses it did not list, for the benefit of whoever is reading the document.

## More than one successful response

`200 Order` beside `202 Accepted` is an ordinary REST shape — create-or-accept,
sync-or-async, `200 Updated` beside `201 Created` — and it was the largest thing
this library could not say. An endpoint's output was one type and one status, so
a document declaring both failed the import and a hand-written endpoint could
not describe one.

It is the same mechanism as `orFail`, with the word "fail" taken out of it.
Declare each response as a value, list them with `or`, and the handler produces
one by invoking it:

```kotlin
val orderAt = responseHeader<String>("Location", "Where the placed order lives")

val orderPlaced = json<Order>(status = 201, orderAt)
val orderQueued = json<Queued>(status = 202)

val submitOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders" / "submit")
    orderPlaced or orderQueued orFail badApiKey
}
```

```kotlin
submitOrder handledOneOf { (id, key, req) ->
    when {
        key != expected -> badApiKey(ApiError(401, "Bad API key"))

        tooBigToPlaceNow(req) -> orderQueued(Queued(ticket(id), position = req.quantity))

        else -> {
            val order = Store.create(id, req)
            orderPlaced(order, orderAt of "/users/$id/orders/${order.id}")
        }
    }
}
```

`submitOrder` is an `Endpoint<In3<Long, String, CreateOrder>, Outcome<ApiError, Any>>`
— the same shape `orFail` alone produces, with more than one thing on the
success side. Which means everything about it is already familiar:

- **Invoking the declaration is what fixes the status**, so `json<Order>(200)`
  and `json<Order>(201)` stay distinguishable although a payload cannot tell
  them apart. Identity, not type — the answer `ErrorOutput` already gave for two
  failures carrying one type.
- **`handledOneOf` is `handledOrFail` under the name that reads right** when the
  alternatives are not failures. Same signature, same `Outcome`; two names
  because `handledOrFail` on an endpoint that declares no failure reads as a
  mistake, and the call site is where a name is read. `handledByOneOf` is the
  asynchronous one on Pekko and http4k.
- **`ok(value)` names none, and means the first declared success.** With one
  success that is the only one there is, so nothing about a single-response
  endpoint changed. Where the response it means declares a header it always
  sends, a bare `ok` is refused when that response is rendered: naming no
  response means carrying no header, and a 201 without the `Location` the
  document promises is the one wrong answer a caller cannot see is wrong. Name
  the response instead. This is only reachable where there is a choice — a
  header on an endpoint's *only* response is already refused when the endpoint
  is built.
- **A response the endpoint never declared does not compile**, exactly as an
  undeclared failure does not.

The alternatives are declared as *values* rather than inside the block, because
a response a handler names has to be nameable — the same reason a failure shared
between endpoints is a top-level `val`. `json`, `text` and `empty` exist at top
level for this, spelled identically to the `EndpointBuilder` members, as
`errorJson` already is. Inside a block the member wins, so `json<Order>()` there
is the call it always was.

### A header on one response and not the other

A `Location` belongs to the 201. Declared with `emits(...)` it would be
documented on the 202 as well — a `Location` for an order that does not have one
yet — so it goes on the response that carries it, and the handler supplies its
value where it produces that response:

```kotlin
orderPlaced(order, orderAt of "/users/$id/orders/${order.id}")
```

The same three checks a declared failure's headers get: a header this response
never declared throws, a required one left out throws naming it, and
`responseHeader(...).optional()` is how a description says it is only sometimes
sent. `emits(...)` still means what it meant — the endpoint's own promise,
settable with `setHeader` from anywhere, documented on every successful
response.

Declared on an endpoint's *only* response, a header is refused when the endpoint
is built: the handler for one response returns the payload alone and never sees
the declaration, so nothing could supply it.

### What a caller sees

`pelican-openapi` publishes one entry per declared 2xx, each with its own schema,
its own media type and its own headers — which is what OpenAPI's `responses` map
always could say.

`pelican-codegen` gives the endpoint a sealed type of its own, one member per
status, exactly as it already does for declared failures:

```kotlin
sealed interface SubmitOrderResult {
    val status: Int
    data class Created(val body: Order, val location: String?) : SubmitOrderResult
    data class Accepted(val body: Queued) : SubmitOrderResult
}
```

so a `when` over what the call produced is exhaustive, and a third 2xx added to
the endpoint stops the callers that do not handle it from compiling. Handing
back the payloads' common supertype instead would have compiled everywhere and
said nothing. The `Outcome` wrapper is the *failure* side's doing: an endpoint
that answers two ways and declares no failure returns the sealed type directly.

`pelican-test` reads it back through the descriptions:

```kotlin
val placed = app.outcome(submitOrder, input)
placed shouldBeResponse orderPlaced
(placed as Outcome.Ok)[orderAt] shouldBe "/users/1/orders/7"
```

`pelican-import` reads several 2xx into the descriptions and writes them out as
`or`, with each response's headers on that response. The refusal it used to make
is gone.

### What stays refused

- **Two responses sharing a status**, success or failure. The status is the only
  thing separating two responses on the wire — it is what a test client, a
  generated client and a browser all match on — so a second 200 is a response no
  reader could pick out. Refused where the output is declared, naming the status.
- **A streamed alternative among several.** `ndjson<Order>() or empty(202)` is
  refused: naming a response is what produces it, and producing a stream means
  handing over the backend's own type — a `Source`, a `Flow`, a `Sequence` —
  which core cannot name. The alternative would be an `invoke` per backend with
  the element type unchecked, which is three copies of the one thing the phantom
  marker exists to avoid. A stream is still a success; it is just the only one,
  and `ndjson<Order>() orFail noSuchUser` is unchanged — a failure decided before
  the first element, as before.

Two 2xx with *different media types* are not refused: `json<Order>() or
text(status = 202)` publishes `application/json` under 200 and `text/plain`
under 202, which is one content map per status and exactly what the format is
for. What could not be told apart is two responses under one status, and that is
the refusal above.

## A whole list, or a stream of one

`json<List<Book>>()` and `jsonArray<Book>()` put the same bytes on the wire —
one JSON array — and publish the same schema: `type: array` over the element.
A caller reading the document cannot tell which was chosen, and neither can a
caller reading the response. What differs is *when* the bytes are written.

`json<List<T>>()` encodes a list that is already in hand, in one go, with a
`Content-Length`. The handler is an ordinary `handledNow` returning a `List<T>`,
and no backend stream type is involved: a shelf of sixteen books never has to
become a `Source`, a `Sequence` or a `Flow`.

`jsonArray<T>()` frames a stream, flushing elements as they are produced. The
handler is `streamedNow` and returns the backend's own stream type. Nothing is
held whole, so the response can be larger than memory and the first element
reaches the caller before the last one exists.

So: a bounded collection already loaded is `json<List<T>>()`; a query nobody has
counted is `jsonArray<T>()`. Since the document is the same either way, this is
a decision about memory and latency rather than about the contract, and moving
from one to the other later breaks nothing that was promised. `example/shop`
lists a catalogue the first way and `example/bookmarks` streams one the second
way; `ShopContractTest` pins the two schemas being identical.

## How streaming stays backend-agnostic

Core cannot name `Source` — it has no Pekko dependency. So streaming outputs are
typed with a phantom marker:

```kotlin
class StreamOf<T> private constructor()      // no instances, ever

// core
inline fun <reified T> ndjson(status: Int = 200): NdjsonOutput<T>   // : Output<StreamOf<T>>
```

and the backend cashes it in for its own type:

```kotlin
// pelican-pekko
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedNow(f: (I) -> Source<T, NotUsed>): ServerEndpoint

// pelican-http4k
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedNow(f: (I) -> Sequence<T>): ServerEndpoint

// pelican-ktor
infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedNow(f: suspend (I) -> Flow<T>): ServerEndpoint
```

That third line is the whole claim tested rather than argued: the marker was
designed before there was a second backend, and neither the second nor the third
needed any change to it — including the third, whose handlers suspend and whose
stream type is nothing core has ever heard of.

Core also owns the framing — `NdjsonOutput.frame(codec, value)` renders one
element — so the backend only supplies wire mechanics. Nothing about NDJSON or
SSE format is duplicated per backend.

`jsonArray<T>()` is the deliberate exception. Pekko already frames a stream of
JSON documents as an array via `EntityStreamingSupport.json()`, and
reimplementing brace-and-comma handling in core would be strictly worse than
calling it — so that one output is framed by the backend. It sits alongside
NDJSON and SSE rather than replacing them. Neither http4k nor Ktor has an
equivalent, so each supplies the brackets and commas itself (`jsonArrayFrames`
in both); that is what "framed by the backend" costs when the backend cannot do
it for you — about ten lines.

## Streaming behaviour

- `ndjson` / `sse` / `jsonArray` map the source element-by-element into
  `HttpEntities.createChunked`. Back-pressure runs from the socket through Pekko
  Streams to your source. Tests throttle a source and fail if the first frame
  does not arrive well before the last — for SSE and for the JSON array, whose
  whole risk is looking streamed while quietly being assembled first.
- On http4k the same outputs become a body backed by `FrameInputStream`, which
  encodes one element per read: the sequence is walked at the speed the socket
  drains. `StreamingTest` asserts the laziness without a socket — read one
  frame, and exactly one element has been produced — and `StreamingTimingTest`
  asserts the delivery over one.
- On Ktor the same outputs are written with `respondBytesWriter`, collecting the
  handler's flow and flushing each frame as it is encoded, so the flow is walked
  at the speed the socket drains. Unlike http4k, no engine can undo this by
  buffering: the flush is the interpreter's, not the engine's.
  `StreamingTimingTest` asserts the delivery over a socket.
- `rawBody()` hands the handler the request body unconsumed, as a
  `Source<ByteString, Any>` via `.toSource()` on Pekko, an `InputStream` via
  `.toStream()` on http4k, or a `ByteReadChannel` via `.toChannel()` on Ktor.
  `/echo` pipes request straight to response.
- `jsonBody<T>()` and `formBody<T>()` are the strict reads, because neither a
  JSON object nor a form can be decoded incrementally into a data class.
  `strictBodyTimeoutMillis` bounds them.
- A multipart body is neither: the parts it holds are read as they arrive,
  within one shared budget, and its streamed part is handed over unread — which
  is what makes an upload larger than `maxBodyBytes` something the service can
  serve.

## Testing

`pelican-test` interprets the descriptions a third way: as a client. The same
values that become a route and an OpenAPI document also know how to *build* a
request, because `Inputs` carries both directions — `extract` for the server,
`inject` for the client.

```kotlin
val app = ordersApi().inMemory()

val user: User = app.call(getUser, 1L)                       // typed in, typed out
val orders: List<Order> = app.collect(streamOrders, In4(1L, 7, null, null))
assertEquals(401, app.response(placeOrder, In3(1L, "wrong", CreateOrder("anvil"))).status)
```

No path strings and no hand-written JSON, which is the point: rename a path
parameter or change an input's type and the *tests* stop compiling rather than
starting to 404. Responses are decoded with the same `Codecs` that encoded
them, so a green test also proves the codec round-trips. What that does *not*
cover is the URL itself, which is the contract a caller holds — see [Pinning the
URL](#pinning-the-url) below.

A body that is not JSON is built from the description too. A cookie parameter
becomes a `Cookie` header, a form body is encoded against its published schema,
and a file part is the same `UploadedFile` the handler receives — so a test
writes what a caller would write and nothing has to know the wire format:

```kotlin
app.call(signIn, SignIn("ada", remember = true, visits = 3))

app.call(importOrders, In3("March", manifest, UploadedFile("orders.csv", "text/csv", stream)))
```

A body declaring several encodings is sent as the first of them, as a generated
client sends it. `sending(...)` is how a suite asks for the other one, and it is
the only place the choice is offered — a media type parameter on `call`,
`response` and `request` alike would widen three signatures for the one endpoint
in a suite that declares a choice:

```kotlin
app.sending("application/json").call(signIn, SignIn("ada", remember = true, visits = 3))
```

Assertions are written against `ApiClient`, which knows only a `Transport`, so
one suite runs both ways:

```kotlin
class InMemoryContractTest : ClientContractTest() {
    override fun open() = ordersApi().inMemory()             // no socket, no port
}

class OverHttpContractTest : ClientContractTest() {
    override fun open() = ordersApi().start(port = 0).client()   // real connection
}
```

`ApiClient`, the assertions and `HttpClientTransport` are in `pelican-test`,
which depends on `pelican-core` and nothing else — a socket transport is the
JDK's own `HttpClient`, and a request built from a description is not a
backend-shaped thing. The *in-memory* transport is, so it lives in a module
named for its backend: `inMemory()` comes from `pelican-test-pekko` and
`inMemoryHttp4k()` from `pelican-test-http4k`. A service on Ktor takes neither
and still gets the typed client.

`ApiClient(HttpClientTransport(url), codecs)` points the same suite at a
deployed service — every call in it, including one whose endpoint declares a
`servers` of its own. See
[One operation served somewhere else](#one-operation-served-somewhere-else) for
why the test client is the reading that ignores that.

### Pinning the URL

Building the request from the description is what makes a typed call
type-checked, and it is also the one thing it cannot be evidence about. The
client injects into the same `Inputs` the server extracts from, so renaming a
path segment or a query parameter moves both ends together: every typed test
still passes, the OpenAPI document still agrees with the server, and the callers
holding the old URL get a 404 that nothing in the suite predicted.

That is not an argument for hand-written URLs everywhere — a suite of them
drifts off the service and asserts about strings rather than behaviour, which is
what the typed client exists to stop. It is an argument for writing them down
*once*, as the contract, separately from the tests that exercise behaviour:

```kotlin
app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"
app.request(listBookmarks, In2(20, Slug("streams"))) shouldBuild "GET /bookmarks?limit=20&tag=streams"
app.request(createBookmark, In2(key, created)) shouldBuild "POST /bookmarks"
app.request(deleteBookmark, In2(1L, key)) shouldBuild "DELETE /bookmarks/1"
```

`request` builds without sending, so a pin costs no server, no port and no
transport — the whole API fits in one test. The literal is duplication on
purpose: a copy that does not move when the description moves is the only thing
that can catch the move.

`shouldBuild` compares the method, the path and the query string, which is
exactly what a caller had to type. It says nothing about headers or bodies —
those are the payload, not the address, and the typed call already checks them
against the declaration.

The split is worth stating in one line: **behaviour tests should not break on a
rename, and the contract test should.** `BookmarksContractTest` holds both, and
`AllBackendsTest` pins the greetings URLs and then sends the pinned call, so
three interpreters agreeing on how to *build* a request is backed by each one
answering at the address itself.

### Golden files

A pin catches the rename it was written for, and somebody has to write one per
endpoint. The change nobody writes a pin for is the one that costs most: a
required field added to a request body passes every typed test in the suite,
because the client that sends it is built from the same description the server
reads it with.

`pelican-test-golden` records what each endpoint publishes into a file, and
compares the next run against that file as a contract:

```kotlin
private val golden = Golden()

@Test fun `every endpoint publishes what it published`() {
    golden.operations(ordersSpec())          // golden/operations/placeOrder.json, and one per endpoint
}
```

The comparison is between two OpenAPI documents, classified from the caller's
side of the wire. A deleted endpoint, a new required parameter or body field, a
field that became required, a removed status, a response field that disappeared
or became nullable, a renamed `operationId` and an added security requirement
each fail the test and name the caller they break. A new endpoint, a new
optional parameter, a new response field or a rewritten summary rewrites the
golden and passes — a check that fails on changes nobody has to act on is one
whose author learns to accept its output unread, which is how a real break gets
waved through. `Golden(strict = true)` fails on every difference where that is
wanted.

An endpoint deleted is caught the same way: its golden is left with nothing to
regenerate it, and a recording nothing produces is read as the deletion it is.

The classification is `pelican-openapi`'s, over two documents, and is public on
its own:

```kotlin
apiChanges(published, ordersSpec().openApi()).filter { it.compatibility == Compatibility.BREAKING }
```

A failure reads as the decision it is — the count first, then the operations,
then what each change does to somebody:

```
placeOrder.json — 1 change breaks callers.

  POST /users/{userId}/orders
    ✖ `currency` in the request body (application/json) is new and required
        every caller that is not sending it is refused
```

The wire recordings are the other half — `request` and `exchange` record the
bytes a call puts on the wire, as text, where any difference fails:

```kotlin
golden.request("place-order", requestsOnly(JacksonCodecs).request(placeOrder, In3(7L, key, order)))
```

The whole of it — the table of what breaks, accepting a break you meant, and the
`PELICAN_GOLDEN_UPDATE` switch — is in
[docs/golden-testing.md](golden-testing.md).

### Asserting on an outcome

An endpoint that declares its failures answers with an `Outcome`, and a test
nearly always knows which side it wants. Saying so with a `when` costs a branch
that must not happen and an `error("...")` inside it — three lines of
scaffolding around one line of assertion, with whatever failure message the
author happened to type.

```kotlin
val bookmark = app.outcome(getBookmark, 1L).shouldBeOk()          // returns Bookmark

app.outcome(getBookmark, 9_999L) shouldBeError NoSuchBookmark(9_999L, "No bookmark 9999")

app.outcome(getBookmark, 9_999L).shouldBeError()                  // returns NoSuchBookmark
    .shouldBeInstanceOf<NoSuchBookmark>()
```

The no-argument forms return the value, which is the join to whatever matcher
library the suite already uses: assert the shape here, carry on there. Pelican's
own assertions throw plain `AssertionError`, understood by every runner, so this
module puts no matcher library on your classpath.

When two failures carry the *same* payload type under different statuses,
equality cannot tell them apart — the declaration the handler named is what
fixed the status, so that is what to assert on:

```kotlin
app.outcome(placeOrder, In3(1L, "wrong", CreateOrder("anvil"))) shouldBeFailure badApiKey
app.outcome(placeOrder, In3(9_999L, key, order))                shouldBeFailure noSuchUser
```

`ApiClient` is `AutoCloseable`, and closing one it owns the system for *waits*
for that system to terminate — `terminate()` alone is asynchronous, so
returning straight after it would let a suite accumulate thread pools while
reporting each client closed. `PelicanServer.stop()` completes on termination
for the same reason. Pass an existing system to `inMemory(system)` to share one
across classes; the client will not shut down a system it borrowed.

In-memory is not a shortcut: `Route.function` seals the route, so path
matching, parameter decoding, response building and rejection-to-status are the
ones a bound server uses. It is also not automatically *faster* — an
`ActorSystem` still has to start, and in this repo the two suites run within
~0.2s of each other. The wins are no port to bind and easy sharing of one
system across classes.

What it does not cover is everything below the route: chunk framing on the
wire, connection handling, TLS. Keep a socket-level test for those.

Streaming stays observable in memory. `collect` flattens a stream, so for
delivery-timing assertions `InMemoryTransport.exchange` hands back the response
with its entity unconsumed — the back-pressure test in `InMemoryContractTest`
is the socket test, minus the socket.

## What the build checks

Six gates beyond the tests, each of which exists because a claim in this
document would otherwise be unverified.

**detekt**, on the type-resolving `detektMain`/`detektTest` tasks rather than
the plain one, with the deviations in `config/detekt/detekt.yml`. That file is
written as a list of decisions: the rules turned off for wildcard imports and
filenames are the same two `.editorconfig` disables, made for the same reason;
HTTP status codes are exempt from `MagicNumber` because this library hands them
to you as integers, and a private `NOT_FOUND` beside a public parameter taking
`404` would read as though the two were different things.

Two rules are off because the default answer is wrong for the one place here
that trips it, and both are recorded where they are disabled: `InjectDispatcher`
where the multipart parse pins its own dispatcher on purpose, and
`AbstractClassCanBeInterface` on `Output`, which is published API a lint rule
should not be reshaping. The three that were off because detekt 1.23.8 could
not resolve Kotlin 2.4 — `UnreachableCode`, `RedundantSuspendModifier`, and the
JDK 25 version string that made the Java 25 CI job skip linting altogether —
are back on under detekt 2, which analyses with a current frontend. All three
CI jobs lint.

**FunctionalStyleTest** holds a line detekt cannot state. `ForbiddenMethodCall`
could ban `mutableListOf` outright now that the stdlib resolves again, but that
was never the claim: what matters is whether the mutation escapes. A builder
that fills a local list and hands back a read-only view is the shape half of
`Endpoint.kt` is written in, so the claim lives in a test that reads the
sources. It is a ratchet, not a ban: sixteen files accumulate into a mutable
collection and hand back something immutable, which is what a builder is, and
each is listed with why. What it stops is the next file quietly starting.

A test that reads files has to say which ones. The eleven library modules it
judges are listed in `pelican-core/build.gradle.kts`, which declares their
`src/main/kotlin` directories as inputs of `:pelican-core:test` and hands the
same list to the test as a system property — one list, snapshotted by Gradle
and walked by the test. Before that the test found the sources itself and
Gradle had no idea it read anything: the task stayed up-to-date over a changed
source file, and a violation rode several green builds, visible only under
`--rerun-tasks`. The paths are content-hashed with relative path sensitivity,
which is as loose as it can safely go — the gate is a regex over raw text, so
it reads a comment exactly as it reads code.

**Kover**, aggregated across modules rather than per-module — a line in
`pelican-core` is exercised by the tests in `pelican-pekko` as often as by its
own — with a floor of 80% wired into `check`. It sits at 87% line, 70% branch.

**OpenApiSpecQualityTest** reads the emitted documents back with
swagger-parser, an implementation that did not write them: `$ref`s resolved,
3.1 conformance, every path keeping its operations and responses, every
security requirement naming a scheme the document defines. It also pins the
fact that keeps 3.1 the default — that swagger-parser still reads a 3.2.0
document as nothing at all — so that when the parsers catch up, the reason for
the default expires loudly rather than quietly. A generator marking
its own homework is the failure mode it rules out, and it caught a wrong
assumption the first time it ran. The YAML rendering is held to the same
standard and to one more: the parser has to read it into the same document it
read the JSON into, which is a claim about the quoting rules that no test
written beside the emitter could make.

**checkOrdersClient**, from the repository's own Gradle plugin, regenerates the
committed example client and fails when it is not what the descriptions
produce. It is on `check`, so a change to an endpoint that nobody regenerated
for stops the build rather than being noticed by whoever compiles the client
next.

**ImportedOrdersTest** closes the loop the other way. The document this build
publishes is imported back into endpoint descriptions, compiled into the
example's test source set, and asked to publish a document of its own; the two
contracts are compared. It is the only test either direction has that the other
one cannot fake, and the compiler is doing half the work — generated Kotlin
that does not compile fails the build here.

What goes round that loop includes a discriminated union: `PaymentMethod`, a
sealed hierarchy served by `payOrder`, whose `bank_transfer` branch is a class
called `BankTransfer`. The pair is the case that cannot survive on shape alone
— a document that publishes the shape and not the name sends every reader the
class name instead — so the loop is asked for the names as well, and the
generated client is called with one against a running server.

Two smaller things. The Pekko route tests run through Pekko's own route
testkit, behind a JUnit 5 extension in `PekkoRouteTestKit` — the testkit drives
its `ActorSystem` from a JUnit 4 `@Rule`, which Jupiter does not run. And the
`benchmarks` module measures what the interpreter costs against hand-written
http4k and Pekko routes. It is a JMH harness rather than a test — forked,
warmed and blackholed, with allocation read off `-prof gc` — and `build` never
runs it; see [What it costs](what-it-costs.md).

## Run it

```bash
./gradlew build                     # every module and the plugin: tests and every gate above
./gradlew :example:run              # server on :8080, on Pekko
./gradlew :example:runHttp4k        # the same service on :8080, on http4k
./gradlew :example:runBackends      # the small example on all three backends at once
./gradlew :example:runCodecs        # one service, served three times over three JSON libraries
./gradlew :example:generateOrdersDocument  # spec, no server
./gradlew :example:generateOrdersClient    # the Kotlin client, likewise
./gradlew :example:generateImportedEndpoints  # the document, read back as descriptions
./gradlew :benchmarks:jmh           # the JMH benchmarks: about six minutes, never run by `build`
```

```bash
curl localhost:8080/users/1
curl 'localhost:8080/users/1/orders?limit=5&status=SHIPPED'   # NDJSON, chunked
curl -N 'localhost:8080/users/1/orders/watch?limit=10'        # SSE, 100ms apart
curl -N 'localhost:8080/users/1/orders/list?limit=10'         # JSON array, chunked
open  localhost:8080/api-docs                                 # Swagger UI
```

## What isn't here

- **A published client artifact.** `pelican-test` derives a client from the
  descriptions, but it is scoped at testing: blocking calls, no retries, no
  connection pooling worth the name.
- **More than one *streamed* file part on a multipart endpoint, or one that is
  not last.** The part is handed over as a live stream, so reading stops at it:
  a second could only be reached by holding the first, and anything sent after
  it is never seen. Both are refused out loud when the endpoint is built — the
  second naming whatever was declared after the stream, of whatever kind — and a
  request that puts a part after it anyway is a 400 naming the part it wanted. Holding a file *is*
  sayable, with `bufferedFile(name, maxBytes = ...)`, which is what makes an
  ordinary two-file upload form describable; see
  [Multipart uploads](#multipart-uploads).
- **A binary file part from `pelican-test`.** `RequestSpec` carries a `String`
  body, so the typed test client uploads text. The generated client streams
  arbitrary bytes and so does anything driving a `Transport` directly; making
  the test client match would mean a byte-carrying `RequestSpec` for the one
  case that needs it, which has not been worth it yet.
- **Nested objects in a form body.** Only scalars and arrays of scalars, since a
  nested one would need a bracket convention nobody agrees on. Refused when the
  endpoint is bound.
- **`filename*` in RFC 5987 form.** A part's `filename` is read from the plain
  parameter; the extended, charset-tagged spelling is not decoded.
- **OpenAPI 3.0.** The emitter writes 3.1.0 or 3.2.0 — see
  [Which version the document says](#which-version-the-document-says-and-how-to-choose)
  — and it will not write 3.0. The reasoning, and what to do if your tooling
  only reads 3.0, are under
  [Moving from 3.0.3 to 3.1.0](#moving-from-303-to-310). The *importer* reads
  3.2, 3.1, 3.0 and Swagger 2.0 alike, which is a different question: reading
  an old document costs one normalising pass, and writing one would cost a
  second emitter that could not be faithful anyway.
- **The 3.2 fields nothing in a description answers** — `$self`, `Server.name`,
  Tag Objects and their `parent`/`kind`, `additionalOperations`,
  `in: "querystring"`, `prefixEncoding`, the `deviceAuthorization` flow and the
  rest. The full list, with what each would need in order to be written, is
  under [What 3.2 added that is not emitted](#what-32-added-that-is-not-emitted).
- **A lenient import.** `pelican-import` refuses an operation it cannot fully
  describe rather than generating a weaker one, and the way through is a
  per-operation `exclude` list, or a per-schema `discriminator(...)` where what
  is missing is which property tells a `oneOf`'s branches apart. There is no
  global switch and no branch inference, on purpose — see
  [Importing an OpenAPI document](#importing-an-openapi-document).
- **More than six typed inputs.** `endpoint(a..f)` is the largest overload;
  past that `endpoint(lensInputs)` takes the whole `Params`.
- **`callbacks`.** A request made *during* an operation, to a URL taken out of
  that operation's own payload through a runtime expression, and nothing in an
  endpoint description evaluates one. Its neighbour in the specification, a
  top-level `webhooks` entry, *is* described and generated — one call to a URL a
  subscriber registered. See
  [Webhooks](#webhooks-the-calls-the-service-sends).
- **A binder for the receiving end of somebody else's webhook.** There is
  nothing to add: a webhook you receive arrives at a path on your own service,
  so it is an ordinary `endpoint(...)` with a handler. `webhook(...)` is for the
  half you send.
- **A fourth server backend.** `pelican-ktor` was the third, and cost what
  `pelican-http4k` cost: the binders above, a request-to-`Params` step and a
  response writer, in about 500 lines including the comments.
- **A Ktor wiring of the *orders* example.** The small `example/backends/`
  service runs on all three; the larger orders service is bound on Pekko and
  http4k only, and `ClientContractTest` runs against those two.
- **Response negotiation** — one media type per response. An endpoint may answer
  two statuses two ways, but nothing reads `Accept` to choose between two
  renderings of the *same* response. The request direction is not the same
  gap: a body declares its encodings and `Content-Type` picks one, because the
  caller says which it sent rather than which it would prefer back. See
  [One body, several encodings](#one-body-several-encodings).
- **A streamed response among several.** An endpoint declaring more than one 2xx
  names the one it is producing, and producing a stream means handing over the
  backend's own type, which core cannot name. A stream is still a success; it is
  just the only one. See
  [More than one successful response](#more-than-one-successful-response).
- **Per-endpoint CORS, and the newer preflight extensions.** The policy is one
  value on the `Api`; `Access-Control-Allow-Private-Network` and friends are not
  emitted.
- **Validation of a credential.** Pelican has no idea what your token means, so
  nothing here checks one. What it does supply is the requirement as a value on
  the endpoint, and a `Filter` slot to enforce it from — see the security
  chapter above, and `example/secured/SecuredReports.kt` for a filter that
  reads `endpoint.security` and needs no second list.
- **Two shapes where the two schema sources genuinely disagree.** Neither is
  about nullability — `CodecAgreementTest` now covers that at property level,
  inside a `List` and a `Map`, through nested generics and through a body whose
  own type is a collection — and both predate the 3.1 move:
  - **`Set<T>`.** swagger-core knows it is a set and emits `uniqueItems: true`;
    the descriptor walker sees kotlinx's `StructureKind.LIST` and emits a plain
    array. The Jackson side is the more informative of the two.
  - **A generic class, instantiated.** swagger-core names the component for the
    instantiation — `Box<Inner>` becomes `BoxInner` — while the descriptor
    walker names it for the class, `Box`, and would therefore collide if a
    second instantiation appeared in the same document. The Jackson side is
    right here too.

  Both are the Jackson side knowing something kotlinx's descriptors do not
  carry, so closing them means teaching the walker to recognise a set and to
  name an instantiation. Not written; a service that stays on one codec sees
  neither.
- **A payload whose type is spread over two properties.** A `oneOf` branch that
  is itself a discriminated `oneOf` — `kind: "electronic"` above and
  `type: "card"` below — is refused on the way in, and refused on the way out
  where the annotations say it. Not a gap: neither JSON library reads a type at
  two levels, both say so on purpose, and `sealed interface Inner : Outer` would
  have generated cleanly and decoded nothing. A *Kotlin* hierarchy nested inside
  another is fine and travels flat — one discriminator, the leaf's own name —
  which is what both codecs now publish and what imports back. The whole of the
  reasoning is under
  [Two levels of hierarchy](#two-levels-of-hierarchy); what the intermediate
  level costs is its own name on the wire, which it never had.
- **`anyOf` of several branches, and `not`.** Both are still refused, and the
  reasoning is in "What it refuses" above rather than here: neither is a gap
  waiting to be filled, they are shapes with no faithful Kotlin.
- **405 vs 404 fidelity is the router's, not Pelican's** — a wrong method on a
  known path answers 405 on http4k, whose router separates "no such path" from
  "not that method", and 404 on Ktor, whose router does not. On Pekko it is 405
  only when no endpoint declares that method; when one does, that endpoint's
  path rejection swallows the method rejection and Pekko answers 404.
  `MethodMismatchTest` and `KtorInterpreterTest` hold all of this.
- **A *compile-time* check that every declared endpoint is bound.** `Api(covers
  = ...)` closes this at startup — hand it the list the spec is built from and
  an unbound endpoint fails the constructor — but nothing in Kotlin's type
  system says a list covers a set of values, so it stays a runtime check. A KSP
  processor could make it a compile error; that is not written.

## Versions

Kotlin 2.4.10 · Pekko 1.7.0 · Pekko HTTP 1.4.0 · http4k 6.58.0.0 · Ktor 3.5.2 ·
Jackson 2.22.2 · swagger-core 2.2.54 · kotlinx.serialization 1.11.0 ·
jsoniter 0.9.23 · slf4j-api 2.0.18 · snakeyaml-engine 2.10 · JDK 21 ·
Gradle 9.7.1

This is the only copy of that list. The README carried a second one until the
two disagreed about half of it, and now points here instead.

http4k built against a newer stdlib than the compiler reading it fails on
metadata, so the two are bumped together; `pelican-http4k/build.gradle.kts`
records the pairing where the version is set.

The Gradle plugin is built against the Gradle 9.7.1 API as Java 21 bytecode, so
the build applying it runs on Java 21 or newer.
