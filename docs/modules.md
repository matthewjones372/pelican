# Modules

Linked from the [README](../README.md). What each module is for, and what it
depends on — the list to read when deciding which ones a build actually needs.

Eighteen library modules and a Gradle plugin; a typical build takes four or
five. The layering is enforced by tests rather than convention.

1.0 ships one server backend, one JSON library and one client transport. The
http4k and Ktor backends, the JDK, OkHttp and Ktor client transports
(`pelican-client-java`, `pelican-client-okhttp`, `pelican-client-ktor`),
`pelican-jsoniter` and `pelican-kotlinx`
are complete and green on the [`multi-backend`](https://github.com/matthewjones372/pelican/tree/multi-backend)
branch and return after 1.0; the table below is what main ships today.

| Module | Depends on | Contains |
|---|---|---|
| `pelican-core` | **nothing** | endpoint descriptions, plain codecs, a minimal JSON tree, the client SPI and its in-memory implementation |
| `pelican-jackson` | core + Jackson | your `Codecs` |
| `pelican-arrow` | core + arrow-core | Arrow's `Either` into Pelican's `Outcome` and back |
| `pelican-pekko` | core + Pekko HTTP | descriptions → that server's routes |
| `pelican-pekko-docs` | pekko, openapi | serves the document and Swagger UI |
| `pelican-pekko-mcp` | pekko, mcp-server | serves the tools over Streamable HTTP, on `/mcp` |
| `pelican-metrics` | core + micrometer-core | one filter; meters tagged from the descriptions |
| `pelican-metrics-otel` | core + opentelemetry-api | one filter; spans and a duration histogram, from the descriptions |
| `pelican-openapi` | core | descriptions → OpenAPI 3.1.0 or 3.2.0 |
| `pelican-schema` | core | one type → a JSON Schema 2020-12 document that resolves on its own |
| `pelican-mcp` | core + schema | descriptions → MCP tool descriptions, and a dispatch that runs them. Values only, so deriving a tool list needs no server |
| `pelican-mcp-server` | core + mcp | those tools **served**: JSON-RPC 2.0 over stdio, and the request/response half of Streamable HTTP. No MCP SDK |
| `pelican-codegen` | core | descriptions → a Kotlin client, as source |
| `pelican-client-pekko` | core + pekko-http | where a generated client's requests go, over Pekko HTTP's client |
| `pelican-import` | codegen + snakeyaml-engine | an OpenAPI document → descriptions, as source |
| `pelican-gradle-plugin` | **nothing** | `io.github.matthewjones372.pelican`: every generator, as Gradle tasks |
| `pelican-test` | **core** | descriptions → a typed client for tests, on any backend |
| `pelican-test-golden` | test + openapi | per-endpoint goldens; fails on a breaking change |
| `pelican-test-pekko` | test + pekko | the typed test client's in-memory transport |

Every one of those dependency claims is a test:

- `pelican-core` asserts its runtime classpath holds nothing but the Kotlin
  standard library.
- `pelican-openapi` asserts Pekko is absent, so documentation can be generated
  in a build task with no server present.
- `pelican-pekko` asserts the document generator is absent, so a service that
  serves only endpoints never ships it.
- `pelican-schema` asserts it carries no document generator and no codec — the
  codec modules are test-scoped, which is where the claim that spans them is
  made.
- `pelican-mcp` asserts it is core plus that schema pass and no MCP SDK;
  deriving tools is a separate job from serving them. `pelican-mcp-server`
  asserts the same of the half that does serve them — the protocol is JSON-RPC
  over lines of text, and taking the official Kotlin SDK would put a Ktor
  server behind `mcpServe` on a service running Pekko.
- `pelican-metrics` asserts it is core plus a meter API and no server library;
  `pelican-metrics-otel` asserts the mirror image — core plus the OpenTelemetry
  API, and no Micrometer. That separation is the whole reason the two telemetry
  vendors are two modules.
- `pelican-client-pekko` asserts it carries Pekko HTTP's own client and no
  second stack — and not the matching *interpreter* either, since making calls
  and serving routes are separate decisions.
- `pelican-arrow` asserts it is core plus `arrow-core` and nothing else.
- `pelican-test` asserts it drags in no server library and no matcher library.

The full breakdown is in [docs/reference.md](reference.md#modules).

`pelican-core` publishes two packages. `io.github.matthewjones372.pelican` is
the DSL a service is written in. `io.github.matthewjones372.pelican.spi` is what
an interpreter or a transport calls to turn those descriptions into a running
server — `handlerFor`, `routeIndex`, `requestBodyCodec`, `readStrictBody`,
`acceptable`, `successNamedBy`, `renderError` and a handful more. None of them
can be `internal`, because a backend is a separate module by design, so the
package is the fence instead: both are covered by the same binary-compatibility
gate, and neither is more or less supported than the other — but a service that
finds itself importing `spi` is reaching for plumbing rather than for the DSL.
`SpiPackageTest` in core is what holds the two apart.
