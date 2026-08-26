# Choosing between Pelican and the alternatives

Linked from the [README](../README.md). The page for deciding *against* Pelican
quickly, and for a reason that is actually true.

Pelican is at 0.1.0. The README explains what it does and the reference manual
explains how, but neither answers the question a reader arrives with, which is
whether the thing they already have is worse. Usually it is not. This page is
the honest version of that comparison: for each neighbour, what it does better
than Pelican does, and which projects should take it instead.

Every claim about another project below was checked against that project's own
current documentation in August 2026, and the version checked is named, because
a comparison with no version on it stops being true without telling anybody.
Where something could not be verified from a project's own documentation it is
either left out or stated as the weaker thing that could be supported.

---

## Contents

- [http4k's contracts and lenses](#http4ks-contracts-and-lenses)
- [Ktor's OpenAPI and Resources plugins](#ktors-openapi-and-resources-plugins)
- [Spring Boot, Micronaut and Quarkus](#spring-boot-micronaut-and-quarkus)
- [tapir](#tapir)
- [Writing OpenAPI by hand and generating from it](#writing-openapi-by-hand-and-generating-from-it)
- [When Pelican is the wrong choice](#when-pelican-is-the-wrong-choice)
- [The API will break before 1.0](#the-api-will-break-before-10)

---

## http4k's contracts and lenses

Checked against http4k 6.58.0.0, which is the version `pelican-http4k` builds
against on the [`multi-backend`](https://github.com/matthewjones372/pelican/tree/multi-backend) branch.

This is the closest neighbour by a distance, and http4k is also a backend
Pelican interprets onto — on that branch, and after 1.0 on main — which makes
the comparison an awkward one to write. If you are
already on http4k, http4k's own contract module is sitting in the same
repository you are already pulling from, and it needs no new concept and no new
dependency tree.

The shape is genuinely similar. A `Lens` reads a value out of an HTTP message
and a `BiDiLens` also writes one back — `Query.int().required("times")` is a
lens, and applying it to a request is how the handler gets an `Int`. A route is
declared with `bindContract`, its metadata with a `meta { }` block carrying
`queries`, `headers`, `receiving(...)` and `returning(...)`, and the module
validates the declared contract per call, answering 400 on input that does not
meet it. Since v6 the artifact is called `http4k-api-openapi` rather than
`http4k-contract`, though the Kotlin package is still `org.http4k.contract`.

**What it does better.**

It emits more of OpenAPI than Pelican does. The `OpenApiVersion` enum in
6.58.0.0 has four entries — `_2_0_0`, `_3_0_0`, `_3_1_0` and `_3_2_0` — and the
`OpenApi3` renderer takes the version as a constructor parameter, defaulting to
3.2.0. `OpenApi2` renders Swagger 2.0. Pelican writes two of those four: 3.1.0,
which is its default, and 3.2.0, selectable the same way. So the gap has
narrowed to the old end of the range — http4k still covers the 3.0 and 2.0 your
vendor's tooling may require, and Pelican refuses both on the grounds that it
could not write them faithfully. That is not a small difference if a consumer
of your document gets to dictate its version and dictates an old one.

It is enormously broader. The 6.58.0.0 API documentation publishes 221 modules.
Beyond API description there is chaos engineering, Servirtium service
virtualisation, approval testing, WebDriver and Playwright drivers, serverless
adapters for five clouds, an AWS surface where most services ship with a
matching fake, and a large MCP and LLM section. Pelican is nineteen modules
that describe HTTP endpoints and does nothing else on purpose, which is a
smaller promise, not a better one.

Its arity ceiling is higher where it matters most in practice. http4k caps
*path* segments at ten — the `ContractRouteSpec0` through `ContractRouteSpec10`
chain, where an eleventh returns `Nothing` — but queries, headers and bodies
accumulate into lists in the `meta { }` block and are not capped at all.
Pelican's limit of six is on *every* typed input together, so an endpoint with
two path parameters and five query parameters is already past it.

And a client is not a special thing. In http4k a client is an `HttpHandler` —
the same `(Request) -> Response` a server is — so the same filters decorate
both and an in-memory fake substitutes for a real call without any adapter.

**Take http4k's contracts instead when** you are already an http4k shop and the
cost of a second description library is not obviously repaid; when you need to
publish OpenAPI 3.0 or Swagger 2.0; when your endpoints have more than six typed inputs
and you would rather keep them typed than drop to a bag; or when you want any
of the two hundred other things in that repository and would rather have one
vendor than two.

One honest note in the other direction, because it is the reason Pelican's
http4k module exists at all: in http4k the schema and the route validation come
from lenses that you assemble per message, and the JSON Schema is produced by
reflection over your classes through whichever marshaller you configured
(`AutoJsonToJsonSchema` for the reflective path, `JsonToJsonSchema` for the
non-reflective one that names models from an example). Pelican's arrangement
puts the constraint on the input value itself so the same declaration is the
decoder and the `pattern`. Whether that is worth a dependency is a judgement,
and http4k's answer to it is a perfectly reasonable one.

---

## Ktor's OpenAPI and Resources plugins

Checked against Ktor 3.5.2, released 4 August 2026, which is the version
`pelican-ktor` builds against on the [`multi-backend`](https://github.com/matthewjones372/pelican/tree/multi-backend) branch.

Ktor's OpenAPI story changed shape recently and a lot of material written about
it is now wrong, so it is worth stating what is there today. The Ktor Gradle
plugin carries an OpenAPI compiler extension which, in the documentation's own
description, analyses routing code at compile time and generates Kotlin code
that registers OpenAPI metadata at runtime; the document itself is assembled
from the routing tree while the application is running. Until 3.4.0 the plugin
wrote a static document at build time instead. This is real generation from
code, not the older arrangement of serving a specification file you wrote
yourself.

**What it does better.**

It is free if you are already on Ktor. There is no extra library to adopt, no
second way of describing a route, and the documentation states that no
additional build or generation step is required — a route change shows up the
next time the specification is requested.

Resources is a good, long-settled type-safe routing mechanism. `@Resource` on a
`@Serializable` class gives you typed route declarations, reverse URL building
through `href(...)`, and — this is the part with no Pelican equivalent in the
same form — a client side, `ktor-client-resources`, that builds requests from
the same class the server routed on.

**Take Ktor's own plugins instead when** you are on Ktor and want typed routing
without a new dependency, when Resources' URL-level type safety is the amount
you actually needed, or when you would rather have a first-party feature from
JetBrains than a 0.1 library from one person.

Three things are worth knowing before you decide, all from Ktor's own
documentation. The generation feature is opt-in: `enabled` defaults to `false`.
The Gradle extension states that it requires Kotlin 2.2.20 and that other
versions may cause compilation errors. And the compiler's structural analysis is
explicitly shallow — it reads route declarations and "does not inspect the
contents of route handlers", so paths, methods and path parameters come out of
the structure, while request bodies, responses and the rest are recovered by a
separate inference pass that pattern-matches a fixed list of recognised idioms
(`call.receive<T>()`, `call.respond<T>()`, `call.queryParameters[...]` and a
handful more). Anything outside that list is described by hand, in KDoc-style
comment annotations or the runtime `.describe { }` DSL, and that runtime
annotations API is still experimental and behind `@OptIn(ExperimentalKtorApi::class)`.
Where inference gets an endpoint wrong the documented remedy is an `// ignore!`
marker to exclude it.

What that adds up to is a description recovered from code rather than one the
code is built from, which is a different guarantee: an endpoint whose handler
does something the inference does not recognise is documented incompletely and
nothing fails. On the Resources side the same distinction applies to the client.
A resource class carries the path and its parameters; it does not carry the HTTP
method, the request body type or the response type. The verb is chosen at the
call site, so nothing stops a client calling `delete` on a resource the server
only bound for `get`.

One incidental detail worth checking before you commit: Ktor's `openAPI()` HTML
renderers, `StaticHtmlCodegen` and `StaticHtml2Codegen`, support only OpenAPI
3.0.x, and the documentation says using them with a 3.1 document may produce
incomplete or incorrect HTML. The suggested route for 3.1 is `swaggerUI()` with
Swagger UI 5.x.

---

## Spring Boot, Micronaut and Quarkus

The three annotation-driven stacks differ from each other more than the usual
grouping suggests, mostly in *when* the document is produced.

**Spring Boot with springdoc-openapi.** Checked against springdoc-openapi 3.1.0,
published 1 August 2026, which tracks Spring Boot 4.1. springdoc's own summary
is that it works by examining an application at runtime to infer API semantics
from Spring configuration, class structure and annotations. By default that
happens lazily, on the first request to the api-docs endpoint —
`springdoc.pre-loading-enabled` defaults to `false` — and the result is cached.
Its default output is now OpenAPI 3.1, selectable through
`springdoc.api-docs.version`. Because it reads live handler mappings, the paths
and methods it publishes are the ones the server will actually dispatch, which
is a real advantage over any scheme that reads source.

**Micronaut.** Checked against Micronaut Platform 5.1.2 and micronaut-openapi
7.1.2. This one is an annotation processor: the documentation says Micronaut
produces the OpenAPI YAML at compilation time, and it is wired as
`annotationProcessor`, `kapt` or `ksp` depending on your language, writing to
`META-INF/swagger/`. Annotations are optional — a document is produced from the
ordinary Micronaut annotations and your Javadoc, with the Swagger annotations
taking precedence when present. The same compile-time machinery that builds the
dependency graph builds the document, so it exists before the application ever
starts and CI can read it without booting a server. Note that its default output
is OpenAPI 3.0; 3.1 is opt-in through `micronaut.openapi.openapi31.enabled`,
which defaults to `false`.

**Quarkus.** Checked against Quarkus 3.38.3, with 3.33.x as the current LTS
line. `quarkus-smallrye-openapi` implements MicroProfile OpenAPI over Jakarta
REST resources, needs no configuration to produce a document, and serves it at
`/q/openapi`. Its default output is 3.1.0 and it can be told to emit an exact
older version instead — `quarkus.smallrye-openapi.open-api-version=3.0.4`, for
instance — which is more control over the emitted version than Pelican offers
at all. Swagger UI is at `/q/swagger-ui` in dev and test mode, the Dev UI at
`/q/dev-ui` links straight to the generated schema, and
`store-schema-directory` writes the document out at build time for downstream
consumers.

**What all three do better.** They are the mainstream, and that is not a
throwaway point — it means an enormous body of documentation, a hiring pool,
answers to your specific question already written down, security and observability
and data access integrated with the same annotations, and vendor support you can
buy. Each of the three is a complete application framework with dependency
injection, configuration, testing and a native-image story; Pelican is a library
that describes endpoints and deliberately owns nothing else, so on any of these
stacks it would be replacing one part of something you would still need the rest
of. Spring's springdoc documents Kotlin types and has Kotlin-specific settings;
Micronaut and Quarkus both put a great deal of work into startup time and
native images that Pelican has never measured itself against.

**Take one of these instead when** you are building a service that needs more
than HTTP description — persistence, messaging, scheduling, a security model —
and want one framework to supply it; when your team already knows the
annotations; when you need commercial support; or when native-image startup time
is a requirement, since no Pelican backend has been characterised for that
here.

The one thing worth being clear about, in all three, is that the document is
derived from the code and is not enforced against traffic. The routing is read
faithfully enough. But `@Operation`, `@Schema`, `@APIResponse` and their
equivalents are documentation: nothing in any of these stacks validates a
request or a response against them at runtime, so a wrong example, an
undocumented failure status or a `required` that disagrees with the handler is a
silent inconsistency in every one of them. That is the specific problem Pelican
was written for, and it is fair to say that it is the *only* problem Pelican was
written for.

---

## tapir

Checked against tapir 1.13.31, published 7 August 2026.

The README calls tapir an influence and it is the honest description: Pelican's
core idea is tapir's. An endpoint is a value, inputs and outputs accumulate onto
it, and interpreters turn that one value into a server route, a document and a
client.

tapir's endpoint type is
`Endpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, -R]`, with a
`PublicEndpoint` alias for the case where the security input is `Unit`. The last
parameter carries the capabilities an endpoint's inputs and outputs require,
such as a particular streaming implementation or websocket support — which is
how tapir names in the type the thing Pelican handles by changing which module
you bind with.

**What it does better, and it is most things.**

It is mature and it is much larger. Roughly a dozen server interpreters are
documented — among them http4s, Netty in four variants, Pekko HTTP, Play,
Vert.x, Armeria, ZIO HTTP, Helidon Níma and AWS Lambda — against Pelican's
three, and four client interpreters against Pelican's one generated file. Eight
JSON libraries are supported where Pelican has three.

It describes more. Besides OpenAPI it generates AsyncAPI, through
`tapir-asyncapi-docs` against the AsyncAPI 2.0.0 specification, and standalone
JSON Schema. It emits OpenAPI 3.1.0 by default and can be asked for 3.0.3
through a separate encoder, so unlike Pelican it can talk to consumers stuck on
3.0. It has an `sbt-openapi-codegen` plugin that reads a specification and
writes Scala endpoint definitions, the direction `pelican-import` covers.

**What Kotlin costs, concretely.** Scala's implicits are load-bearing in tapir
in two places, and neither carries across.

The first is schema and codec derivation. tapir derives a `Schema[T]` through
Magnolia — fully automatically with `import sttp.tapir.generic.auto.*`, or
explicitly with `Schema.derived[T]` — and the JSON codec through the same
implicit mechanism from whichever library you chose. A type that has no instance
in scope is a compile error at the endpoint that needs it. Pelican has no such
mechanism, so a description carries a `KType` and the codec is resolved when the
`Api` is assembled. That resolution succeeding is a startup check rather than a
compile-time one — which is stated plainly in the reference manual, and is a
genuine loss.

The second is input accumulation. tapir's `.in(...)` takes an implicit
`ParamConcat.Aux` that concatenates the accumulated tuple with the new input's
type at the type level, so inputs chain without an overload per shape. Kotlin
has nothing that does this, so Pelican has one `endpoint(...)` overload per
arity and stops at six. It should be said that tapir does not escape an arity
ceiling either — its `TupleArity` instances are generated up to 21, near Scala's
own tuple limit — but 21 and six are not the same number, and the fallback past
six in Pelican is an untyped `Params` bag read by key, which throws at request
time rather than failing to compile.

**Take tapir instead when** you are writing Scala. That is the whole of it —
there is no case in which a Scala project should reach for Pelican. If you are
writing Kotlin and admire tapir, the fair statement is that Pelican implements
the parts of the idea that survive the translation and is several years and one
type system short of the rest.

Two smaller notes for anyone comparing feature lists directly. tapir's `oneOf`
and `oneOfBody` output combinators are, as its documentation says outright, not
related to OpenAPI `oneOf` schemas; those come from coproducts. And no callback
combinator exists in tapir's endpoint DSL either, so on `callbacks` the two
libraries are in the same place, for what is probably the same reason.

---

## Writing OpenAPI by hand and generating from it

This is the option most teams actually take, and the one a library like Pelican
has to argue with rather than dismiss.

The specification is at 3.2.0, published 19 September 2025, and Pelican now
writes it — though not by default, because swagger-parser and the JVM tooling
built on it still read a 3.2.0 document as nothing at all. Which is worth
noting before anything else, because it is a problem a hand-written document
has in exactly the same way: choosing 3.2 is choosing what your consumers'
tools can read, not what the specification says.

**What it does better.**

The document is the contract, in the strongest available sense: it is a file,
it is reviewed in a pull request, it is versioned, and it can be agreed before
any code exists on either side. Nothing derived from an implementation can offer
that, Pelican included — a Pelican document is the truth about the server, but
it is written after the server, and design-first is a real workflow with real
advantages for teams whose consumers are not in the same building.

It is language-neutral. `openapi-generator` 7.25.0 publishes on the order of a
hundred and seventy generators, and the Kotlin ones alone offer a choice of
transport — the `kotlin` client defaults to OkHttp 4 with Moshi and can be
pointed at Ktor, Retrofit, Vert.x, Spring WebClient or RestClient, or Kotlin
Multiplatform, with kotlinx-serialization, Gson or Jackson for bodies — plus
`kotlin-spring` and `kotlin-server` on the server side. Pelican generates one
Kotlin client with one transport. If your callers are on Go, Python and
TypeScript, one hand-written YAML file serves all three and Pelican serves none
of them.

The tooling around a specification file is mature in a way nothing here matches.
Spectral 6.16.3 lints against a house style guide, Redocly CLI 2.47.0 lints and
bundles and reads 3.2 as well as 3.1, 3.0 and 2.0, and vacuum 0.30.0 covers 3.0
through 3.2. Conformance testing between a document and a live server exists too
— Redocly's `respect` sends real requests and validates the responses against
the description, and Schemathesis generates inputs from a schema and tests a
running API with them. Pelican's `pelican-test-golden` answers a narrower
question, whether a change breaks callers you already have, and does not
exercise a running server against its own document at all.

**Take hand-written OpenAPI instead when** the document is genuinely negotiated
with people outside your team before implementation; when your callers are in
several languages; when a spec-linting rule set is a thing your organisation
enforces; or when the specification version you must publish is one Pelican does
not write.

The cost, which is the reason people leave, is the one the `by-hand` page walks
through in Kotlin terms: the file and the server are two artefacts, and keeping
them in step is a human process that fails quietly. It is worth being precise
about how much of that generation solves. Generating server stubs from the
document does make the routes match; it does not make the handler's behaviour
match, and it puts a code generator in the middle of your build. Generating only
clients leaves the server side unchecked entirely. And `openapi-generator`'s own
README describes its OpenAPI 3.1 support as beta — the compatibility line reads
"1.0, 1.1, 1.2, 2.0, 3.0, 3.1 (beta support)" — with no 3.2 support that could
be verified, so a document written to the current specification is not
necessarily a document this toolchain reads well.

---

## When Pelican is the wrong choice

Separately from any comparison, there are projects that should not use this
library at all.

**It is 0.1.0 and the API moves.** There is one released version. The
description DSL, the binder names and the module layout are all still being
shaped by what the examples turn out to need, and there is no deprecation cycle
because there is nothing yet to deprecate against. A service that will be
maintained for years by people who did not choose this is taking on a risk that
a Spring or Ktor service is not.

**One backend at 1.0.** Pekko HTTP, and nothing else on main. If you are on
Vert.x, Helidon, Armeria, Netty directly, Jakarta REST, Javalin or Spring MVC,
there is no module for you, and writing one is a real piece of work even though
the reference manual puts the last one at about five hundred lines.

The interpreter is not shaped by Pekko, and that is provable rather than
asserted: http4k and Ktor interpreters exist, complete and green, on the
[`multi-backend`](https://github.com/matthewjones372/pelican/tree/multi-backend) branch, where one parity suite runs the same descriptions
against all three and asserts they answer byte for byte. What narrowed for 1.0
is the *promise*, not the design — a first release's stability guarantee covers
the pair the maintainer can stand behind, and the other two return after it as
restores. Until they do, "the backend is a choice" is a claim about the code you
can read on a branch, not about a module you can depend on today.

**The refusals are deliberate and they will not be argued away.** Each of these
is a documented decision with reasoning in the reference manual, not a gap
waiting on a release:

- **OpenAPI 3.0 is not emitted, and neither is Swagger 2.0.** 3.1.0 and 3.2.0
  are both written — 3.1.0 by default, because swagger-parser and the tooling
  built on it still read a 3.2.0 document as nothing at all — so the current
  specification is reachable and the old ones are not. Writing 3.0 would mean a
  second emitter that could not be faithful, since 3.0 cannot say that a `$ref`
  may be null. If a consumer of your document dictates a version below 3.1,
  check that first, because it is the fastest way to rule Pelican out. The
  *importer* reads 3.2, 3.1, 3.0 and Swagger 2.0, which is the other direction
  and does not help here.
- **`callbacks` are not described.** A callback URL taken out of an operation's
  own payload through a runtime expression has no equivalent in an endpoint
  description. Top-level `webhooks` are described and generated; if what you
  need is the `callbacks` object specifically, it is not here.
- **`anyOf` of several branches is refused**, as is `not`. A payload may satisfy
  two `anyOf` branches at once and a Kotlin value is one class or the other, so
  there is no faithful description. If the API you must model or import uses
  `anyOf` structurally, that corner of it will not build.
- **Form bodies carry scalars and arrays of scalars only.** A nested object in a
  form body is refused when the endpoint is bound, because there is no bracket
  convention that everyone agrees on.
- **Six typed inputs, and then a bag.** `endpoint(a..f)` is the largest
  overload. Past it the lens form takes the whole `Params` and reading an
  undeclared key throws at request time instead of failing to compile — which
  gives up most of what you came for. Six is fewer than http4k's ten path
  segments with uncapped queries and headers, and much fewer than tapir's
  twenty-one.
- **No encoder for a second representation.** `negotiated(...)` says a response
  is written several ways and `Accept` picks between them, but the writer for
  anything that is not JSON is one you supply on your `Codecs`. There is no CSV
  or XML module here.
- **CORS is one policy on the `Api`**, not per endpoint.

**There is no production client story yet, only most of one.** `pelican-codegen`
generates a real Kotlin client from the descriptions: one method per operation,
sealed failure types, streamed responses, a per-request timeout, and a
`ClientTransport` it sends through rather than an HTTP library it is welded to.
That is more than a toy. But there is no published client artifact — the file is
generated into your build, and you own it. Four transports are written:
`pelican-client-java` over the JDK's own `HttpClient`, `pelican-client-pekko`
over Pekko HTTP's client, which takes the `ActorSystem` a Pekko service already
has, `pelican-client-okhttp` over OkHttp's `Call`, which takes the
`OkHttpClient` an application already built and is the one that runs on Android,
and `pelican-client-ktor` over Ktor's, which is on the [`multi-backend`](https://github.com/matthewjones372/pelican/tree/multi-backend)
branch with the Ktor server and not published at 1.0. A build carrying more
than one has to name the
transport at each client it constructs, since nothing can choose between two
providers on one classpath. The generated methods block by default, joining the
stage the transport answers with; a `suspend` surface is generated instead when
the client entry asks for one, and the choice belongs to whoever generates the
file. Retries are a decorator over a transport rather than generated code, and
are off unless somebody wraps one — but nothing here does circuit breaking or
per-call metrics, and those you still write around the transport yourself. The
client that `pelican-test` derives is explicitly narrower still,
scoped at testing, blocking, with no retries and no pooling worth the name, and
it cannot upload a binary file part because its `RequestSpec` carries a `String`
body.

**Some checks are startup checks, not compile-time ones.** This is worth saying
because the front page leads with what the compiler catches. It does not catch a
path parameter declared but absent from the path, a captured segment nothing
declares, a scope asked for that its scheme never granted, or a missing codec —
those all throw when the endpoint value or the `Api` is constructed. It does not
catch two inputs of the same type being read in the wrong order, and it lets a
handler destructure a prefix of its inputs and quietly ignore the rest. And
nothing in Kotlin's type system can say that a list of bound handlers covers a
set of declared endpoints, so `api { covers = ... }` is a runtime check too.

**Finally, the obvious one.** One author, one released version, and no issues
open — which reads as "nothing is broken" and equally as "nobody has tried it
yet". Weigh it accordingly.

---

## The API will break before 1.0

Plainly: the public API will change in breaking ways between minor versions
until 1.0. Not only in new modules or at the edges — the endpoint DSL, the
binder names, the codec interfaces and the module boundaries are all in scope,
and a `0.2.0` may require edits to code that compiled against `0.1.0` with no
deprecation period in between.

So pin an exact version, and upgrade deliberately:

```kotlin
dependencies {
    implementation("io.github.matthewjones372:pelican-core:0.1.0")
}
```

Not a range, not a `+`, and not a version resolved by a plugin you do not
control. If you use the Gradle plugin, pin that too, at the same version as the
libraries — `id("io.github.matthewjones372.pelican") version "0.1.0"` — since
its generators and the runtime read the same descriptions and are not tested
against each other across versions.

The other half of that advice costs nothing and is worth taking whatever you
decide about Pelican: pin the wire contract your callers hold, in a test, so
that a version bump which changes what you publish fails loudly rather than
reaching them. [Golden files](golden-testing.md) is how that is done here, and
`app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"` is the one-line
form of the same idea.
