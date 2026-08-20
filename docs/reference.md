# Pelican — reference

The long version: every module, every trade-off, and the limitations spelled
out. Start with the [README](../README.md) if you have not read it yet.

Endpoints are values; interpreters turn them into a Pekko HTTP route, an http4k
`HttpHandler`, a set of Ktor routes, and an OpenAPI 3.1.0 document.

## Modules

| Module | Depends on | Contains |
|---|---|---|
| `pelican-core` | **nothing** | endpoint descriptions, plain-value codecs, a minimal JSON tree. No HTTP library, no JSON library. |
| `pelican-openapi` | core | descriptions → OpenAPI 3.1.0 |
| `pelican-jackson` | core, Jackson, swagger-core | the default `Codecs`: Jackson reads bodies, swagger-core describes types |
| `pelican-kotlinx` | core, kotlinx.serialization | the alternative `Codecs` |
| `pelican-pekko` | core | descriptions → Pekko HTTP `Route` |
| `pelican-pekko-docs` | pekko, openapi | serves the document and Swagger UI over HTTP |
| `pelican-http4k` | core, http4k-core | descriptions → an http4k `HttpHandler`, plus a server that streams |
| `pelican-http4k-docs` | http4k, openapi | the same two pages, on http4k |
| `pelican-ktor` | core, ktor-server-core, ktor-server-cio | descriptions → Ktor routes, with `suspend` handlers and `Flow` streams |
| `pelican-ktor-docs` | ktor, openapi | the same two pages, on Ktor |
| `pelican-test` | **core** | descriptions → a typed client and assertions. Backend-agnostic; no matcher library. |
| `pelican-test-pekko` | test, pekko | the in-memory transport, on Pekko, and `PelicanServer.client()` |
| `pelican-test-http4k` | test, http4k | the in-memory transport, on http4k |
| `example` | core, openapi, jackson, all three backends | the orders, bookmarks, greetings and secured services |

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

## Choosing a JSON library

Descriptions carry a `KType` and nothing else — no serializer, no mapper. The
codec is resolved when the `Api` is assembled, which is why switching JSON
libraries is one line in one file and touches no endpoint:

```kotlin
Api(routes, codecs = JacksonCodecs)              // or KotlinxCodecs
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

That the two implementations agree is a test, not a claim: `CodecAgreementTest`
generates one document through each and compares them, over models covering
defaults, nullability, enums, maps, nesting and recursion. It also round-trips a
value encoded by one codec through the other.

Pass your own mapper or `Json` when the defaults do not fit:

```kotlin
Api(routes, codecs = JacksonCodecs(myObjectMapper))
Api(routes, codecs = KotlinxCodecs(myJson))
```

## Getting the OpenAPI docs

Three ways, all reading the same descriptions.

**At build time**, no server, no request:

```bash
./gradlew :example:generateOpenApi     # -> example/build/openapi.json
```

```kotlin
// the whole task body
fun main(args: Array<String>) {
    File(args[0]).writeText(ordersSpec().openApiJson())
}
```

**From descriptions, in code** — this needs only `pelican-core` and
`pelican-openapi`:

```kotlin
val spec = ApiSpec(
    endpoints = listOf(getUser, streamOrders, placeOrder),
    schemas = JacksonCodecs,
    title = "Orders",
)
spec.openApiJson()      // String
spec.openApi()          // JsonObj — core's own tree, if you want to post-process it
```

**Served by the running app** — opt in with `pelican-pekko-docs`:

```kotlin
import dev.pelican.pekko.docs.Docs
import dev.pelican.pekko.docs.startWithDocs

ordersApi().start(port = 8080)              // endpoints only; no OpenAPI code on the classpath
ordersApi().startWithDocs(port = 8080)      // plus /openapi.json and /docs
ordersApi().startWithDocs(port = 8080, docs = Docs(openApiPath = "/v1/openapi.json", docsPath = "/api-docs"))
```

Set either path to `null` to turn that page off. `docsRoutes(docs)` hands back
the routes on their own if the service already has a route to concat with.

### Moving from 3.0.3 to 3.1.0

The emitter used to write `"openapi": "3.0.3"`. It now writes `"openapi":
"3.1.0"`, whose schema dialect is JSON Schema 2020-12 rather than the modified
subset 3.0 defined for itself. Nothing about how you describe an endpoint
changed — this is entirely about what comes out the other end.

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

`webhooks` and the top-level `$self` are 3.1 features and are not emitted.
There is no endpoint description that means "webhook", so there would be
nothing to derive one from.

**There is no way to ask for 3.0 output, and that is deliberate.** A version
argument would have to reach the schema sources, because nullability is spelled
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
openapi-generator all read 3.1.

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

val health = endpoint(noInputs) {
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
ERROR dev.pelican.pekko - Unhandled failure in POST /reports [ref f3ef2bdef43b]
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
holds it whole.

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

Past six inputs there is no overload, and tuples have stopped paying for
themselves anyway. Drop to the lens style — `endpoint { }` with
`query(...)`/`header(...)`, handler receives `Params`, read by key. The trade is that reading an undeclared key throws at
request time instead of failing to compile.

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
strict read like any other.

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
tells Swagger UI's file picker what to offer.

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

### What it will not do

`UploadedFile.stream()` is the request's own body, so nothing here holds an
upload. Two consequences follow, and both are enforced rather than hoped for:

- **The file part has to be the last part on the wire.** Reading stops there, so
  a text part sent after it has not been seen and never will be. A text part
  still missing when the file arrives is a 400 that says exactly this. An HTML
  form satisfies the rule by putting its `<input type="file">` last, and both
  clients here write text parts first whatever order they were declared in.
- **One file part per endpoint.** A second could only be reached by buffering
  the first, so declaring two is a startup failure naming the reason rather
  than a description no handler could ever be given. Two uploads want two
  requests, or a `rawBody()` you parse yourself.

The size limit works the way it does for `rawBody()`: the file part is exempt,
because nothing holds it whole, and the text parts are bounded in total by
`maxBodyBytes`. An upload larger than the limit is served; a *field* larger than
it is a 413.

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
}                                  // Endpoint<Long, Fallible<ApiError, User>>

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
  e: Return type mismatch: expected 'Fallible<ApiError, User>', actual 'User'
```

With several payload types the error parameter infers to their common
supertype, so a sealed hierarchy of problems makes the handler's `when`
exhaustive as well.

The binders are named apart from the total ones — `handledOrFail`,
`handledByOrFail`, `streamedOrFail`, `streamedByOrFail` — rather than
overloaded, because a lambda's return type is inferred *after* overload
resolution, and `(I) -> T` cannot be told apart from `(I) -> Outcome<E, T>` at
the call site.

Streaming works the same way: `ndjson<Order>() orFail noSuchUser` is a
`Fallible<ApiError, StreamOf<Order>>`, which is a failure decided before the
first element rather than mid-stream.

Tests read them back typed, through the same descriptions:

```kotlin
when (val result = app.outcome(getBookmark, 9_999L)) {
    is Outcome.Ok  -> error("expected a failure, got ${result.value}")
    is Outcome.Err -> result.error shouldBe NoSuchBookmark(9_999L, "No bookmark 9999")
}
```

What this does **not** cover, honestly:

- **Throwing still works, and is still unchecked.** `notFound(...)` and any
  other escaping exception become an `ApiError`, as before. `orFail` is opt-in
  per endpoint; nothing forces an endpoint to declare its failures.
- **A failure declared elsewhere with the same payload type still type-checks.** Two
  endpoints declaring `ApiError` can each hand out the other's value. The route
  checks it at response time and answers 500 rather than sending an
  undocumented status.
- **The success status is not in the type.** `json<Order>(status = 201)` and
  `json<Order>()` are the same type to a handler.

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
- A multipart body is neither: its text parts are read as they arrive and its
  file part is handed over unread, which is what makes an upload larger than
  `maxBodyBytes` something the service can serve.

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
them, so a green test also proves the codec round-trips.

A body that is not JSON is built from the description too. A cookie parameter
becomes a `Cookie` header, a form body is encoded against its published schema,
and a file part is the same `UploadedFile` the handler receives — so a test
writes what a caller would write and nothing has to know the wire format:

```kotlin
app.call(signIn, SignIn("ada", remember = true, visits = 3))

app.call(importOrders, In2("March", UploadedFile("orders.csv", "text/csv", stream)))
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
deployed service.

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

## Run it

```bash
./gradlew build                     # 572 tests across all modules
./gradlew :example:run              # server on :8080, on Pekko
./gradlew :example:runHttp4k        # the same service on :8080, on http4k
./gradlew :example:runBackends      # the small example on all three backends at once
./gradlew :example:generateOpenApi  # spec, no server
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
- **More than one file part on a multipart endpoint, or one that is not last.**
  The file is handed over as a live stream, so reading stops at it: a second
  file part could only be reached by buffering the first, and a text part sent
  after the file is never seen. Both are refused out loud — the first when the
  endpoint is built, the second as a 400 naming the part it wanted. See
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
- **OpenAPI 3.0.** The emitter writes 3.1.0 and nothing else. The reasoning,
  and what to do if your tooling only reads 3.0, are under
  [Moving from 3.0.3 to 3.1.0](#moving-from-303-to-310).
- **More than six typed inputs.** `endpoint(a..f)` is the largest overload;
  past that the lens form takes the whole `Params`.
- **A fourth server backend.** `pelican-ktor` was the third, and cost what
  `pelican-http4k` cost: the binders above, a request-to-`Params` step and a
  response writer, in about 500 lines including the comments.
- **A Ktor wiring of the *orders* example.** The small `example/backends/`
  service runs on all three; the larger orders service is bound on Pekko and
  http4k only, and `ClientContractTest` runs against those two.
- **Content negotiation** — one output media type per endpoint.
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
- **`oneOf` for sealed hierarchies** — they emit a bare `object`. swagger-core
  does understand `@JsonTypeInfo`, so the Jackson side may do better than this
  already; it is untested, so it is listed as missing.
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

Kotlin 2.2.20 · Pekko 1.6.0 · Pekko HTTP 1.4.0 · http4k 6.22.0.0 · Ktor 3.5.2 ·
Jackson 2.22.2 · swagger-core 2.2.54 · kotlinx.serialization 1.9.0 ·
slf4j-api 2.0.17 · JDK 21 · Gradle 8.14.3

http4k is pinned to the last release compiled against Kotlin 2.2.20; 6.23 and
later ship stdlib metadata this compiler will not read. Bump Kotlin and http4k
together.
