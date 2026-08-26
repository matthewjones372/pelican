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

### Changed

- **Ktor dispatches through `RouteIndex`**, as the other two backends already
  did, rather than installing one Ktor route per endpoint. Decoding, precedence
  and the trailing-slash rule are one answer on all three. Where a request lands
  is still Ktor's: a constant segment scores above the tailcard these routes
  install, so routes written by hand beside them keep the paths they describe.

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
