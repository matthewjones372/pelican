# Roadmap

What is not built yet and the order it is worth building in. This is a
different list from [What isn't here](reference.md#what-isnt-here) in the
reference manual: that one is the deliberate refusals — the things Pelican
will not do because doing them would mean describing something it cannot
faithfully serve. This one is the things it should do and has not.

No dates. The order is an argument about value, and the argument is written
down so it can be disagreed with.

## Where the library stands

The description core is the finished part. An endpoint value drives the route,
the OpenAPI document, the test client, the generated client and the golden
files, and the layering that keeps those apart is asserted by tests rather than
maintained by habit. The unfinished parts are on either side of that core:
what happens to a request while it is being served — metrics, traces, response
rewriting — and what the caller gets.

## What has since landed

Two of the items below are partly done, and the page says so here rather than
being quietly rewritten, because the argument for the order is worth keeping
next to the result of following it.

- **Item 1** shipped as `pelican-metrics`: `Endpoint.statusFor` resolves the
  status once in core, `afterStatus` hands it to a filter, and a counter and a
  timer come out tagged from the description. OpenTelemetry is still to do.
- **Item 2** shipped as [Choosing between Pelican and the alternatives](choosing.md).
- **Item 3** shipped its first two phases: `ClientTransport` in core,
  `pelican-client-java` over the JDK's own `HttpClient`, and
  `pelican-client-ktor` over Ktor's. The Pekko adapter, the `suspend` surface
  and the retry policy are still to do. The second adapter also made the
  classpath question real rather than hypothetical: `ClientTransport.default()`
  refuses to choose between two providers, so a build carrying both names the
  transport at each client it constructs.

## 1. Metrics and traces, from the description

An endpoint already carries everything a useful metric needs a dimension for:
`method`, `pathSpec.template` (`/users/{userId}/orders`, not the expanded
path — the distinction that keeps cardinality finite), `operationId`, `tags`,
`deprecated`, and the statuses its declared failures can produce. A filter
already sees the endpoint that matched, through `Params.endpoint`, and `after`
already runs once the handler has answered with both the result and the
throwable. Almost all of the work is done and none of it is spent.

The missing piece is the response status. `after` sees what the handler
returned, not what the interpreter rendered, so a filter can infer the status
from the result's type and the endpoint's `errors` but cannot read it. That
inference is exactly the thing a metrics module should not be reimplementing
three times, once per backend.

So: make the resolved status visible to the chain, then a `pelican-metrics`
module that turns it into Micrometer meters and OpenTelemetry spans, with the
dimensions taken from the description rather than from a string the user
remembered to pass.

This is first because it is the cheapest item on the list and the one that most
changes what Pelican is for. Every competitor makes you name the operation
twice — once in the route, once in the metric. Here the name already exists.

**Touches** `pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/Filter.kt`,
the three interpreters, a new `pelican-metrics` module.
**Done when** one line in an `Api(...)` produces a request counter and a latency
timer dimensioned by method, path template, operation and status; the example
runs with it; and the module's dependency test asserts it pulls in core and a
meter API and nothing else.

## 2. A page that says when not to use Pelican

At 0.1.0 with no issues open, the thing standing between a reader and a
decision is not a missing feature. It is that nobody can tell in five minutes
how this differs from http4k's own contracts, from the Ktor OpenAPI plugins,
from springdoc-style annotations, or from tapir — which the README names but
does not compare against.

The page worth writing is the unflattering one: what each of those does better,
which projects should use them instead, and what you give up by taking a 0.1
library. Paired with a plain statement that the API will break before 1.0.

This costs no code and is second only because the first item is nearly free.

**Touches** `docs/`, the appendix table in `README.md`.
**Done when** a reader who has never seen Pelican can decide against it quickly
and for the right reason.

## 3. The client, and which HTTP library serves it

One correction to the usual framing of this gap: the generated client is not
an unpooled toy. It builds on `java.net.http.HttpClient` with a connect timeout
and a per-request timeout, and that client pools connections; the instance is a
constructor argument, so an application can hand it a configured one. The
`pelican-test` client is the blocking, test-scoped one, and is meant to stay
that way — see [What isn't here](reference.md#what-isnt-here).

The real problem is that the choice is not a choice. A service that already
runs Ktor, and already tunes one Ktor `HttpClient` engine, should not acquire a
second HTTP stack because it generated a Pelican client. The server side settled
this argument already — a description is served through a web stack you already
run — and the caller side should answer it the same way.

### The seam

`java.net.http` is not behind an abstraction in the generated code; it *is* the
generated code. `request()` returns an `HttpRequest`, bodies are
`BodyPublisher`s, responses are `HttpResponse<String>` and
`HttpResponse<InputStream>`. So this is an SPI plus a rewrite of the fixed
runtime preamble the generator emits, not a swapped field.

- **`ClientTransport` lives in core.** An interface and two holders, no library
  types, which keeps `NoThirdPartyDependenciesTest` true. Adapters —
  `pelican-client-java`, `pelican-client-ktor`, `pelican-client-pekko` — carry
  their own dependencies, exactly as the three server modules do.
- **Not `pelican-test`'s `Transport`.** That one is blocking and carries a
  `String` body deliberately, which is precisely why the test client cannot
  upload binary. A real client needs a streamed request body for a file part
  and a lazily-read response body for `ndjson`, SSE and `bytes`.
- **`CompletionStage`, not blocking and not `suspend`.** The shape cannot vary
  per adapter: the generated code is written against the interface before
  anyone picks a client, so a shape that varied would mean three generators and
  three call surfaces. Given one shape, it has to be the widest, because the
  conversion only runs one way — an asynchronous transport serves a blocking
  caller with a `join`, while a blocking transport serves an asynchronous client
  by tying up a thread per call, which is most of what a Ktor or Pekko client
  was for. `CompletionStage` is also what core already uses for the same job on
  the server side, at
  [Api.kt:109](../pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/Api.kt),
  and it is a JDK type, so core keeps its stdlib-only classpath. `suspend`
  cannot live in core for that last reason alone.

The payoff is that the generated client can offer both call shapes — blocking
and `suspend` — whatever is plugged in underneath. Which HTTP library a service
runs becomes an operational decision rather than one that changes the API its
own code is written against.

### What it makes cheap

Retries, per-call metrics and request logging are all decorators over a
transport. Once the seam exists they are small, and the caller half of item 1
arrives with them rather than as separate work.

- **A retry policy.** Nothing retries today. What is safe to retry is partly
  knowable from the description — the method, and whether the failure the server
  declared could pass on a second attempt — but the policy itself is an
  argument, defaulting to none.
- **An asynchronous surface.** The generated methods block. Both shapes fall
  out of the SPI rather than being generated twice.

Writing a connection pool, a circuit breaker and a load balancer is a different
project and is not this one. Each adapter inherits whatever its library already
does about all three.

**Touches** core (the SPI), `pelican-codegen` and the runtime preamble it
emits, three new adapter modules, `docs/generated-client.md`.
**Done when** the same generated client runs against all three adapters under
one test, blocking and `suspend` call shapes both generate, a retry policy is a
constructor argument defaulting to no retries, and each adapter module's
dependency test asserts it drags in core and one HTTP library and nothing else.

## 4. A filter that can change the response

Filters compose, run outermost-first, are narrowable with `onlyWhen`, and can
reject by throwing. What they cannot do is transform a response with types
intact: the chain carries `CompletionStage<Any?>`, so a filter that wants to
wrap a body sees `Any?` and has to cast.

Either give filters a typed view of the output, or state plainly in the
reference that response rewriting belongs in the handler and why. Both are
acceptable answers. Leaving it undecided is what is not.

**Touches** `Filter.kt`, the reference manual.

## 5. Response content negotiation

A request body can declare more than one encoding; a response cannot. One
success status carries one media type, so `json` or `xml` chosen by `Accept` is
not sayable. This is a real gap against OpenAPI rather than a refusal — an
`Output<R>` carries a single encoding today, and letting it carry several means
deciding how the handler learns which one was picked.

## 6. The cover check, at compile time

`Api(covers = ...)` catches an endpoint that was described and never bound, but
it catches it when the `Api` is constructed —
[Api.kt:254](../pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/Api.kt).
That is a test away from useless and a KSP processor away from being a compile
error. Worth doing, worth doing after the items above.

## 7. The ergonomic cliff, and example parity

`endpoint(a..f)` takes six typed inputs; past that a handler drops to the lens
form and reads the whole `Params`. More overloads are one answer and a nicer
builder is another, and neither is urgent — six covers nearly every endpoint
anyone writes. Worth knowing where the bar actually sits: tapir does not escape
this either, its `TupleArity` instances stopping at twenty-one. So the gap is
six against twenty-one rather than six against no limit at all.

Separately: the small greetings example runs on all three backends, but the
fuller orders service is wired on Pekko and http4k only. "The backend is just a
choice" is a claim the examples should demonstrate rather than assert, so the
Ktor wiring should exist too.

## 8. OpenAPI 3.2.0

The emitter writes 3.1.0 and nothing else, and the reference manual frames that
as a deliberate step forward under the heading "Moving from 3.0.3 to 3.1.0".
That framing has expired: 3.2.0 has been the current specification since
19 September 2025, and http4k's own renderer already defaults to it. Writing
3.1.0 is now neither the floor nor the ceiling, and a reader whose tooling
dictates a version rules Pelican out on it faster than on anything else here.

This is late in the list only because it was written after the rest. On cost
against value it belongs near the top: the document is emitted from one place,
and what changed between 3.1 and 3.2 is additive for what Pelican describes.
What it needs first is a survey of exactly what differs, so that emitting the
newer number is not a claim the document cannot support.

## Not on this list

Things that look like gaps and are not, with the reasoning in
[What isn't here](reference.md#what-isnt-here): writing OpenAPI 3.0, validating
credentials, `callbacks`, `anyOf`/`not`, a lenient importer, nested objects in
form bodies, more than one streamed file part. A fourth backend belongs here
too — three is already enough to prove the interpreter is not shaped by any one
of them.

## Stability

Until 1.0, expect breaking changes between minor versions. The golden-file
tests in `pelican-test-golden` exist so that a break in *your* API is loud;
they say nothing about breaks in Pelican's own. Pin an exact version.
