# Changelog

Notable changes, newest first, in the format of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

**Before 1.0, expect breaking changes between minor versions.** Pin an exact
version. The golden-file tests in `pelican-test-golden` make a break in *your*
contract loud; they say nothing about breaks in Pelican's own, and this file is
the only place those are recorded.

A version is cut by tagging — `git tag v0.2.0 && git push --tags`. The build
reads the nearest `v` tag, so an untagged commit is a `-SNAPSHOT` of the next
one.

## [Unreleased]

### Changed — read this one

- **`spi.renderError` takes the `Api`, not a boolean.**
  `renderError(t, api.exposeInternalErrors)` becomes
  `renderError(t, api, endpoint)`, and the `RenderedError` it returns carries
  the body already written, under `body.bytes` and `body.mediaType`. There is
  no default for `api`: a call site that forgot one would answer in an envelope
  nobody configured, silently, and only for the refusals that reach it — which
  is how three of them came to bypass the setting during this change. An
  interpreter should write `rendered.body` and take only the status from
  `rendered.error`; encoding `error` itself is a fourth dialect.
- **The interpreter SPI lives in `io.github.matthewjones372.pelican.spi`.**
  `handlerFor`, `routeIndex`/`RouteIndex`, `renderError`/`RenderedError`,
  `readStrictBody`, `requestBodyCodec`/`RequestBodyCodecs`, `successNamedBy`,
  `failureNamedBy`, `decodeList`, `acceptable`, `declaredInputCount` and
  `CorsHeaders` moved out of the root package, with their signatures unchanged
  and no forwarders left behind. Nothing in the DSL moved. A service written
  against Pelican compiles as it was; a backend or transport written against
  core adds one import per file. The fence goes up before 1.0 because after it
  the same move would break every backend at once.
- **An `Api` is built by `api(routes, codecs = ...) { }`**, and its constructor
  is internal. A fifteen-parameter constructor with defaults freezes twice over
  — the descriptor and the synthetic constructor carrying the defaults both
  change when a parameter is added — so after 1.0 every new server setting
  would break every caller compiled against the last release. Settings that
  were named arguments are assignments inside the block, and
  `filters = listOf(a, b)` is `filter(a)` and `filter(b)`. Every call site is a
  compile error, not a silent change of meaning.
- **The same for the other settings bundles**: `retryPolicy { }`, `docs { }`,
  `docsOAuth(clientId) { }`, `mcpOptions { }` and
  `importOptions(packageName, name) { }` replace their constructors.
  `ClientRequest` keeps a constructor — a client builds one per call — and
  gains `withTimeout(...)` and `withHeader(...)` for what is no longer a
  parameter.
- **`start` is `start(port, host = "127.0.0.1", ...)` on all three backends.**
  Pekko read `start(host, port)` and Ktor read `start(port, host)`, so the same
  two positional arguments meant opposite things; http4k had no host at all.
  Port is first everywhere now, `host` is second on `start` and
  `startWithDocs`, and http4k's `config` moves one place right —
  `StreamingSunHttp(port, host, ...)` likewise. A call using named arguments is
  unaffected; a positional `start("0.0.0.0", 8080)` on Pekko no longer
  compiles.
- **Ktor binds loopback by default, where it bound every interface.** A bare
  `start()` was `0.0.0.0` on Ktor and `127.0.0.1` on Pekko, which is a
  deployment decision inherited from an engine rather than made by a service.
  This one is silent — nothing fails to compile — so a Ktor service that
  *meant* to be reachable off the machine now says `start(port, "0.0.0.0")`.
  http4k, which had no host parameter and bound every interface through the
  JDK's server, changes the same way. `PelicanServer.host` reports what was
  bound.
- **One server handle on all three backends.** Pekko's `PelicanServer` gains
  `AutoCloseable`, `block()` and a `stop()` returning nothing; the
  `CompletionStage` it used to return is now `stopAsync()`, which http4k and
  Ktor gain too. `server.stop().toCompletableFuture().join()` becomes
  `server.stop()`. `block()` means the same thing everywhere — parked until
  `stop()` — which on http4k is a change: `Http4kServer.block()` is
  `Thread.currentThread().join()`, released by nothing, and Pelican no longer
  delegates to it.
- **`Ansi` is internal**, and the `DefaultImpls` classes are gone from the
  published surface: the modules compile with `-jvm-default=no-compatibility`,
  and the interfaces that had them already emitted real JVM default methods.
  Kotlin callers see no difference; a Java class that implemented `JsonValue`,
  `PlainCodec` or `SecurityScheme` by calling `DefaultImpls` is the one break.

### Fixed

- **A path segment is decoded as a path, not as a form.** A `+` in a captured
  segment arrived as a space, because captures were decoded with `URLDecoder`;
  `/tags/c++` now reaches a handler as `c++`. Literals are matched decoded too,
  so `/users/%61dmin` finds the route declared `/users/admin`. A malformed
  escape is a 400 naming the segment rather than a 500 out of an interpreter's
  last-resort catch.
- **An encoded slash stays inside the segment that carried it.** The path is
  split on `/` before anything is decoded, so `/items/a%2Fb` is one captured
  value and never two segments and a different route.
- **`?q` on http4k is a present, empty value**, as it already was on Pekko and
  Ktor, rather than indistinguishable from a parameter nobody sent.
- **A kotlinx-backed server reads what its own document says it accepts.**
  `defaultJson()` now sets `explicitNulls = false`. Without it, a nullable
  property with no default was `required` to the decoder and optional in the
  published schema, so a caller following the document got a 400 from
  `KotlinxCodecs` and a 200 from the other two. The agreement harness gained the
  decode direction that catches the whole class: for every shape it covers, the
  smallest payload each codec's own schema accepts must decode through that
  codec.
- **A nullable scalar in a form body keeps its type.** `FormShape` read only the
  string spelling of `type`, so the `["integer","null"]` OpenAPI 3.1 publishes
  for an `Int?` fell through to a string and the value reached the codec quoted
  — a 400 on jsoniter and a silent coercion on the other two.
- **Documentation that promised more than the library does.** The reference said
  a response an endpoint never declared "does not compile"; both halves are
  false, and what actually happens — `UndeclaredResponse` where the response is
  written, a 500 with a reference and the whole story in the log — is now what it
  says. Two of the compiler lines it and the README quote were invented: a
  handler is `Params.(String) -> User`, never `Function1<String, User>`, and a
  Pekko `Source` built from a Java constant arrives with platform-type marks.
  Every quoted line is now the compiler's own, asserted verbatim. The Kover
  floor reads 90 in `AGENTS.md`, as the build has, and `FunctionalStyleTest`
  judges eighteen modules rather than the eleven the reference claimed.

### Added

- **Refusals in the caller's dialect.** `refusals(ProblemDetails)` inside
  `api { }` writes the built-in refusals — a 400 nothing could decode, a 406, a
  413, a 500 nobody described — as RFC 9457 `application/problem+json` instead
  of `{"status":…,"error":…,"detail":…}`. The default, `ApiErrorEnvelope`, is
  the envelope that was always there, byte for byte. A `RefusalRenderer` sees
  the *classified* refusal — status, sentence, detail, reference, and the path
  template where a route matched — and never the request or the throwable, so
  the 500 path cannot leak internals by construction and `instance` cannot echo
  anything a caller sent. One renderer serves all three backends and the
  in-memory transport; `RefusalsAcrossBackendsTest` and `ThrowingHandlerTest`
  run once per backend and once per shipped renderer. Declared failures are
  untouched — `errorJson<E>` already carries whatever type was promised — and
  an MCP tool call is unaffected, because JSON-RPC's envelope is the protocol's
  and not the service's.
- **An event stream that says where a caller left off.**
  `sse<T>(id = { it.sequence.toString() }, retry = 15.seconds)` puts an `id:`
  on every frame and opens the stream with a `retry:` directive of its own —
  once, ahead of the first event, because a frame carrying no `data:`
  dispatches nothing. `id` is a projection of the event rather than a counter
  the interpreter keeps, so there is one source of truth for the resume point.
  Both are optional and additive: an `sse<T>()` that declares neither writes
  the frames it always wrote, byte for byte, on all three backends. Under
  OpenAPI 3.2 the described event gains an `id` property, required exactly when
  the output sends one; `retry` is said in the response's description, being a
  directive about the stream rather than a field of any event. Neither travels
  through the OpenAPI importer — an id extractor is a function of the event
  value and no document holds a function — so an imported event stream declares
  its payload and the service adds the frame fields back.
- **`lastEventId()` on `Params`**, the `Last-Event-ID` a reconnecting caller
  sent, and null on a fresh connect. An extension rather than a declared input:
  resume is optional by nature and reading it does not change an endpoint's
  input arity, so a handler that ignores it is unchanged. The header is read
  only where the output declares an event stream (`Endpoint.resumable`, decided
  in core), and the value rides on `Params` rather than through an attribute
  nothing stops a filter forging. What is done with it stays the service's:
  Pelican frames and delivers, and retention is not its business. Under
  OpenAPI 3.2 the described event's `id` carries the contract in words.
- **Clients that can pick a stream up again.** A generated method answering
  `text/event-stream` takes a trailing `lastEventId: String? = null` and sends
  it as the header, and the `Streamed<T>` it returns reports `lastEventId` —
  the id of the last event handed over, so it is read after the sequence rather
  than off the response. `pelican-test`'s `collect(...)` takes the same
  argument. The parameter is generated for every event stream rather than only
  those declaring an extractor, so a client generated from a description and
  one generated from the document it publishes stay identical. Reconnecting
  itself is deliberately not done for you: no loop, no backoff, no decision
  about how much of a gap is worth replaying — that is a policy, like retrying,
  and what is here is the seam for writing it.
- **One response, several renderings**, chosen by the caller's `Accept`:
  `negotiated(json<Report>(200), media<Report>("text/csv", 200))` is one
  declared response written two ways. The handler goes on returning a `Report`;
  which rendering goes out is the interpreter's, from the RFC 9110 scoring the
  406 already used, and all three backends read it through one function in
  core. No `Accept`, or `*/*`, takes the first alternative in declaration
  order. Nothing acceptable is the 406 it always was. The document says one
  status with one entry per media type — the shape a negotiated request body
  already published — the importer reads that shape back as the same
  `negotiated(...)`, and the generated client grows one method per rendering
  (`exportReportAsJson`, `exportReportAsCsv`), each sending the `Accept` that
  asks for the one it decodes.
- **`media<T>("text/csv")`**, a response written as a media type that is not
  JSON, and **`CodecFactory.codec(type, mediaType)`**, which is where the
  writer for it comes from. The default answers `application/json` with the
  codec it already had and refuses anything else by name, so a media type
  nothing can write is a startup failure rather than a 500. Implementing it is
  one override on the `Codecs` an `Api` is given; `example/secured` has one.
- **`pelican-mcp-server`, and the tools served.** `pelican-mcp` derived the
  tools and ran a call; nothing carried either to a client, and the docs said
  so. This module speaks the protocol — JSON-RPC 2.0, revision `2025-11-25`:
  `initialize`, `notifications/initialized`, `tools/list`, `tools/call`,
  `ping` — over stdio with `mcpServe(api)`, and `pelican-pekko-mcp`,
  `pelican-http4k-mcp` and `pelican-ktor-mcp` mount the request/response half
  of Streamable HTTP on `/mcp` beside the endpoints, exactly as the `-docs`
  modules mount the document. Both drive the same `McpDispatch`, so a tool call
  is the execution path an HTTP request takes: the same codecs, refinements,
  filters and refusals, into the handler the route already has.
  **No MCP SDK**, and the dependency test says so: the official Kotlin SDK's
  server half is Ktor's — its transports are `Route` extensions, and
  `kotlin-sdk-server` compiles against `ktor-server-core` even for stdio — so
  adopting it would put a Ktor server behind `mcpServe` on a service running
  Pekko, and leave the HTTP half mountable on one backend of three. The
  revision is pinned to that SDK's `LATEST_PROTOCOL_VERSION`. Deferred and said
  out loud: the server-initiated event stream and the session id that goes with
  it — a GET is answered `405` — and resources, prompts and sampling.
  `example.mcp` is the Orders service with both halves on one port.
- **A Ktor-bound handler runs when it is invoked without a request.** Ktor's
  binders launch the handler as a child coroutine of the call, so anything
  invoking a binding without one — `mcpDispatch` running the same
  `ServerEndpoint` as a tool — cast `null` to an `ApplicationCall` and answered
  an internal error. Where there is no call it runs on the default dispatcher
  instead; nothing is cancelling that work and no socket is waiting on it.
- **`pelican-client-okhttp`, the fourth transport — and the one Android can
  run.** There is no `java.net.http` on Android, so a generated client could
  only get there through `KtorHttpTransport(HttpClient(OkHttp))`, which drags
  the Ktor client machinery into an app that already ships an HTTP stack.
  `OkHttpTransport(okHttpClient)` takes the client the application already
  built, with its interceptors, its cache and its connection pool. The module
  is plain JVM — core and `okhttp`, no Android plugin and no AndroidX — and
  `DependenciesTest` asserts that list rather than promising it: running on
  Android is a consequence of depending on nothing that does not.
  **OkHttp 4.x is the floor**; Gradle resolves upwards, so a build on OkHttp 5
  keeps it, and a fleet pinned to OkHttp 3 keeps the Ktor-engine route, which
  stays documented. The per-request deadline bounds the arrival of the response
  head, as it does on the JDK and Pekko adapters — deliberately *not* OkHttp's
  `callTimeout`, which is the whole exchange and would end every `sse` response
  that outlived the deadline its client was built with. It raises the
  `InterruptedIOException` OkHttp itself raises on a call timeout, so a caller
  catching a timeout by type catches it either way, and `RetryPolicy` treats it
  as the `IOException` it is.
- **Four claims the parity suite made and never asked.** A handler that throws
  answers the same `ApiError` on all three backends — a 500 whose detail is a
  reference to grep the log for, nothing of the throwable, and the header a
  filter stamped still on it. A declared HEAD endpoint answers on all three; a
  HEAD aimed at a GET endpoint is pinned per backend as the difference it is
  (Pekko 404, http4k 405, Ktor 404). A chunked body over the limit is refused
  on http4k and Ktor, as it already was on Pekko. And a list header split over
  two field lines decodes as two values, not one.
- **A consumer that reads one frame and hangs up closes the handler's stream**,
  on all three backends, within a bounded time — asserted through each one's
  own signal: `watchTermination` on a `Source`, `onCompletion` on a `Flow`, and
  the `finally` of a `Sequence` whose walking thread is interrupted. It fails
  against a `StreamingSunHttp` that does not close the body it was writing,
  which is the leak it exists to stop.
- **A definition for `handledNow`**, which had none: handled in place, on the
  request, where *in place* is a thread on Pekko and http4k and a coroutine on
  Ktor. No code changes — `docs/reference.md` states it, and
  `BinderNameParityTest` names the binders each backend publishes so the
  difference stays documented rather than discovered.
- **Three refusals where something was published as what it is not.** Two types
  wanting one component name are refused where the schema is built, naming both
  — all three sources named a component after the simple name and let the second
  take the first's schema. A `Json` setting the schema derivation cannot see is
  refused at `KotlinxCodecs(...)`, naming the setting: `classDiscriminator`,
  `ignoreUnknownKeys`, `encodeDefaults` and `explicitNulls` are what it reads.
  And a success carrying a payload its response never declared is now an
  `UndeclaredResponse` naming both types, the check a failure already had.
- **Every compiler line the manual quotes is now a fixture.** `DoesNotCompileTest`
  compiles the wrong input type, the wrong stream element, a value where a
  stream was declared, a bare value where a failure was declared, a failure from
  outside the declared type, and a seventh input with no overload to land on —
  asserting the compiler's line, not a paraphrase of it. Two more fixtures pin
  the holes the manual admits *open*, so a page describing a trade-off fails if
  the trade-off quietly closes: a failure of the same payload type compiles
  whichever endpoint declared it, and a lens handler reading a key nothing
  declared compiles and throws on the request instead.
- **Three truths the manual left unwritten.** A declared failure is always JSON —
  `errorJson` is the only spelling, on all three backends and in the document.
  A client that disconnects cancels a Ktor handler and does not cancel a Pekko
  or http4k one, which both run to completion and answer a connection that is
  gone; a *stream* closes on all three. And the refusal of two responses under
  one status says why in terms that leave room for several renderings of one
  response, which would be that one response declaring both media types.
- **`InMemoryClientTransport(api)`**, in `pelican-core`: a `ClientTransport`
  that answers a generated client's requests by calling the `Api` in memory —
  the same routing, decoding, filters, handlers and response building a bound
  server runs, with no port and no mock. A `bytes(...)` request body and a
  streamed response that is not a `Sequence` are refused by name, because the
  values behind those belong to a backend rather than to core.

### Changed

- **Ktor dispatches through `RouteIndex`**, as the other two backends already
  did, rather than installing one Ktor route per endpoint. Decoding, precedence
  and the trailing-slash rule are one answer on all three. Where a request lands
  is still Ktor's: a constant segment scores above the tailcard these routes
  install, so routes written by hand beside them keep the paths they describe.
- **All three codecs now leave a null property out.** The flag that lets
  kotlinx.serialization read an absent nullable property governs writing too, so
  `defaultMapper()` moved to Jackson's `NON_NULL` and jsoniter's encoder skips a
  null property, rather than one library writing `"detail": null` and another
  omitting it. The schema already marks a nullable property optional and all
  three read an absent one back as null, so this is one spelling of a fact that
  had two. A null *inside* a list or a map is unaffected — it is a value there,
  and all three still write it. A response body carrying nullable fields is
  shorter and no longer carries their names; pass your own mapper or `Json` to
  write them back.
- **A streamed call no longer inherits the client's deadline.** `ndjson`, `sse`,
  `jsonArray` and `bytes` calls are built with no timeout, because the three
  transports do not bound the same thing: Ktor's request timeout ends the whole
  exchange and the other two are done when the response head arrives, so one
  client's SSE subscription died at 30 seconds on one transport and ran on the
  other two. Everything read whole is bounded as before. Regenerate to pick it
  up. `docs/generated-client.md` now has the per-transport table, and the
  bearer-token recipe that was missing beside it.
- **A generated client refuses a body it cannot read, naming the call.** A
  declared status arriving with something the codec cannot decode — a proxy's
  HTML 404, a gateway's plain-text 502 — used to let a bare Jackson or kotlinx
  exception escape with no status, path or body attached. It is now an
  `ApiCallFailed` like any undeclared status, carrying the status, the method,
  the path template, the body capped at 8 KiB with a marker where it was cut,
  and the codec's failure as its `cause`. Regenerate to pick it up.

## [0.2.0]

Ninety-seven commits since 0.1.0. One breaking change, named below.

### Changed — read this one

- **`endpoint { }` now means no inputs**, not the lens form. `endpoint(a)` is one
  input and `endpoint(a, b)` is two, so no arguments should be none; the lens is
  `endpoint(lensInputs) { }`, which is a name rather than an absence, and
  `noInputs` is gone. Every affected call site is a compile error — a handler's
  parameter moves from `Params` to `Unit` — so nothing silently keeps working
  and means something else.
- **`Fallible<E, T>` is gone**, replaced by `Outcome<E, T>` in the same position.
  It was a phantom with a private constructor whose only appearance was in the
  compiler error you got for using the wrong binder, where it named a type you
  had never written. That error now reads `expected 'Outcome<Problem, Item>'`.

### Added

- **Routing that does not depend on how many endpoints you have.** Descriptions
  go into a trie once instead of a list each router walks in turn. At two
  hundred endpoints a request went from about 150µs to 223ns on http4k and 645ns
  on Pekko — and both are now faster than the same routes registered by hand at
  *every* size, including one. See [what it costs](docs/what-it-costs.md).
- **`pelican-mcp`.** The endpoints as MCP tool descriptions and a dispatch that
  runs them through the handler the route already has.
- **`pelican-schema`.** A derived JSON Schema that resolves on its own, for
  anything holding one without your OpenAPI document around it.
- **`pelican-metrics` and `pelican-metrics-otel`.** Micrometer meters and
  OpenTelemetry spans, dimensioned from the descriptions rather than by hand.
- **A client transport SPI** — `ClientTransport` in core, with adapters over the
  JDK's `HttpClient`, Pekko's and Ktor's — plus a `suspend` call surface and a
  retry policy for generated clients.
- **OpenAPI 3.2.0**, written as well as 3.1.0.
- **`bytesOrFail`**, so a byte stream that may 404 before its first byte is
  bindable and not merely describable.
- **A `description` on a declared success**, so two of them stop both saying
  "Success.".
- **`UndeclaredResponse`**, so a handler returning a response its endpoint never
  declared is distinguishable at `onServerError` from a broken codec.

### Fixed

- **`maxBodyBytes` counted characters on http4k and Ktor**, so an 8MB limit
  admitted roughly 24MB of CJK — and the body was already whole in memory before
  there was a length to check. Both now count bytes as they read.
- **Jackson dropped the discriminator on a collection of a union**, so
  `json<List<PaymentMethod>>()` went out with no `kind` on any member and could
  not be read back.
- **kotlinx described a value class as an object** while writing it as a number.
- **A slow request body** is a 408 on Pekko rather than an undescribed 500.
- **Two parameters under one name** and **two endpoints sharing an
  `operationId`** are refused where the endpoint is described, instead of
  producing an invalid document and a client that will not compile.
- **`ApiSpec` silently dropped one of two endpoints on a route.** `Api` refused
  it; a documentation-only build did not.
- **Parameter defaults and `emits(...)` headers on failures** now reach the
  document, which the server was already applying and sending.

### Known

- **A handler can return a failure its endpoint never declared.** `E` is pinned
  to the failure's *payload type*, not to the `ErrorOutput` that declared it, so
  any failure carrying the same type fits — and `ApiError` is the payload of
  most of them. It is an `UndeclaredResponse` when the response is written: a 500
  with a reference, the whole story in the log. See
  [declared failures](docs/reference.md#declared-failures).

## [0.1.0] — 2026-08-24

First published release, on Maven Central.

### Added

- **Descriptions as values.** An endpoint is an ordinary Kotlin value; the
  route, the OpenAPI 3.1 document, the test client and the generated client are
  derived from it.
- **Three backends** — `pelican-pekko`, `pelican-http4k`, `pelican-ktor` — one
  import apart, with parity asserted by a suite that runs against all three.
- **Three JSON libraries** — `pelican-jackson`, `pelican-kotlinx`,
  `pelican-jsoniter`.
- **Typechecked endpoints.** `endpoint(a, b, …)` up to six inputs fixes the
  handler's signature; a lens form past that.
- **Declared failures.** `orFail` puts the promised error into the endpoint's
  type, so a handler produces it rather than throwing it.
- **More than one success**, with `handledOneOf`.
- **Streaming**, backend-agnostic in the description: NDJSON, SSE, and a
  chunked JSON array.
- **Refined inputs** that carry their constraint into the document.
- Form bodies, multipart uploads, cookies, response headers, CORS, security
  schemes, webhooks, filters with attributes.
- **`pelican-openapi`**, read back by an independent parser in the build.
- **`pelican-codegen`** — a Kotlin client, as source.
- **`pelican-import`** — an existing OpenAPI document, read back as
  descriptions.
- **`pelican-gradle-plugin`** — every generator as Gradle tasks, with a remote
  reference lock.
- **`pelican-test`** — the descriptions interpreted a third way, as a typed
  client, with `shouldBuild` for pinning the URL a caller holds.

[Unreleased]: https://github.com/matthewjones372/pelican/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/matthewjones372/pelican/releases/tag/v0.1.0
