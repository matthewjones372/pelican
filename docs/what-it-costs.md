# What it costs

Linked from the [README](../README.md); kept here because a benchmark is
detail, and the front page only has to say that it was measured.

A description has to be interpreted, and that is not free. What it costs is
measured rather than argued about, by a JMH harness in the
[`benchmarks`](../benchmarks) module: the same endpoint written twice, once
described with Pelican and interpreted onto a backend, once written directly
against that backend's own routing. Both decode a path parameter and an
optional query parameter with a default, and both encode the same object with
the same Jackson mapper, so what is left in the difference is the interpreter.

## How these numbers were taken

```bash
./gradlew :benchmarks:jmh                       # everything: six minutes
./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 Http4k"   # one file, one fork
./gradlew :benchmarks:jmh -PbenchmarkArgs="-prof jfr"     # a flight recording per fork
```

JMH 1.37, average time in nanoseconds per request, three forks, five
one-second warmup iterations and five one-second measured ones per fork. Errors
are JMH's 99.9% confidence interval over the fifteen measured iterations.
Allocation is `-prof gc`'s `gc.alloc.rate.norm`, which is on by default,
counts every thread rather than only the one calling, and — unlike a timing —
is the same number every run.

Measured on an Apple M3 (four performance and four efficiency cores), 24GB,
macOS 26.5.2, on Temurin 21.0.9+10, which is the toolchain the rest of the
build compiles against. The whole run took 6m 04s.

Two things about the harness are worth knowing before reading the table. Each
fork runs with a pinned 512MB heap: these handlers allocate three or four
gigabytes a second and keep none of it, and with the default ceiling of a
quarter of the machine's RAM the young generation grows until its pages stop
being resident. On a workstation with an IDE open that turns the benchmark into
a measurement of paging, and it reported 491µs for a 14µs operation before the
heap was pinned. And every benchmark returns its response, which is how JMH
knows the work was not dead code; a handler whose result went unused could be
optimised away entirely, and the fastest possible route is the one that does
nothing.

Nothing wires the run into `check` or `build`, so it happens only when asked
for. It is also not a test, which is how the coverage agent stays out of it:
instrumentation rewrites bytecode, and it rewrites more of Pelican than of a
hand-written route — precisely the comparison being made — so a measurement
taken through the agent reports the agent. That cost an afternoon to notice
when the benchmarks were tests. JMH forks its own JVMs and there is no test
task left for an agent to attach to.

Everything below is in-memory. No socket, no connection handling, no OS
scheduling. That flatters both sides equally and isolates the layer under test.

## http4k

One endpoint, `GET /items/{itemId}?limit=`, answering JSON.

| | per request | allocated |
|---|---|---|
| **Pelican, interpreted onto http4k** | **1215 ± 23ns** | **5643 ± 29B** |
| http4k written the ordinary way | 1581 ± 365ns | 5725 ± 86B |
| http4k with the response hand-tuned | 1140 ± 38ns | 5384 ± 25B |

Two baselines, because one would flatter. An http4k `Response` is immutable, so
the idiomatic `Response(status).header(...).body(...)` copies it at each step —
three responses and two header lists where one of each will do. That chain is
worth 341 bytes a request, and building the response in one construction is a
trick anyone can use, so the second row is the honest comparison against
somebody who has done the same thing by hand. Against it the interpreter costs
**75ns and 259 bytes**; against the idiomatic route it is 366ns and 83 bytes
*ahead*, which is a statement about the copies rather than about Pelican.

The wide error on the middle row is real and is the harness earning its keep.
That benchmark is bimodal at the fork level: one JVM settled at 2040ns and two
at 1350ns, each of them flat to within one per cent across five iterations. It
is a JIT decision that goes one of two ways per process, which is exactly the
thing a single-JVM loop cannot see and forks exist to expose. Read the row as
"1350 or 2040, depending on the JVM", not as a number with a wobble.

## What matching costs when there is more than one endpoint

Every table above uses an API of **one** endpoint, which measures decoding and
rendering and says nothing about the thing that varies most between services.
Pekko reduces its routes with `Directives.concat` and http4k's `routes(...)`
tries them in order, so both are an ordered scan. The endpoint under test is
declared last — the worst case a scan has, and the one a service acquires by
adding endpoints over time.

`./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 RoutingScale"` — a class of its
own, so the six-minute run above is unchanged.

| endpoints | **Pelican on http4k** | http4k routes, by hand | **Pelican on Pekko** |
|---|---|---|---|
| 1 | **220 ± 28ns** | 1725 ± 667ns | **713 ± 23ns** |
| 50 | **226 ± 21ns** | 37,920 ± 1095ns | **701 ± 122ns** |
| 200 | **223 ± 9ns** | 149,170 ± 7720ns | **645 ± 28ns** |

Both Pelican columns are flat, and the hand-written one is not. That is the
whole of the difference between holding descriptions and holding handlers: given
a list of opaque handlers a router can only try them in turn, and given the path
templates they can be walked into a trie once and matched by segment afterwards.

These are whole requests — a path parameter decoded, the handler run, the
response encoded — not a dispatch microbenchmark. At two hundred endpoints
http4k is **669 times** what the same service cost written by hand, and Pekko is
two orders of magnitude over its own control.

The single-endpoint row is worth reading twice: **220ns against 1725ns**, so this
is not only a large-service story. Matching one template through http4k's router
costs more than walking a trie that happens to have one entry in it.

What replaced what: an earlier attempt grouped the routes under a prefix using
http4k's own nesting, and measured 155µs at two hundred — an outer scan is still
a scan. Only owning the dispatch moves the number.

Ktor dispatches through the same index — `Route.pelican` installs one route per
method the descriptions use and the trie decides which endpoint answers — and is
not yet measured here.

## An endpoint with nothing to decode

`GET /ping`, answering text.

| | per request | allocated |
|---|---|---|
| **Pelican** | **392 ± 59ns** | **2232B** |
| http4k | 359 ± 26ns | 2069 ± 17B |

34ns and 163 bytes. The interpreter still has to match the description and
choose the output shape even when there is nothing to decode, and this is what
that costs on its own.

## Pekko

The same endpoint again, and three ways of writing it by hand rather than one,
because the first number alone leaves the wrong impression. Every route here is
sealed with `Route.function(system)`, so none of them pays for a socket — the
same choice the in-memory transport in `pelican-test-pekko` makes.

| | per request | allocated |
|---|---|---|
| **Pelican, interpreted onto Pekko** | **742 ± 27ns** | **3320 ± 26B** |
| Pekko, idiomatic (`PathMatchers` + `parameterOptional`) | 953 ± 21ns | 4880 ± 75B |
| Pekko, `PathMatchers` with the query read off the request | 723 ± 38ns | 3880B |
| Pekko, one directive and both read off the request | 612 ± 12ns | 2472B |

Pelican beating an idiomatic Pekko route by 211ns and 1.5KB is not a claim that
the library is faster than the server it runs on. It is a claim about
`parameterOptional`, which the second and third rows price at 230ns and a
kilobyte on its own. Take that one directive out and a hand-written route draws
level — 19ns apart, inside the error either way. Write the whole thing as a
single `extractRequest` that matches the path and reads the query itself, which
is what the interpreter does, and the hand-written version is **131ns and 848
bytes** ahead, where you would expect it to be.

## Reading a body

The GET cases above never materialise anything: the request has no entity and
the response is strict. `POST /items` with a JSON body is where Pekko's streams
actually turn, because `toStrict` materialises.

| | per request | allocated |
|---|---|---|
| **Pelican** | **12.0 ± 0.8µs** | **5405 ± 39B** |
| Pekko, hand-written `extractStrictEntity` | 14.8 ± 0.3µs | 12257 ± 1266B |

Two orders of magnitude more than the routing above, and the interpreter is
2.8µs and 6.8KB *under* the hand-written route, because it asks Pekko for the
cheaper of two reads when the request declared its length. See
[`ChunkedBodyLimitTest`](../pelican-pekko/src/test/kotlin/io/github/matthewjones372/pelican/pekko/ChunkedBodyLimitTest.kt)
for why the other read still exists.

## What the old harness got wrong

These numbers replace a set taken by a JUnit test that looped and timed in one
JVM, with no warmup control, no forks, nothing stopping the JIT deleting work
whose result went unused, and allocation measured by differencing a thread
counter. Most of what it said survives; two things do not, and are worth
naming because they are the failure modes that harness had.

**"Free on an endpoint with no inputs"** does not survive. The old harness
reported 0ns for the ping case. Measured in its own forked JVM it is 34ns and
163 bytes — small, but not nothing, and a number that only reaches zero when
two handlers are timed in the same JVM off the same warmed profile.

**"0–35ns against http4k written the ordinary way"** does not survive either,
and not in the direction you would expect: the interpreted route is 366ns
*ahead* of the idiomatic one, not marginally behind it. The old figure came
from timing both handlers in one process, where the JIT had seen every call
site and neither shape got the compilation it would have had on its own.

The allocation figures barely moved, which is the point of measuring them: 5643
and 5725 bytes for that pair here, against 5808 and 5672 from the old harness.
Differencing a counter was the one thing it did honestly, and even that only
counted the calling thread — on Pekko, where the numbers are now whole-JVM,
they are larger and more nearly true.

Everything else the old document claimed is still standing, and by wider
margins: Pelican is still cheaper than an idiomatic Pekko route, still level
with one that drops `parameterOptional`, still a hundred-odd nanoseconds behind
one written as a single directive, and still under a hand-written
`extractStrictEntity` on a POST.

## For scale

A loopback socket round trip is tens of microseconds and a database call is
milliseconds, so on a realistic endpoint this is a fraction of a percent. It
does not grow with the endpoint either — the cost is the per-request bag of
decoded values, the `Params` around it and the response, and none of those
scale with how many inputs are declared or how big the payload is. An endpoint
that decodes nothing pays much the same as one that decodes two, which on the
smaller absolute numbers of an empty endpoint reads as a larger ratio.

One caution about reproducing any of this. A benchmark is only as quiet as the
machine under it, and these were taken on an otherwise idle one. The same
command on the same laptop, with an IDE indexing a fresh module and eight
gigabytes of swap in use, reported two to five times these figures with error
bars to match — one row came back at thirty-five times. If a score arrives with
an error anywhere near its own size, that is the machine talking and not the
library, and the run is worth repeating rather than reading.
