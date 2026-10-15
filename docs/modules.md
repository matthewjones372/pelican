# Modules

Which modules a build needs, what each one brings with it, and which are in a
release. Coordinates are `io.github.matthewjones372:<module>`.

The current release is 1.0.0. Modules marked **main only** are built and tested
on `main` but are in no release yet; to try them, see
[Jetty, kotlinx and the BOM](../README.md#jetty-kotlinx-and-the-bom-on-main-not-yet-released).

## Choosing modules

Pick the modules you need and nothing else. Every dependency between Pelican
modules is `api`, so each module brings `pelican-core` and whatever Pelican
modules it is built on; you declare the leaves, not the tree.

- **Foundation.** One backend and one codec. The backend brings core. The codec
  is needed as soon as an endpoint has a JSON body or schema: without one, `api`
  uses `NoCodecs`, which fails with "No codecs configured" at the first JSON
  body. No backend depends on a codec or on `pelican-openapi`.
- **Optional companions.** A `-docs` module serves the OpenAPI document, an
  `-mcp` module serves MCP tools, a test module gives you a typed test client.
  Each is a separate dependency, so a service that does not serve docs never
  ships the document generator.
- **Integrations.** Metrics, traces, health checks, OIDC, Arrow, streams: each a
  module of its own that adds one third-party library at most.

### Minimum sets for common setups

| Setup | Modules (1.0.0) |
|---|---|
| Descriptions only, no server | `pelican-core`, plus `pelican-jackson` once a body is JSON |
| The OpenAPI document in a build task | `pelican-openapi` and a codec; no backend |
| A JSON service on Pekko | `pelican-pekko`, `pelican-jackson`, and Pekko itself (below) |
| The same, serving `/openapi.json` and a docs page | add `pelican-pekko-docs` (brings `pelican-openapi`) |
| Tests, in memory, no backend | `testImplementation` `pelican-test`: `Api.inMemory()` runs on core's in-memory transport |
| Tests through the real backend | `testImplementation` `pelican-test-pekko` (brings `pelican-test`) |
| A generated client, for callers | the Gradle plugin at build time; at run time a codec and `pelican-client-pekko` |
| MCP tools over HTTP | add `pelican-pekko-mcp` (brings `pelican-mcp-server`, `pelican-mcp`, `pelican-schema`) |
| MCP tools over stdio | `pelican-mcp-server` and a codec; no backend |

On `main`, swap the Pekko modules for their Jetty counterparts (`pelican-jetty`,
`pelican-jetty-docs`, `pelican-test-jetty`, `pelican-client-jetty`,
`pelican-jetty-mcp`) and drop the Pekko lines.

### A minimal 1.0.0 build

Pelican compiles against Pekko but ships none of it, so the build names the
Scala cross-build it runs (`_2.13` or `_3`, the same on every Pekko line):

```kotlin
// build.gradle.kts
val pelicanVersion = "1.0.0"

dependencies {
    implementation("io.github.matthewjones372:pelican-pekko:$pelicanVersion")
    implementation("io.github.matthewjones372:pelican-jackson:$pelicanVersion")

    implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-stream_2.13")
    implementation("org.apache.pekko:pekko-http_2.13:1.4.1")

    testImplementation("io.github.matthewjones372:pelican-test-pekko:$pelicanVersion")
}
```

An optional module is one more line with the same version:

```kotlin
    implementation("io.github.matthewjones372:pelican-pekko-docs:$pelicanVersion")
    implementation("io.github.matthewjones372:pelican-metrics:$pelicanVersion")
```

## Version alignment and the BOM

Every Pelican module in a build should be the same version. There are two ways
to get that.

**On 1.0.0: one variable.** `pelican-bom` is not in 1.0.0. Write the version
once, as `val pelicanVersion = "1.0.0"` above, and use it on every Pelican
coordinate.

**On `main`: `pelican-bom`** (main only, published as `1.0.1-SNAPSHOT` to
Central's snapshot repository). It is a Gradle `java-platform` with no
dependencies of its own, only a version constraint for each of the 31 library
modules:

```kotlin
dependencies {
    implementation(platform("io.github.matthewjones372:pelican-bom:$pelicanVersion"))
    implementation("io.github.matthewjones372:pelican-jetty")
    implementation("io.github.matthewjones372:pelican-jackson")
    testImplementation("io.github.matthewjones372:pelican-test-jetty")
}
```

The BOM manages versions; it does not add modules. Importing it puts nothing on
the classpath, and you still declare each module you use, without a version.
The platform on `implementation` also governs `testImplementation`, because the
test classpaths extend `implementation`. `platform` recommends versions and lets
conflict resolution pick a higher one; `enforcedPlatform` forces them.

It covers Pelican's library modules only:

- **Not third-party libraries.** Jackson, Pekko, Jetty, Micrometer and the rest
  are not constrained by it. Pekko in particular is still yours to declare.
- **Not the Gradle plugin.** Its version goes in `plugins { }`.

Maven reads the same BOM through `dependencyManagement`:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.matthewjones372</groupId>
      <artifactId>pelican-bom</artifactId>
      <version>${pelican.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## The modules

"Depends on" lists what the module brings. *Provided* means the module
compiles against a library and does not ship it: the service declares the
version it runs.

### Core and documents

| Module | Availability | Depends on | Provides |
|---|---|---|---|
| `pelican-core` | 1.0.0 | `jackson-core` (implementation) only | endpoint descriptions, plain codecs, a minimal JSON tree and the streaming parser under it, the client SPI (`ClientTransport`) and its in-memory implementation |
| `pelican-openapi` | 1.0.0 | core | descriptions to an OpenAPI 3.1.0 or 3.2.0 document. No server, so it runs in a build task |
| `pelican-schema` | 1.0.0 | core | one type to a JSON Schema 2020-12 document that resolves on its own, for consumers outside an OpenAPI document |

You rarely declare `pelican-core`: every other module brings it. Declare it
when a module of yours holds only descriptions, so it does not depend on a
server.

### Codecs

| Module | Availability | Depends on | Provides |
|---|---|---|---|
| `pelican-jackson` | 1.0.0 | core, `jackson-module-kotlin`, `jackson-datatype-jsr310`; `swagger-core` (implementation) | `JacksonCodecs`: bodies, and the schemas the document derives |
| `pelican-kotlinx` | main only | core, `kotlinx-serialization-json` 1.11.0 | `KotlinxCodecs`, for `@Serializable` payloads |

Both write the same schemas for tagged failures and `oneOf`, and both are in
the codec agreement matrix. Choose by the annotations your model already
carries. `pelican-kotlinx` 0.1.0 and 0.2.0 on Central predate 1.0 and do not
match it.

### Pekko HTTP backend

For a service that already runs Pekko. Pekko is *provided* in every module
here: `pekko-actor_2.13` and `pekko-actor_3` hold identical class names under
different Maven coordinates, so shipping one would put both on a Scala 3
service. See [Versions](reference.md#versions).

| Module | Availability | Depends on | Provides |
|---|---|---|---|
| `pelican-pekko` | 1.0.0 | core, `slf4j-api`; Pekko HTTP *provided* | descriptions to Pekko HTTP routes, and `start` to bind them |
| `pelican-pekko-docs` | 1.0.0 | pekko, openapi | the document and a Swagger UI or Redoc page and a Redoc or RapiDoc reference beside it, as a route |
| `pelican-pekko-mcp` | 1.0.0 | pekko, mcp-server | MCP tools over Streamable HTTP on `/mcp`, as a route |
| `pelican-test-pekko` | 1.0.0 | pekko, test | `inMemory()` through Pekko's own routing, no socket |
| `pelican-client-pekko` | 1.0.0 | core; Pekko HTTP *provided* | a generated client's transport over Pekko HTTP's client. No server |
| `pelican-streams` | 1.0.0 | pekko; Pekko and `lark-stream` *provided* | a streamed body as a lark `Stream`, whose failure is a value. Java 25, as lark 0.9.0 is |

Typical stack: `pelican-pekko`, `pelican-jackson`, `pelican-pekko-docs`, the
four Pekko lines, and `pelican-test-pekko` for tests.

### Jetty 12 backend

For a service that does not run Pekko. Jetty 12's core `Handler`, not the
servlet API, with no Pekko or Scala on the classpath. Every binder
`pelican-pekko` has exists here under the same name; streaming handlers take a
`Sequence` where Pekko takes a `Source`. Jetty is shipped rather than provided,
since it has no cross-build to choose.

| Module | Availability | Depends on | Provides |
|---|---|---|---|
| `pelican-jetty` | main only | core, `jetty-server` 12.1.13, `slf4j-api` | descriptions to a Jetty `Handler` (`toHandler`), and `start` to serve it |
| `pelican-jetty-docs` | main only | jetty, openapi | the document and a Swagger UI or Redoc page and a Redoc or RapiDoc reference beside it, as a `Handler` |
| `pelican-jetty-mcp` | main only | jetty, mcp-server | MCP tools over Streamable HTTP on `/mcp`, as a `Handler` |
| `pelican-test-jetty` | main only | jetty, test | `inMemory(handlers)` through a `LocalConnector`: no socket, but Jetty's own HTTP parsing |
| `pelican-client-jetty` | main only | core, `jetty-client` | a generated client's transport over Jetty's `HttpClient`. No server |
| `pelican-streams-jetty` | main only | jetty; `lark-stream` *provided* | a streamed upload as a lark `Stream`; runs on any lark backend. Java 25, as lark 0.9.0 is |

Typical stack: `pelican-bom`, `pelican-jetty`, `pelican-jackson` or
`pelican-kotlinx`, `pelican-jetty-docs`, and `pelican-test-jetty` for tests.

### Testing

| Module | Availability | Depends on | Provides |
|---|---|---|---|
| `pelican-test` | 1.0.0 | core | a typed client for tests, derived from the descriptions. `Api.inMemory()` needs no backend |
| `pelican-test-golden` | 1.0.0 | test, openapi | per-endpoint golden files; a test fails on a breaking change |
| `pelican-test-wiremock` | 1.0.0 | core, test, `wiremock-standalone` 3.13.1 | WireMock stubbed and verified in endpoint values, and stub files exported from them |

`pelican-test`'s `inMemory()` runs on core's in-memory transport; the backend
test modules run the same client through the real interpreter.

### Integrations

| Module | Availability | Depends on | Provides |
|---|---|---|---|
| `pelican-metrics` | 1.0.0 | core, `micrometer-core` | one filter; meters tagged from the descriptions |
| `pelican-metrics-otel` | 1.0.0 | core, `opentelemetry-api` | one filter; spans and a duration histogram, from the descriptions |
| `pelican-health` | 1.0.0 | core only | live and ready probes in `application/health+json`, and checks that need only the JDK |
| `pelican-health-kafka` | 1.0.0 | health; `kafka-clients` *provided* | `kafka(admin)`, over the service's own Kafka client |
| `pelican-health-redis` | 1.0.0 | health; `lettuce-core` *provided* | `redis(connection)`, over the service's own Lettuce connection |
| `pelican-oidc` | 1.0.0 | core, `nimbus-jose-jwt` (implementation) | verifies an OpenID Connect provider's tokens and says who is calling |
| `pelican-arrow` | 1.0.0 | core, `arrow-core` | Arrow's `Either` into Pelican's `Outcome` and back |
| `pelican-mcp` | 1.0.0 | core, schema | descriptions to MCP tool descriptions, and a dispatch that runs them. Values only |
| `pelican-mcp-server` | 1.0.0 | mcp | the tools served: JSON-RPC 2.0 over stdio (`mcpServe`), and the protocol the `-mcp` modules mount. No MCP SDK |

The two telemetry modules are separate so that a service using Micrometer does
not acquire the OpenTelemetry API, and the reverse.

### Tooling

| Module | Availability | Depends on | Provides |
|---|---|---|---|
| `pelican-codegen` | 1.0.0 | core | descriptions to a Kotlin client, as source |
| `pelican-import` | 1.0.0 | codegen, `snakeyaml-engine` | an OpenAPI document to descriptions and handler stubs, as source |
| Gradle plugin `io.github.matthewjones372.pelican` | 1.0.0 | nothing on your classpath | every generator as Gradle tasks: clients, documents, endpoints |
| `pelican-bom` | main only | nothing | version constraints for the 31 library modules; see [above](#version-alignment-and-the-bom) |

Most builds use the generators through the plugin rather than declaring
`pelican-codegen` or `pelican-import`. The plugin is on Maven Central, not the
Gradle Plugin Portal, so `settings.gradle.kts` needs
`pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }`.
See [The Gradle plugin](reference.md#the-gradle-plugin).

## Every dependency claim is a test

Each claim below is asserted by a test, so a dependency that leaks fails the
build. The rest of the "Depends on" column (the codecs, the `-docs` and `-mcp`
mountings, the backend test modules, `pelican-codegen`, `pelican-import` and
`pelican-oidc`) rests on the build files.

- `pelican-core` asserts its runtime classpath holds nothing but the Kotlin
  standard library and `jackson-core`, and that databind, kotlinx and Pekko are
  absent (`NoThirdPartyDependenciesTest`).
- `pelican-openapi` asserts Pekko is absent, so the document can be generated
  in a build task with no server present.
- `pelican-pekko` asserts the document generator is absent, so a service that
  serves only endpoints never ships it.
- `pelican-schema` asserts it carries no document generator and no codec.
- `pelican-mcp` asserts it is core plus schema and no MCP SDK.
  `pelican-mcp-server` asserts the same of the half that serves them: the
  protocol is JSON-RPC over lines of text, and the official Kotlin SDK's server
  half would put a Ktor server on the classpath.
- `pelican-metrics` asserts core plus a meter API and no server library;
  `pelican-metrics-otel` asserts core plus the OpenTelemetry API and no
  Micrometer.
- `pelican-health` asserts it is core and nothing else. `pelican-health-kafka`
  and `pelican-health-redis` assert they ship no Kafka or Lettuce client.
- `pelican-arrow` asserts it is core plus `arrow-core`.
- `pelican-client-pekko` asserts it ships no Pekko and not the Pekko
  interpreter, since making calls and serving routes are separate decisions.
  `pelican-client-jetty` asserts core plus Jetty's client half and no
  `jetty-server`.
- `pelican-streams` asserts it ships neither Pekko nor lark: `lark-stream-pekko`
  pins `_2.13` as an `api` dependency of its own. `pelican-streams-jetty`
  asserts no lark and no Pekko.
- The Jetty family is asserted as a whole in `example`'s
  `JettyServiceClasspathTest`: a service built from the Jetty modules, both
  codecs and the health, metrics and OIDC modules has no Pekko and no Scala at
  runtime, and adding `pelican-pekko` is what brings them.
- `pelican-test` asserts no server library and no matcher library, and
  `pelican-test-golden` no server library. `pelican-test-wiremock` asserts it
  is core, `pelican-test` and WireMock, with no backend and no JUnit, which it
  compiles against and does not ship.

The full breakdown is in [the reference](reference.md#modules).

## Two packages in `pelican-core`

`io.github.matthewjones372.pelican` is the DSL a service is written in.
`io.github.matthewjones372.pelican.spi` is what an interpreter or a transport
calls to turn descriptions into a running server: `handlerFor`, `routeIndex`,
`requestBodyCodec`, `readStrictBody`, `acceptable`, `successNamedBy`,
`renderError` and a handful more. None of them can be `internal`, because a
backend is a separate module, so the package is the fence instead. Both are
covered by the same binary-compatibility gate and are equally supported; a
service importing `spi` is reaching for plumbing rather than for the DSL.
`SpiPackageTest` in core holds the two apart.
