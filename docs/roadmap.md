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

Four of the items below are done or partly done, and the page says so here
rather than being quietly rewritten, because the argument for the order is
worth keeping next to the result of following it.

- **Item 1** shipped as `pelican-metrics`: `Endpoint.statusFor` resolves the
  status once in core, `afterStatus` hands it to a filter, and a counter and a
  timer come out tagged from the description. OpenTelemetry is still to do.
- **Item 2** shipped as [Choosing between Pelican and the alternatives](choosing.md).
- **Item 3** shipped its first phase, and then the two things that phase was
  supposed to make cheap:
  - `ClientTransport` lives in core, `pelican-client-java` sends over the JDK's
    own `HttpClient`, and `pelican-client-pekko` over Pekko HTTP's. The second
    adapter made the classpath question real rather than hypothetical:
    `ClientTransport.default()` refuses to choose between two providers, so a
    build carrying both names the transport at each client it constructs.
  - The `suspend` surface is `callStyle.set("suspending")` on a client entry —
    one call shape per generated file, the same methods either way, and
    kotlinx.coroutines on the classpath of the callers who asked for it rather
    than of everyone who generated a client. A cancelled coroutine cancels the
    exchange.
  - The retry policy is `ClientTransport.default().retrying(policy)`: a
    decorator in core, no line of it generated, and no retries at all unless
    somebody wrapped a transport in one.

  The Ktor adapter is the last of this item.
- **Item 8** shipped: `pelican-openapi` writes 3.2.0 as well as 3.1.0, and the
  survey that item asked for came back with more than a number. Two things
  Pelican describes every day — cookie parameters and streamed responses —
  turned out to be things 3.1 has no vocabulary for, and 3.2 does. The default
  stayed at 3.1.0 all the same, and the argument for that is below.

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

Both of those are done, and the estimate above was right about the shape and
half right about the cost. The retry policy is a decorator over the interface
and nothing else, exactly as predicted. The `suspend` surface did fall out of
the SPI, but "rather than being generated twice" turned out to be the wrong
target: a file carrying both shapes would double every operation's surface and
put coroutines on the classpath of callers who never asked for one, so the
generator emits one shape per file and the caller says which. See
[Blocking or suspending](reference.md#blocking-or-suspending) for the argument,
and [Retrying](reference.md#retrying-and-what-is-safe-to-retry) for the
defaults and the defence of each.

Writing a connection pool, a circuit breaker and a load balancer is a different
project and is not this one. Each adapter inherits whatever its library already
does about all three.

**Touches** core (the SPI), `pelican-codegen` and the runtime preamble it
emits, three new adapter modules, `docs/generated-client.md`.
**Done when** the same generated client runs against all three adapters under
one test, blocking and `suspend` call shapes both generate, a retry policy is a
constructor argument defaulting to no retries, and each adapter module's
dependency test asserts it drags in core and one HTTP library and nothing else.
Two of those four are done; what is left is the two adapters and the test that
runs one client against all three.

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

## 8. OpenAPI 3.2.0 — done, and what the survey found

Kept rather than deleted, because the argument this item made turned out to be
half right and the half it got wrong is the more interesting one.

The item assumed the problem was a number: that 3.1.0 was "neither the floor
nor the ceiling", and that raising it was mostly a matter of not claiming more
than the document could support. The survey found two places where the document
Pelican was already emitting was *wrong*, and had been wrong since 3.1 was the
only thing it wrote.

- **Cookie parameters.** Pelican joins cookie pairs with `"; "` and passes the
  values through unescaped. Both revisions assume `style: "form"` at
  `in: "cookie"`, and `form` means percent-encoded values joined by `&` — 3.2's
  Appendix D says in as many words that it "uses the wrong delimiter for
  cookies". 3.2 added `style: "cookie"` for what Pelican actually does. 3.1 has
  no way to say it.
- **Streamed responses.** `application/x-ndjson` and `text/event-stream` are
  sequential media types, where 3.2 is explicit that `schema` describes the
  whole stream and `itemSchema` describes one frame. Pelican knows the frame,
  and had nowhere but `schema` to put it. Worse for `sse<T>`: 3.2 says an item
  of an event stream is the *parsed event*, so naming the payload there
  described a stream nobody sends.

So the newer number was not a claim to be careful about making. It was the only
number under which two of Pelican's own claims are true.

**The default did not move, and that is the part worth disagreeing with.** A
consumer reading 3.1 is promised nothing about a document saying 3.2 — the
specification's versioning rule only covers a `major.minor` feature set — and
swagger-parser, which `openapi-generator` and most of the JVM ecosystem stands
on, hands back `null` for a 3.2.0 document with an empty message list. No
error, no warning. Defaulting to a document that the dominant parser silently
turns into nothing would be a worse failure than being a revision behind, so
3.1.0 is what a caller who does not choose still gets, and `OpenApiVersion` is
one argument away. A test asserts that swagger-parser still cannot read 3.2, so
the reason for the default expires loudly rather than quietly.

**What is left.** Move the default to 3.2.0 when that test starts failing. And
the 3.2 fields nothing in an endpoint description answers — `$self`,
`Server.name`, Tag Objects with `parent` and `kind`, `additionalOperations`,
the `deviceAuthorization` flow — are listed in
[What isn't here](reference.md#what-isnt-here) rather than here, because each
of them needs something added to the *description* first, and that is a
different argument from this one.

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
