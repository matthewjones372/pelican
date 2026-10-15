# Choosing between Pelican and the alternatives

Linked from the [README](../README.md). This page is for deciding whether
Pelican fits a project, and for ruling it out quickly when it does not. It
covers what Pelican is good for, what it costs, what to check before adopting
it, and how it compares with the alternatives a Kotlin team is likely to
consider.

Statements about other projects are limited to what those projects say in
their own documentation, and were last checked in August 2026. Libraries
change; where a decision depends on one of those statements, check the
project's current documentation.

## Contents

- [When Pelican is a good fit](#when-pelican-is-a-good-fit)
- [When it is unnecessary](#when-it-is-unnecessary)
- [The trade-offs it introduces](#the-trade-offs-it-introduces)
- [Before adopting it](#before-adopting-it)
- [Hand-written routes and a separately maintained document](#hand-written-routes-and-a-separately-maintained-document)
- [Writing OpenAPI by hand and generating from it](#writing-openapi-by-hand-and-generating-from-it)
- [http4k's contracts and lenses](#http4ks-contracts-and-lenses)
- [Ktor's OpenAPI and Resources plugins](#ktors-openapi-and-resources-plugins)
- [Spring Boot, Micronaut and Quarkus](#spring-boot-micronaut-and-quarkus)
- [tapir](#tapir)
- [When Pelican is the wrong choice](#when-pelican-is-the-wrong-choice)
- [What the API promises from 1.0](#what-the-api-promises-from-10)

---

## When Pelican is a good fit

Pelican earns its place when the same HTTP contract is consumed in several
ways and keeping those in step by hand has become a cost:

- The service publishes an OpenAPI document that other teams or tools rely
  on, and the document has drifted from the server before.
- Input constraints (ranges, patterns, defaults, optionality) should be
  enforced and documented by the same declaration.
- Declared failures matter to callers, and you want a handler that forgets a
  documented 404 to fail to compile.
- You want a typed test client, a generated Kotlin client, golden-file checks
  on the wire contract, or MCP tools, all from the endpoint descriptions rather
  than from a second source.
- The service runs on Pekko HTTP already, or is new and can choose its server.
- The team writes Kotlin on the JVM and is willing to learn a small DSL.

## When it is unnecessary

- **A small internal API with one caller in the same repository.** If nobody
  reads the document and the caller is refactored with the server, the
  machinery buys little. Plain routes are simpler.
- **No published contract.** If there is no OpenAPI document, no generated
  client and no external consumer, most of what Pelican produces goes unused.
- **The framework you run already does enough.** If your Spring, Micronaut,
  Quarkus or Ktor service documents itself well enough for its consumers, a
  second description library is a cost without a matching benefit, and moving
  to Pelican means moving the web layer (see
  [Spring Boot, Micronaut and Quarkus](#spring-boot-micronaut-and-quarkus)).
- **The document is the agreed artefact.** If the OpenAPI file is negotiated
  before code exists, generate from it instead; see
  [Writing OpenAPI first](#writing-openapi-by-hand-and-generating-from-it).
  (`pelican-import` can generate Pelican descriptions from such a document, see
  [Importing](importing.md), but that only helps if you want Pelican on the
  server side anyway.)

## The trade-offs it introduces

- **A DSL to learn.** Inputs, outputs, failures and binders are Pelican's own
  types. Some HTTP behaviour you would write directly in a route is declared
  instead, and a reviewer needs to know what each declaration does.
- **An interpreter between the request and your code.** Measured against
  hand-written routes on the same backend in [What it costs](what-it-costs.md):
  on Pekko with the handler on the dispatcher, a few hundred nanoseconds and
  under a kilobyte per request behind the fastest hand-written route; on Jetty,
  within the benchmark's error in time and 1.1 to 1.3KB more allocated. Running
  synchronous handlers on a virtual thread, the default on JDK 24 and newer,
  is the largest cost measured and can be turned off for handlers that never block. These are
  in-memory measurements on one machine, on two small endpoints.
- **Fewer choices in the server.** Two backends exist, and only Pekko HTTP is
  in a release. A service on any other server cannot use Pelican without
  changing server.
- **Limits on what can be described.** Six typed inputs per endpoint before
  falling back to an untyped bag, no OpenAPI 3.0 output, no `anyOf`, and the
  other refusals listed under
  [When Pelican is the wrong choice](#when-pelican-is-the-wrong-choice).
- **Some checks happen at startup, not at compile time.** A path parameter
  declared but missing from the path, a missing codec or an unbound endpoint
  listed in `covers` fails when the `Api` is constructed. A test that builds
  the `Api` catches them; the compiler does not.
- **A young dependency.** 1.0.0 is the first stable release, maintained mainly
  by one person.

## Before adopting it

A checklist for a team evaluating Pelican 1.0.0:

- **The JDK.** 1.0.0's library modules are compiled for JDK 25, so a
  service on 1.0.0 must run on 25 or newer. On `main`, not yet released, they
  target JDK 21, except `pelican-streams` and `pelican-streams-jetty` (25), and
  synchronous handlers run on platform threads below JDK 24. The Gradle plugin
  needs Java 21 or newer for the build. See [Versions](reference.md#versions)
  and [Where a handler runs](reference.md#where-a-handler-runs).
- **Backend.** `pelican-pekko` is the released backend. Pelican declares
  Pekko `compileOnly`, so the service names its own Pekko and Pekko HTTP
  versions and Scala cross-build (`_2.13` or `_3`). The Jetty 12 backend, its
  docs, MCP, test, client and streams modules, `pelican-kotlinx` and the BOM
  are on `main` and not in any release; using them today means a snapshot,
  with no stability promise. See
  [Getting started](../README.md#getting-started).
- **A first stable release.** The API is frozen under the contract described
  [below](#what-the-api-promises-from-10), but it has a short track record.
  Pin exact versions.
- **The Gradle plugin** is published to Maven Central, not the Gradle Plugin
  Portal, so `pluginManagement` needs `mavenCentral()` in its repositories.
  See [The Gradle plugin](reference.md#the-gradle-plugin).
- **The OpenAPI version your consumers need.** Pelican writes 3.1.0 (default)
  and 3.2.0. If a consumer requires 3.0 or Swagger 2.0, that rules Pelican out
  for that document.
- **Interpreter cost.** Read [What it costs](what-it-costs.md) and decide
  whether the virtual-thread default suits your handlers.
- **Client transports.** A generated client sends through a `ClientTransport`.
  In 1.0.0 the shipped adapter is `pelican-client-pekko`; the Jetty adapter is
  on `main` only. Anything else (OkHttp, the JDK client) is an adapter you
  write. See [The generated client](generated-client.md).
- **Escape hatches.** Pelican does not have to own the server.
  `api.toRoute(system)` returns a Pekko `Route` that concatenates with routes
  you already have, and a request it does not describe falls through to them,
  so an existing service can move one endpoint at a time. On Jetty,
  `toHandler()` sits in a `Handler.Sequence` the same way. Inside a handler,
  `params.request` exposes the backend's own request (`HttpRequest` on Pekko).
  See [The backend](reference.md#the-backend).
- **Leaving.** The descriptions are Pelican-specific. The OpenAPI document it
  produces is not, and is the natural thing to keep if you move away: it can be
  checked in and served or generated from by other tools.

---

## Hand-written routes and a separately maintained document

Writing routes directly against the server library and keeping an OpenAPI
document beside them, as annotations or as a file, is the most common setup
and often the right one.

**It is preferable when:**

- the API is small and changes rarely, so the drift a second copy allows is
  cheap to catch in review;
- the team knows the server library well and does not want another
  abstraction over it;
- you need route-level control Pelican does not describe (custom rejection
  handling, unusual content negotiation, protocols other than HTTP
  request/response);
- the latency budget cannot absorb an interpreter, or you have not measured
  whether it can;
- the server is one Pelican has no backend for.

**What it costs.** Each fact about an endpoint (path, parameter names, types,
constraints, defaults, response shapes, failure payloads) is written once for
the server and once for the document, and nothing compares the two.
[The same endpoints, by hand](by-hand.md) shows two endpoints written both ways
on Pekko HTTP and lists each duplicated fact. Contract tests that exercise the
running server against the document (Schemathesis is one tool for this) can
catch drift after the fact.

---

## Writing OpenAPI by hand and generating from it

Document-first: write the OpenAPI file, review it, and generate server stubs
and clients from it.

**What it does better.**

The document is the contract in the strongest sense: a file, reviewed in a
pull request, versioned, and agreed before either side writes code. A Pelican
document is derived from the server, so it is accurate about the server but
cannot exist before it.

It is language-neutral. `openapi-generator` publishes generators for many
languages and frameworks, including several Kotlin clients and Kotlin server
targets. Pelican generates one Kotlin client. If callers are in Go, Python and
TypeScript, a hand-written document serves all of them; a Pelican document can
too, since it is ordinary OpenAPI, but Pelican's own generator does not.

The tooling around a specification file is mature: linters such as Spectral
and Redocly CLI enforce house style, and contract-testing tools such as
Schemathesis test a running API against its document. Pelican's
`pelican-test-golden` answers a narrower question, whether a change breaks
callers you already have, and does not exercise a running server against its
document.

**Take document-first instead when** the document is negotiated with people
outside your team before implementation; when callers are in several
languages; when your organisation enforces a lint rule set on API documents;
or when you must publish a specification version Pelican does not write.

**What it costs.** Generating server stubs makes the routes match the
document; it does not make handler behaviour match, and it puts a code
generator in the build. Generating only clients leaves the server unchecked.
`openapi-generator`'s README describes its OpenAPI 3.1 support as beta, so a
document written to 3.1 or 3.2 may not be read fully by that toolchain. The
same applies to Pelican's 3.2.0 output: Pelican defaults to 3.1.0 because
swagger-parser and tooling built on it do not read 3.2.0 documents (see
[Which version the document says](reference.md#which-version-the-document-says-and-how-to-choose)).

---

## http4k's contracts and lenses

The closest neighbour in design. If you are already on http4k, its contract
module is in the same project you depend on, needs no new concepts and no new
dependency tree, and is the obvious first choice.

The shape is similar to Pelican's. A lens reads a typed value out of an HTTP
message (`Query.int().required("times")`), a route is declared with
`bindContract` and a `meta { }` block listing its queries, headers, request
and response bodies, and the contract module validates each call against that
declaration and renders an OpenAPI document from it. In http4k 6 the artifact
is `http4k-api-openapi`; the package is `org.http4k.contract`.

**What it does better.**

- It can render OpenAPI 3 and Swagger 2.0, so it can serve consumers that
  require an older version than Pelican writes.
- It is much broader. http4k covers serverless adapters, testing tools, cloud
  service fakes and more from one project. Pelican describes HTTP endpoints and
  does nothing else.
- Its typed input limits are looser. Typed path segments in a contract route
  have a fixed maximum, but queries, headers and bodies are listed in
  `meta { }` without an arity limit. Pelican's limit of six covers all typed
  inputs together.
- A client is an `HttpHandler`, the same `(Request) -> Response` type as a
  server, so the same filters decorate both and an in-memory fake replaces a
  real call without an adapter.

**Where Pelican differs.** In http4k the constraint lives in the lens and the
JSON Schema for bodies comes from reflection or an example through the JSON
library you configured. In Pelican the constraint is on the input value, so the
same declaration is the decoder and the schema's `pattern` or bounds, and
declared failures are part of the endpoint's type, which the binder checks
against the handler's return type. Whether that is worth a second dependency
for an http4k service is a judgement, and staying with http4k's contracts is a
reasonable answer.

**Take http4k's contracts instead when** you are already on http4k; when you
need to publish OpenAPI 3.0 or Swagger 2.0; when endpoints have more than six
typed inputs; or when you want other parts of the http4k ecosystem and prefer
one dependency family.

---

## Ktor's OpenAPI and Resources plugins

For a Ktor service, Ktor's own features come first.

Ktor's documentation describes OpenAPI generation from routing code through
its Gradle plugin: route structure (paths, methods, path parameters) is
analysed at compile time, request and response details are inferred from a
set of recognised handler calls, and anything the inference does not cover is
described by hand, in comments or a runtime description DSL. The feature is
opt-in. Ktor also has a long-established Resources plugin: a `@Resource` class
gives typed routes, URL building, and a matching client (`ktor-client-resources`)
that builds requests from the same class.

**What it does better.** It is free if you are on Ktor: no new library, no
second way of describing a route. It is a first-party JetBrains feature with a
large user base. Resources gives typed URLs on both server and client.

**Where Pelican differs.** Ktor's document is recovered from code that was
written for the server; a handler that does something the inference does not
recognise is documented incompletely and nothing fails. A Resources class
carries the path and parameters but not the HTTP method, request body or
response type, which are chosen at the call site. In Pelican the description
is what the route is built from, and it carries method, inputs, outputs and
failures.

**Take Ktor's plugins instead when** you are on Ktor and want typed routing and
a generated document without a new dependency, when typed URLs are all you
needed, or when you would rather rely on a first-party feature than a
first-release library. Pelican has no Ktor backend, so on Ktor it is not an
option without changing server.

---

## Spring Boot, Micronaut and Quarkus

Pelican has no backend for any of these. Choosing Pelican means running the
web layer on Pekko HTTP (or, from `main`, Jetty) rather than adding a module to
the framework you have. This section compares two ways of describing an HTTP
API for a team deciding what to start with, not a migration that can be done
piecemeal.

The three differ mostly in when the document is produced:

- **Spring Boot with springdoc-openapi** builds the document at runtime by
  inspecting the application's handler mappings, classes and annotations, and
  serves it from an endpoint. Because it reads the live mappings, the paths and
  methods it publishes are the ones the server dispatches.
- **Micronaut** (micronaut-openapi) generates the document at compile time
  through an annotation processor, so it exists before the application starts
  and CI can read it without booting a server.
- **Quarkus** (`quarkus-smallrye-openapi`) implements MicroProfile OpenAPI over
  Jakarta REST resources, produces a document without configuration, serves it
  at `/q/openapi`, and offers Swagger UI in dev mode. The emitted OpenAPI
  version is configurable.

**What all three do better.** They are mainstream: extensive documentation, a
hiring pool, answers to most questions already written down, commercial
support, and security, persistence, messaging, configuration and observability
integrated with the same programming model. Each is a full application
framework. Pelican is a library that describes endpoints and owns nothing else.
Micronaut and Quarkus also invest heavily in startup time and native images,
which Pelican has not been measured against.

**Where Pelican differs.** In all three, Jakarta Bean Validation constraints
(`@Min`, `@Max`, `@Pattern` and so on) are enforced at runtime and also appear
in the generated schema, so input constraints can already be stated once. What
is not checked is the documentation-only part: `@Operation`, `@ApiResponse`
and their equivalents describe responses, failure statuses and examples, and
nothing verifies that a handler produces what they say. Pelican's declared
outputs and failures are part of the endpoint's type, so a handler that does
not produce the declared failure type does not compile.

**Take one of these instead when** the service needs more than HTTP
description (persistence, messaging, scheduling, a security model) and you
want one framework to supply it; when the team already knows the framework;
when you need commercial support; or when native-image startup is a
requirement.

---

## tapir

tapir, a Scala library, is the main influence on Pelican: an endpoint is a
value, inputs and outputs accumulate onto it, and interpreters turn that value
into a server route, a document and a client.

**What it does better, which is most things.** It is mature and much larger.
It documents server interpreters for many Scala and JVM servers (Pekko HTTP
and http4s among them) and several client interpreters, supports many JSON
libraries, and can produce AsyncAPI and JSON Schema as well as OpenAPI,
including OpenAPI 3.0 output. Schemas and codecs are derived at compile time
through Scala's implicits, so a type with no codec is a compile error at the
endpoint that needs it, and inputs accumulate at the type level without a
per-arity overload.

**What Kotlin costs, concretely.** Kotlin has no equivalent of the implicit
machinery tapir relies on, so:

- Pelican resolves codecs when the `Api` is assembled. A missing codec is a
  startup failure, not a compile error.
- Pelican has one `endpoint(...)` overload per arity and stops at six typed
  inputs. tapir's limit is far higher. Past six, Pelican's fallback is an
  untyped `Params` bag read by key, which throws on an undeclared key at
  request time rather than failing to compile.

**Take tapir instead when** you are writing Scala. There is no reason for a
Scala project to use Pelican. For a Kotlin project, Pelican implements the
parts of tapir's design that translate to Kotlin and lacks the rest.

---

## When Pelican is the wrong choice

Separately from any comparison, some projects should not use it.

**A first release.** The API is frozen and guarded by the `.api` dumps and
`StillCompilesTest`, and a break waits for a major release. But 1.0.0 has
little production history, and it is maintained mainly by one person. A service
maintained for years by people who did not choose Pelican takes on more risk
than one on a mainstream framework.

**Your server is not Pekko HTTP.** Pekko HTTP is the backend in 1.0.0. A Jetty
12 backend (a Jetty core `Handler`, not a servlet, with no Pekko or Scala on
the classpath) is on `main` and not yet in a release. On Spring MVC, Ktor,
Vert.x, Helidon, Armeria, Netty directly, Jakarta REST or Javalin there is no
module, and writing a backend is a real piece of work. A parity suite on `main`
runs the same descriptions against Pekko and Jetty and asserts identical
answers and documents, and records the few places they differ; see
[What differs on Jetty](reference.md#what-differs-on-jetty).

**A JDK older than 25.** 1.0.0's library modules will not load on an older
runtime.

**A refusal you cannot work around.** Each of these is a documented decision
with its reasoning in [What isn't here](reference.md#what-isnt-here):

- **OpenAPI 3.0 and Swagger 2.0 are not emitted.** 3.1.0 (the default) and
  3.2.0 are. 3.0 cannot express that a `$ref` may be null, so a 3.0 emitter
  could not be faithful. If a consumer dictates a version below 3.1, that rules
  Pelican out for that document. The importer reads 3.2, 3.1, 3.0 and Swagger
  2.0, which is the other direction and does not help here.
- **`callbacks` are not described.** Top-level `webhooks` are described and
  generated.
- **`anyOf` of several branches, and `not`, are refused.** A payload can
  satisfy two `anyOf` branches and a Kotlin value is one class, so there is no
  faithful description. An API that uses `anyOf` structurally will not import
  in that corner.
- **Form bodies carry scalars and arrays of scalars only.** A nested object in
  a form body is refused when the endpoint is bound.
- **Six typed inputs, then a bag.** Past six, `endpoint(lensInputs)` hands the
  handler the whole `Params`, and reading an undeclared key compiles and
  throws at request time.
- **No encoder for non-JSON representations.** `negotiated(...)` declares
  several renderings chosen by `Accept`, but the writer for anything other than
  JSON is one you supply on your `Codecs`. There is no CSV or XML module.
- **CORS is one policy on the `Api`**, not per endpoint.

**You need a full client library.** `pelican-codegen` generates a Kotlin
client into your build: one method per operation, sealed failure types,
streamed responses, per-request timeouts, blocking or `suspend` methods, and a
`ClientTransport` it sends through. There is no published client artifact; you
own the generated file. In 1.0.0 the shipped transport is
`pelican-client-pekko`. Retries are a decorator you opt into; circuit breaking
and per-call metrics are yours to add around the transport. The client that
`pelican-test` derives is for tests only: blocking, no retries, no pooling to
speak of, and it cannot upload a binary file part because its `RequestSpec`
carries a `String` body.

**You need everything checked at compile time.** The compiler does not catch a
path parameter declared but absent from the path, a captured segment nothing
declares, a scope its scheme never granted, or a missing codec; those throw
when the endpoint or the `Api` is constructed. It does not catch two inputs of
the same type read in the wrong order, and it lets a handler destructure a
prefix of its inputs and ignore the rest. `api { covers = ... }` checks that
every declared endpoint is bound, but at startup.

---

## What the API promises from 1.0

The public API of the released modules is stable, and a breaking change waits
for a major release. The endpoint DSL, the binder names, the codec interfaces
and the module boundaries are inside that promise.

Two artefacts are the contract, and both are checked on every build. The `.api`
dump beside each module is the binary contract: `apiCheck` fails when a
published signature changes, so a break cannot reach a release without the
dump changing in the same commit. `StillCompilesTest` covers what a dump cannot
see: `json<T>()`, `jsonBody<T>()`, `pathParam<T>()`, `sse<T>()`,
`errorJson<T>()` and the rest are reified inline functions, so callers compile
against source, and the suite compiles pinned call sites against the published
modules.

Outside the promise: anything `internal`, the emitted document's exact bytes,
and modules that are not in a release (the Jetty family, `pelican-kotlinx` and
the BOM, which are on `main`). The full statement is under
[Stability](reference.md#stability).

Pin an exact version and upgrade deliberately: not a range, not `+`, and not a
version chosen by a plugin you do not control. Pin the Gradle plugin at the
same version as the libraries, since its generators and the runtime read the
same descriptions and are not tested against each other across versions.

Whatever you decide about Pelican, pin the wire contract your callers depend
on in a test, so that a change to what you publish fails the build instead of
reaching them. [Golden files](golden-testing.md) is how that is done here, and
`app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"` is the one-line
form.
