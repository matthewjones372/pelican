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

### Added

- **`pelican-metrics`.** `Endpoint.statusFor` resolves the status once in core
  and `afterStatus` hands it to a filter, so a counter and a timer come out
  tagged from the description rather than from a string passed by hand.
  OpenTelemetry spans alongside the meters.
- **A client transport SPI.** `ClientTransport` in core, with
  `pelican-client-java` over the JDK's own `HttpClient` and
  `pelican-client-pekko` beside it. A generated client also offers a `suspend`
  call surface and a retry policy.
- **OpenAPI 3.2.0**, written as well as 3.1.0.
- **Content negotiation on request bodies**, SSE keep-alives, and reserved
  response headers.
- **`pelican-test-golden`** fails when a change breaks an existing caller,
  rather than only recording that the contract moved.
- **`docs/choosing.md`** — where http4k's contracts, Ktor's plugins, Spring,
  Micronaut, Quarkus, tapir or a hand-written document are the better answer.
- **`docs/roadmap.md`** — what is not built yet and the argument for the order.
- **`docs/cookbook.md`** — complete recipes rather than fragments.
- **`CHANGELOG.md`**, **`CLAUDE.md`**, **`TODO.md`** and **`llms.txt`**.

### Changed

- **The javadoc jar holds the KDoc.** It was empty, which left javadoc.io blank
  and the KDoc unreadable without a clone. Dokka renders it.
- `AGENTS.md` carries the layering, the testing order and the build gates, not
  only the comment rules.
- The README says what Pelican is before it says how it works.
- Renovate opens the dependency pull requests.

### Fixed

- **Generation under a parallel build.** The generator templates are read
  through a connection of their own rather than a stream the worker's
  classloader owns and closes, which failed `generate<Name>Endpoints` with
  `java.io.IOException: Stream closed` whenever another task finished first.
- jsoniter binds a value class as the value inside it, and honours jsoniter's
  own settings and config contract.
- The transport is named where two adapters are present.

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
