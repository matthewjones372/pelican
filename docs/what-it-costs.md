# What it costs

Linked from the [README](../README.md).

A Pelican endpoint is a description, and a backend module interprets it on
every request: it matches the path against the descriptions, decodes the
declared inputs, calls the handler and renders the declared response. This
page measures what that interpretation costs, by running the same endpoint
twice on the same backend: once described with Pelican, once written directly
against the backend's own routing. Both sides decode the same inputs and encode
the same object with the same Jackson mapper, so the difference is the
interpreter.

The measurements are taken with JMH in the [`benchmarks`](../benchmarks)
module, plus one end-to-end run over a socket. They are numbers from one
machine at a time, and the answer differs by backend and by where the handler
runs.

## Contents

- [The short answer](#the-short-answer)
- [How the measurements are taken](#how-the-measurements-are-taken)
- [Pekko, in memory](#pekko-in-memory)
- [Jetty, in memory](#jetty-in-memory)
- [Pekko and Jetty over a socket](#pekko-and-jetty-over-a-socket)
- [Routing as the number of endpoints grows](#routing-as-the-number-of-endpoints-grows)
- [Decoding a body: Jackson and kotlinx](#decoding-a-body-jackson-and-kotlinx)
- [Streaming and framing](#streaming-and-framing)
- [Reproducing these numbers](#reproducing-these-numbers)

## The short answer

- **On Pekko, with the handler on the dispatcher**, Pelican is faster than an
  idiomatic hand-written route, level with one that avoids
  `parameterOptional`, and **356ns and 784 bytes** behind the fastest route
  written by hand. That last figure is the interpreter's cost on this endpoint.
- **On Jetty, with the handler on the Jetty thread**, Pelican and the
  hand-written handler overlap within their error on both a GET and a POST, so
  the interpreter's time is below what this benchmark can resolve. It
  allocates **1.1 to 1.3KB** more per request.
- **Running a synchronous handler on a virtual thread**, which is the default on
  both backends, is the largest cost measured. On Pekko's in-memory GET it is
  about 50 times the dispatcher figure, and the reason has not been
  investigated. On Jetty it adds about 9.5KB of allocation per request. A
  handler that never blocks can opt out with `Handlers.onDispatcher`.
- **On a JSON POST**, Pekko's body handling costs far more than routing does,
  and Pelican on the dispatcher cannot be told apart from the hand-written
  route in time. It allocates 2.0KB more.
- **Routing stays flat** as an API grows from 1 to 200 endpoints, because
  Pelican indexes the path templates instead of trying routes in turn. That
  table was taken on an older machine and an older build and has not been
  retaken.

| Backend | Measurement | Baseline | Principal result | Detail |
|---|---|---|---|---|
| Pekko | in memory, `GET`, JMH | fastest hand-written route | +356ns, +784B | [Pekko](#pekko-in-memory) |
| Pekko | in memory, `GET`, JMH | idiomatic hand-written route | 375ns faster, 1.6KB less | [Pekko](#pekko-in-memory) |
| Pekko | in memory, JSON `POST`, JMH | hand-written `extractStrictEntity` | time within error, +2.0KB | [Pekko](#reading-a-body-on-pekko) |
| Jetty | `LocalConnector`, `GET` and `POST`, JMH | hand-written Jetty core handler | time within error, +1.1 to 1.3KB | [Jetty](#jetty-in-memory) |
| Pekko, Jetty | loopback socket, open-model load | each other, both Pelican | not an overhead measurement; see the section | [Over a socket](#pekko-and-jetty-over-a-socket) |
| Pekko | in memory, 1 to 200 endpoints, JMH | none comparable on `main` | flat at 645 to 713ns | [Routing](#routing-as-the-number-of-endpoints-grows) |

Rows from different sections are not comparable with each other. The Pekko
benchmark hands the route a request that is already an object; the Jetty one
parses and generates HTTP bytes; the socket run measures two whole servers
under load; the routing table comes from a different machine. Each section
says what its own baseline is.

What this page does **not** establish:

- That Pelican is free. It costs hundreds of nanoseconds and some kilobytes on
  the endpoints measured here, and more where a handler is moved onto a
  virtual thread.
- How a service will perform in production. The in-memory benchmarks exclude
  the network on purpose, and the socket run puts its load generator on the
  same four cores as the servers.
- Anything about endpoints shaped very differently from the ones measured: one
  path parameter and one optional query parameter, and a small JSON body.

For scale: the interpreter's own cost on the dispatcher is a few hundred
nanoseconds. Median request latency in the
[socket run](#pekko-and-jetty-over-a-socket), at the rates both servers
sustained, was 283µs to 1.4ms, and a database call typically takes
milliseconds. At that scale it is a small fraction
of a request. The virtual-thread handoff measured on Pekko, about 77µs in
memory, is not small at that scale, which is why it is called out above. The
per-request work is the bag of decoded values, the `Params` around it and the
response. None of the benchmarks here vary the number of declared inputs, so
how the cost changes with them is not measured.

## How the measurements are taken

### The harness

Each benchmark is a JMH class in `benchmarks/src/main/kotlin`. The `jmh` task
runs them with JMH 1.37, reporting average time per operation. Unless a
section says otherwise, every benchmark uses the same settings, declared on
the class:

- **Three forks**, each a fresh JVM. A JIT decision can go one of two ways per
  process, and only separate JVMs show it: a benchmark can settle at one speed
  in one fork and a markedly different one in another, each flat across its
  iterations. A single-JVM timing loop cannot see that, and one that times two
  handlers in the same JVM, off the same warmed profile, can report a
  difference of zero that separate forks do not reproduce.
- **Five one-second warmup iterations and five one-second measured
  iterations** per fork, so fifteen measured iterations in all.
- **Errors are JMH's 99.9% confidence interval** over those fifteen
  iterations. Where two rows' intervals overlap, this page says the
  difference is not resolved rather than reporting the gap between the means.
- **Allocation is `-prof gc`'s `gc.alloc.rate.norm`**, bytes per operation,
  which the task always passes. It counts every thread rather than only the
  caller, and unlike time it barely varies from run to run, so it is often the
  steadier of the two results.
- **A pinned 512MB heap** per fork. These handlers allocate three or four
  gigabytes a second and keep none of it. With the default heap ceiling, a
  quarter of the machine's RAM, the young generation grew until its pages
  stopped being resident, and on a workstation with an IDE open the benchmark
  reported 491µs for a 14µs operation before the heap was pinned.
- **Every benchmark returns its response**, which is how JMH knows the work was
  not dead code. A handler whose result went unused could be optimised away.

In the charts on this page, a dot is the mean and its whiskers are the
99.9% interval, so where two rows' whiskers overlap the difference is not
resolved. Allocation is drawn as a bar, because its error is a few bytes.
Each chart is drawn from the table above it by
[`what-it-costs.py`](assets/what-it-costs.py), and the tables remain the
exact figures.

The benchmarks are not tests and nothing runs them as part of `check` or
`build`. That keeps the coverage agent out of them: instrumentation rewrites
more of Pelican than of a hand-written route, which is exactly the comparison
being made, so a measurement taken through the agent reports the agent.

The in-memory benchmarks have no socket, no connection handling and no OS
scheduling of network I/O. That flatters both sides equally and isolates the
layer under test; the [socket run](#pekko-and-jetty-over-a-socket) is the
complement.

### The machines

Two machines appear on this page, and their numbers are never compared with
each other.

- **The cloud instance**, which took the Pekko, Jetty, decode and socket
  measurements, in October 2026. An Intel Xeon at 2.80GHz,
  4 cores (one thread each), 16GB, Ubuntu 24.04.5 LTS, Linux 6.18.44 in a
  Firecracker microVM. The benchmarks ran on JDK 25.0.4.1, the toolchain
  `benchmarks/build.gradle.kts` names; Gradle itself ran on OpenJDK 21.0.12.
  Pekko 1.7.1, Pekko HTTP 1.4.1, Jetty 12.1.13.
- **An Apple M3** (four performance and four efficiency cores), 24GB, macOS
  26.5.2, on Temurin 21.0.9+10, which took the routing-scale table in August
  2026. It has not been retaken.

Every current table ran on JDK 25, where `Handlers.default` is virtual
threads, so "the default" on this page means virtual threads. Below JDK 24 the
default is a pool of platform threads, which none of these benchmarks
measures.

### A quiet machine matters

A benchmark is only as quiet as the machine under it. The M3 tables were taken
on an otherwise idle machine. The cloud instance had nothing else running, but
its load average was about 2 during the JMH runs (JMH's own threads) and much
higher during and just after the socket run, where the driver is part of the
load. Each section gives the load average it saw.

The same command on the same laptop, with an IDE indexing a fresh module and
eight gigabytes of swap in use, once reported two to five times these figures
with error bars to match, and one row at thirty-five times. If a result comes
back with an error close to its own size, that is the machine talking, and the
run is worth repeating rather than reading.

## Pekko, in memory

**What is measured.** One endpoint, `GET /items/{itemId}?limit=`, answering
JSON, plus a JSON `POST /items`. Every route is sealed and called with
`Route.function(system)`, so none of them pays for a socket; this is the same
choice the in-memory transport in `pelican-test-pekko` makes.

**The comparison.** Three hand-written Pekko routes for the GET, because one
alone gives the wrong impression:

- *idiomatic*: `PathMatchers` and `parameterOptional`, the way the Pekko
  documentation writes it;
- *matchers only*: `PathMatchers`, with the query read off the request;
- *one directive*: a single `extractRequest` that matches the path and reads
  the query itself, which is what Pelican's interpreter does.

Pelican is measured twice: with `toRoute(system, Handlers.onDispatcher)`, which
runs the synchronous handler on Pekko's dispatcher as the hand-written routes
do, and on a virtual thread, which is what `toRoute` does by default.

`./gradlew :benchmarks:jmh -PbenchmarkArgs="PekkoOverhead"`, on the cloud
instance, with the shared settings. The run took about six minutes. The load
average was 9.85 when it started, left over from the socket run before it, and
2.06 when it ended.

| | per request | allocated |
|---|---|---|
| **Pelican, handler on the dispatcher** | **1584 ± 68ns** | **3185 ± 64B** |
| Pelican, handler on a virtual thread (the default) | 78,499 ± 3352ns | 14,028 ± 116B |
| Pekko, idiomatic (`PathMatchers` + `parameterOptional`) | 1959 ± 99ns | 4841 ± 3B |
| Pekko, `PathMatchers` with the query read off the request | 1492 ± 96ns | 3801 ± 2B |
| Pekko, one directive and both read off the request | 1228 ± 54ns | 2401 ± 2B |

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/what-it-costs/pekko-get-dark.svg">
  <img src="assets/what-it-costs/pekko-get-light.svg" alt="Pekko in-memory GET: time and allocation per request for Pelican on the dispatcher and three hand-written routes">
</picture>

The virtual-thread row is left out of the chart: at 78µs it is fifty times
the others and would flatten them.

**What it shows.**

- Against the idiomatic route, Pelican on the dispatcher is **375ns and 1.6KB
  cheaper**. That says more about `parameterOptional` than about Pelican: the
  idiomatic row and the one below it put that one directive at about 470ns and
  a kilobyte.
- Against the matchers-only route, the two are 92ns apart, inside the sum of
  their errors, so the time difference is not resolved. Pelican allocates 616
  bytes less.
- Against the single-directive route, the hand-written version is **356ns and
  784 bytes ahead**, clear of both errors. This is the interpreter's cost on
  this endpoint.
- On a virtual thread the same request takes about 50 times as long and
  allocates 10.8KB more.

**Limitations.**

- The virtual-thread gap is far larger than the extra allocation would
  explain, and I have not looked into where the time goes. This machine has
  four cores to hand a request between; a machine with more may show a smaller
  gap. Treat the figure as measured but unexplained.
- The run started on a machine still busy from the socket benchmark. The
  intervals are tight, but a retake on an idle machine would be the cleaner
  number.
- One endpoint shape. Endpoints with more inputs, or larger responses, are not
  measured.

### Reading a body on Pekko

The GET above never materialises a stream: the request has no entity and the
response is strict. A JSON `POST /items` is where Pekko's streams actually run,
because `toStrict` materialises one. The hand-written baseline uses
`extractStrictEntity`. Same command, machine and run as the GET.

| | per request | allocated |
|---|---|---|
| **Pelican, handler on the dispatcher** | **79.8 ± 4.5µs** | **14,247 ± 106B** |
| Pelican, handler on a virtual thread (the default) | 105.1 ± 5.5µs | 15,699 ± 150B |
| Pekko, hand-written `extractStrictEntity` | 97.5 ± 31.3µs | 12,234 ± 352B |

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/what-it-costs/pekko-post-dark.svg">
  <img src="assets/what-it-costs/pekko-post-light.svg" alt="Pekko in-memory JSON POST: time and allocation per request for Pelican on the dispatcher, Pelican on a virtual thread, and the hand-written route">
</picture>

**What it shows.** A body costs two orders of magnitude more than routing does
on this backend. Pelican on the dispatcher allocates 2.0KB more than the
hand-written route, and its time cannot be told apart from it: the
hand-written row's error of 31µs covers the difference, so the lower mean is
not evidence that Pelican is faster. Pelican on a virtual thread is clearly
slower than Pelican on the dispatcher, by about 25µs; against the hand-written
route it overlaps.

**Limitations.** The hand-written row's wide interval makes this the least
precise comparison on the page. The interpreter keeps a second way of reading a
body for chunked uploads, which this benchmark does not exercise; see
[`ChunkedBodyLimitTest`](../backends/pekko/pelican-pekko/src/test/kotlin/io/github/matthewjones372/pelican/pekko/ChunkedBodyLimitTest.kt)
for why it exists.

## Jetty, in memory

**What is measured.** The same GET and the same JSON POST, served by
`toHandler` on a started Jetty `Server` with `PELICAN_URI_COMPLIANCE`, with
every request sent through a `LocalConnector`.

**The comparison.** A `Handler.Abstract` written against Jetty 12 core that
matches the path, reads the query with `Request.extractQueryParameters`, reads
the body with `Content.Source.asByteArrayAsync` and writes the same Jackson
bytes, on its own started `Server`. Pelican is measured with the handler on the
Jetty thread (`Handlers.onDispatcher`) and on a virtual thread, the default.

**Why these numbers do not compare with Pekko's.** A `LocalConnector` has no
socket, but it is a real connector: each request arrives as bytes, is parsed as
HTTP/1.1 on Jetty's thread pool, and its response is generated back into bytes
that the caller waits for. Pekko's `Route.function` does none of that: it takes
an `HttpRequest` that is already an object and returns one. The Jetty rows
include a parse, a generate and two thread handoffs per request. They say what
Pelican costs on Jetty against Jetty by hand, and nothing about which server is
faster.

`./gradlew :benchmarks:jmh -PbenchmarkArgs="JettyOverhead DecodeBenchmark"`,
on the cloud instance, with the shared settings. The run took about ten
minutes, with a load average of 2.06 at the start and 2.37 at the end.

| | per request | allocated |
|---|---|---|
| **Pelican on Jetty, handler on the Jetty thread** | **117 ± 14µs** | **17,330 ± 78B** |
| Pelican on Jetty, handler on a virtual thread (the default) | 130 ± 13µs | 26,883 ± 265B |
| Jetty core, hand-written | 122 ± 13µs | 16,047 ± 69B |
| **Pelican, `POST /items`, on the Jetty thread** | **123 ± 13µs** | **18,158 ± 137B** |
| Pelican, `POST /items`, on a virtual thread | 146 ± 10µs | 27,635 ± 175B |
| Jetty core, hand-written `POST /items` | 120 ± 17µs | 17,076 ± 86B |

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/what-it-costs/jetty-dark.svg">
  <img src="assets/what-it-costs/jetty-light.svg" alt="Jetty through a LocalConnector, GET and POST: time and allocation per request for Pelican on the Jetty thread, Pelican on a virtual thread, and a hand-written Jetty core handler">
</picture>

**What it shows.**

- On both requests, Pelican on the Jetty thread and the hand-written handler
  overlap within their error. Most of each figure is the connector's thread
  handoffs, which both pay alike, so the interpreter's own time is below what
  this benchmark can resolve.
- Allocation is the steadier result. Pelican on the Jetty thread allocates
  **1283 bytes** more than Jetty by hand on the GET and **1082 bytes** more on
  the POST.
- A virtual thread adds about **9.5KB** a request on both. On the POST it is
  also the one row whose time sits above the others: about 26µs over the
  hand-written handler and 23µs over Pelican on the Jetty thread, at the edge
  of the errors. On the GET its time overlaps.

**Limitations.** With most of the time spent in the connector, a regression in
the interpreter of a few hundred nanoseconds would not show here. A benchmark
that calls Jetty's handler without the connector would be needed to resolve
the time difference, and none exists yet.

## Pekko and Jetty over a socket

**What is measured.** Two whole servers under load: Pelican on Pekko and
Pelican on Jetty, each started through its own `start()` with its defaults,
each in a JVM of its own with a 1GB heap, driven in turn over loopback with the
same GET and JSON POST as the tables above, alternating. The defaults matter:
synchronous handlers run on a virtual thread per request on both. Jetty uses
its `QueuedThreadPool` of 200 with virtual threads for handlers; Pekko uses its
default dispatcher.

**The comparison.** The two servers against each other. **Both are Pelican,
so this is not a measurement of the interpreter's overhead.** It answers the
question the in-memory tables cannot: with HTTP parsing, sockets and
scheduling included on both, how do the two backends behave under a rising
request rate?

**The driver.** The load is open-model: requests leave on a fixed schedule
whether or not earlier ones have answered, and each request's latency is
measured from when it should have left, so a stall shows in every request
queued behind it. The driver runs on Jetty's `HttpClient` with at most 256
requests in flight; the JDK's client was tried first and fell behind its own
schedule at 20,000 to 40,000 requests a second, at which point it was
measuring itself. Each server is warmed for 30 seconds at 5,000 requests a
second, then each rate runs for 5 seconds unrecorded and 15 recorded. A server
stops keeping up at the first rate whose p99 reaches 100ms, that loses more
than 0.1% of its requests, or whose senders fall two seconds behind.

`./gradlew :benchmarks:wire` on the cloud instance, taking 5m 58s. **The
driver and both servers shared the same four cores**, so each ceiling below
belongs to the pair of driver and server, not to the server alone. The machine
was idle at the start (load average 0.25 one minute after boot). The load
average was 1.70 before Pekko's warmup and 14.17 after its last rate; Jetty
started at 13.28, most of which is Pekko's run because the average lags, and
ended at 10.70.

| server | req/s | p50 | p99 | p99.9 | max | requests | errors | kept up |
|---|---|---|---|---|---|---|---|---|
| pekko | 1000 | 1.0ms | 6.7ms | 50.3ms | 64.1ms | 15000 | 0 | yes |
| pekko | 5000 | 663µs | 7.0ms | 22.9ms | 31.8ms | 75000 | 0 | yes |
| pekko | 10000 | 807µs | 8.1ms | 29.2ms | 40.2ms | 150000 | 0 | yes |
| pekko | 20000 | 1.4ms | 21.9ms | 45.7ms | 76.3ms | 300000 | 0 | yes |
| pekko | 30000 | 1124.1ms | 1908.4ms | 1950.4ms | 2000.7ms | 338323 | 0 | no |
| jetty | 1000 | 442µs | 2.3ms | 9.7ms | 21.0ms | 15000 | 0 | yes |
| jetty | 5000 | 283µs | 1.3ms | 6.1ms | 11.3ms | 75000 | 0 | yes |
| jetty | 10000 | 324µs | 1.6ms | 8.3ms | 14.8ms | 149998 | 0 | yes |
| jetty | 20000 | 482µs | 14.1ms | 40.7ms | 62.7ms | 299999 | 0 | yes |
| jetty | 30000 | 1.2ms | 29.6ms | 53.7ms | 85.9ms | 450000 | 0 | yes |
| jetty | 40000 | 110.7ms | 825.8ms | 846.2ms | 862.5ms | 600000 | 0 | no |

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/what-it-costs/socket-dark.svg">
  <img src="assets/what-it-costs/socket-light.svg" alt="Median and p99 latency against offered request rate for Pelican on Pekko and Pelican on Jetty, on a log scale, with the rates each did not keep up with drawn hollow">
</picture>

Both lines are Pelican, so the chart compares the backends, not Pelican with
hand-written code. The latency axis is logarithmic.

`requests` counts the recorded requests over 15 seconds. Pekko at 30,000 sent
338,323 of the 450,000 it was due to, because its senders fell more than two
seconds behind schedule, so that step stopped early.

**What it shows.**

- On this machine, with this driver, Pekko's last rate that kept up was 20,000
  requests a second and Jetty's was 30,000. Both failed by the senders falling
  behind, not by errors.
- At the rates both sustained, Jetty's latencies were lower. From 1,000 to
  10,000 requests a second, p99 was 1.3 to 2.3ms on Jetty against 6.7 to 8.1ms
  on Pekko, p99.9 was 6 to 10ms against 23 to 50ms, and the median at 5,000
  and 10,000 was 283 to 324µs against 663 to 807µs.
- At 20,000 the gap narrows (p99 14.1ms against 21.9ms, p99.9 40.7ms against
  45.7ms): both are close to their limit on four shared cores.

**Limitations.**

- This is one run per server, in a fixed order with Pekko first. It is no
  evidence about run-to-run variation, and says nothing about whether a second
  run would put the ceilings in the same place.
- The driver competes with the server for the same four cores, and the load
  average shows it. A ceiling here is not a server's capacity, and neither
  figure is a production throughput.
- Both servers' 1,000 requests a second row has a higher p99.9 than their
  5,000 row. That has not been explained; the first step after the warmup may
  still be warming.
- Both servers run handlers on virtual threads, the default. Given what the
  in-memory Pekko table shows for virtual threads, some of Pekko's latency may
  be that handoff rather than Pekko itself, but this run does not separate the
  two.

### Measuring a change

`-PwireAb=<ref>` compares two builds on one machine. The current checkout is
A. `<ref>` is checked out as a worktree under `benchmarks/build/wire-ab`, this
checkout's `WireServer.kt` is copied into it so that only the libraries
differ, and it is built by its own Gradle as B. Jetty from each then climbs
the same ladder in turn, A then B, for three rounds (ABABAB), and
`benchmarks/build/wire-ab.md` gives, per rate and percentile, each side's
median and range over its rounds, B against A, and whether the two ranges are
disjoint. Overlapping ranges mean the difference is inside run-to-run noise.

`-PwireScenario=stream` swaps the GET and POST for two streamed responses of
100 frames of about 40 bytes, one NDJSON and one SSE, alternating, timed to
the last byte and counted in responses a second. `both` runs each scenario in
turn.

```bash
git fetch origin
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=both  # on main: the noise, about 35 to 45 minutes
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=rest  # on a branch: the JSON GET and POST, about 17 minutes
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=stream  # on a branch: the streams, about 15 to 25 minutes
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireSmoke  # one round, one short step: checks the setup in a minute
```

`-PwireRounds=<n>` changes the three rounds and `-PwireAbPekko` adds Pekko.
The first A/B run also builds the baseline, a few minutes more.

**Main against main.** Placeholder: the noise table from
`./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=both` run on
`main`, on the cloud instance, goes here. Until it does, no A/B difference on
this page can be called larger than the noise.

## Routing as the number of endpoints grows

**What is measured.** Every other table uses an API of one endpoint, which
measures decoding and rendering and says nothing about matching a request
against many endpoints. `RoutingScaleBenchmark` builds APIs of 1, 50 and 200
endpoints and requests the one declared **last**, the worst case for a router
that tries routes in order, and the one a service acquires by adding endpoints
over time. Each request is a whole request: a path parameter decoded, the
handler run, the response encoded.

**The comparison.** Given a list of opaque handlers, a router can only try them
in turn. Given path templates, Pelican builds a trie once and matches a
request by its segments. Registered with Pekko directly, routes are reduced
with `Directives.concat` into an ordered scan, and the benchmark includes that
hand-written control.

`./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 RoutingScale"`, on the
Apple M3, in August 2026.

| endpoints | **Pelican on Pekko** |
|---|---|
| 1 | **713 ± 23ns** |
| 50 | **701 ± 122ns** |
| 200 | **645 ± 28ns** |

**What it shows.** The cost does not grow with the number of endpoints. For
contrast, when Pekko's interpreter registered one route per endpoint and let
`Directives.concat` scan them, the same benchmark measured about 150µs a
request at 200 endpoints. That figure is from the same machine but an older
build, and there is no comparable hand-written control on this page, so read
it as the reason the index exists rather than as a measured comparison.

**Limitations.**

- The hand-written Pekko control is in the benchmark but its numbers are not
  reported here, because it is not comparable: it runs each request through a
  materialised flow, which cost about 23µs on its own when last measured and
  swamps the small end. Pelican's row calls the sealed route directly.
- The decoys are `/resource{n}/{id}`, so every endpoint has a distinct first
  path segment. That is the shape most flattering to an index and least
  flattering to a scan. A service whose endpoints all sit under one prefix,
  such as `/orders`, is not measured.
- Taken on the M3 against the August 2026 interpreter, with `-f 1` (one fork).
  It has not been retaken on the cloud instance, so it cannot be read against
  the Pekko table above. Jetty dispatches through the same index, and is not
  measured here.

## Decoding a body: Jackson and kotlinx

**What is measured.** `DecodeBenchmark` decodes one JSON body of each size with
no route and no server, so only the codec is measured.

**The comparison.** Two routes into each library. "Via String" decodes the
bytes to a `String` first, which is what a codec's `decodeFromString` takes.
"Via bytes" hands the codec the bytes: `JacksonCodecs`' `UTF8StreamJsonParser`,
and `KotlinxCodecs.decodeFrom`'s stream reader.

`./gradlew :benchmarks:jmh -PbenchmarkArgs="JettyOverhead DecodeBenchmark"`, the
same run as the [Jetty table](#jetty-in-memory), with the shared settings.

| body | Jackson, via String | Jackson, via bytes | kotlinx, via String | kotlinx, via bytes |
|---|---|---|---|---|
| 100B | 884 ± 78ns, 1608B | 780 ± 22ns, 1536B | 547 ± 26ns, 936B | 699 ± 50ns, 1152B |
| 1KB | 2521 ± 114ns, 3456B | 1748 ± 80ns, 2456B | 2788 ± 216ns, 2784B | 1493 ± 125ns, 2072B |
| 64KB | 264 ± 11µs, 132,546B | 115 ± 7µs, 263,726B | 181 ± 9µs, 131,809B | 180 ± 10µs, 312,513B |

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/what-it-costs/decode-dark.svg">
  <img src="assets/what-it-costs/decode-light.svg" alt="Decode time and allocation for 100B, 1KB and 64KB bodies, with Jackson and kotlinx each reading from a String and from bytes">
</picture>

Each body size is drawn on its own axes.

Allocation varies by no more than 21 bytes either way in any cell.

**What it shows.**

- At 100 bytes, kotlinx via String is the fastest, and the only cell clear of
  the others.
- At 1KB, kotlinx via bytes is the fastest, clear of Jackson via bytes by about
  50ns beyond their errors.
- At 64KB, Jackson via bytes is the fastest by a wide margin: 115µs against
  180µs for either kotlinx route and 264µs for Jackson via String.
- On allocation, kotlinx allocates less than Jackson on the two small bodies by
  either route. At 64KB the byte readers allocate more than the String route
  does: about twice as much for Jackson, and 2.4 times for kotlinx, whose
  stream reader allocates the most, 313KB for a 64KB body.

**Limitations.** One payload shape at three sizes, decoded in isolation. Which
codec is faster depends on the body size, so neither is faster overall. The
result of an earlier run of this benchmark, which led to Pelican decoding
bodies from bytes, is in
[spec 0046](../specs/0046-the-decode-path-unmeasured.md).

### Encoding to bytes

**What is measured.** `EncodeBenchmark` writes one response body of each size
with no route and no server. "Via String" is what both backends did before
`BodyCodec.encodeTo`: the codec's String, then its UTF-8 copy. "Via bytes" is
`JacksonCodecs`' `encodeTo`, which writes straight to bytes. kotlinx keeps the
default `encodeTo`, so its bytes row is `encodeToStream`, shown as the evidence
for not overriding it.

Machine: 4 vCPU Intel Xeon @ 2.10GHz, 16GB, Ubuntu 24.04, Linux 6.18, JDK 25.0.4
for the benchmarks, JDK 21 running Gradle. Load average before the run 1.29,
after it 1.43. Nothing else ran. Three forks of five 1s iterations after five
1s warmups, `-Xms512m -Xmx512m`, `-prof gc`.

```bash
./gradlew :benchmarks:jmh -PbenchmarkArgs="EncodeBenchmark"
```

| body | Jackson, via String | Jackson, via bytes | kotlinx, via String | kotlinx, via bytes |
|---|---|---|---|---|
| 100B | 272 ± 30ns, 744B | 333 ± 81ns, 568B | 146 ± 17ns, 448B | 353 ± 34ns, 760B |
| 1KB | 1330 ± 116ns, 2600B | 2634 ± 438ns, 1496B | 778 ± 66ns, 2304B | 2081 ± 280ns, 4984B |
| 64KB | 121 ± 16µs, 328,369B | 131 ± 16µs, 120,001B | 41.5 ± 2.6µs, 131,328B | 133 ± 12µs, 331,425B |

Each cell is time with its error, then bytes allocated per call.

**Over a socket.** The current checkout is A and `origin/main` is B, Jetty
only, the JSON GET and POST, three rounds in the order ABABAB. Load average
was 1.29 before the run and 10.88 after it.

```bash
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=rest
```

Highest sustained rate: A 40,000 req/s (rounds 40,000, 40,000, 50,000), B
40,000 req/s (rounds 40,000, 40,000, 50,000).

| req/s | percentile | A (this branch) median, range | B (main) median, range | B vs A | outside the spread |
|---|---|---|---|---|---|
| 10000 | p99 | 2.7ms, 1.2 to 3.7ms | 1.2ms, 863µs to 1.3ms | -58.0% | no |
| 10000 | p99.9 | 24.7ms, 6.9 to 35.9ms | 7.9ms, 7.3 to 11.5ms | -67.9% | no |
| 30000 | p50 | 434µs, 374 to 446µs | 369µs, 326 to 373µs | -15.1% | yes, B faster |
| 30000 | p99 | 7.2ms, 6.6 to 8.5ms | 7.9ms, 7.2 to 8.9ms | +9.5% | no |
| 30000 | p99.9 | 14.4ms, 14.2 to 18.9ms | 35.6ms, 15.4 to 37.4ms | +146.7% | no |
| 40000 | p99 | 21.1ms, 14.0 to 28.5ms | 14.8ms, 13.7 to 19.6ms | -29.6% | no |

The full table is in `benchmarks/build/wire-ab.md`.

**Verdict.** Jackson's bytes path is lighter and not faster. It allocates less
per call: 43% less at 1KB (1496B against 2600B) and 63% less at 64KB
(120,001B against 328,369B), and 24% less at 100B. In time it is not faster in
this isolated test. At 1KB it is about twice as slow, 2634 ± 438ns against
1330 ± 116ns, well outside the errors. At 64KB the two overlap (131 ± 16µs
against 121 ± 16µs), and at 100B they nearly do. kotlinx via String beats its
stream route in both time and allocation at every size, which supports leaving
its default in place.

Over the socket, nothing separates the two builds beyond the spread except one
cell, p50 at 30,000 req/s, where main was 15% faster, in the direction against
this change. Every other cell overlaps, and the A rounds differ from each other
by as much as A differs from B (p99 at 10,000 req/s runs from 1.2 to 3.7ms on A
alone). Both builds sustained 40,000 req/s. The machine was loaded (10 and
above during the rounds), so these figures are indicative of noise, not of a
difference. The saving in allocation did not show up as latency here.

## Streaming and framing

Two more benchmarks are in the module and are not retaken on this page. Their
numbers were taken on 4 September 2026 and are recorded in the specs that
asked for them, against the interpreter as it was then.

- `StreamingOverheadBenchmark` compares an NDJSON response of 100 and 10,000
  elements on Pekko against a hand-written `createChunked` route. Spec 0041
  found the two within their error at both sizes, with identical allocation
  per element. See [spec 0041](../specs/0041-what-the-description-costs-a-frame.md).
- `FramingBenchmark` compares two ways of framing a streamed element (through
  a `String`, or straight to bytes), and is a design measurement inside
  Pelican rather than a comparison with hand-written code. See
  [spec 0039](../specs/0039-three-allocations-a-frame.md), whose finding was
  that the bytes route was slower in five cases of six, though it allocated
  less, and was not adopted.

Both run with `./gradlew :benchmarks:jmh -PbenchmarkArgs="StreamingOverhead"`
and `-PbenchmarkArgs="Framing"`. The specs do not record the machine they were
taken on in the detail this page does, so treat them as earlier findings to be
retested rather than current results.

## Reproducing these numbers

```bash
./gradlew :benchmarks:jmh                                         # every JMH benchmark
./gradlew :benchmarks:jmh -PbenchmarkArgs="PekkoOverhead"         # the Pekko tables
./gradlew :benchmarks:jmh -PbenchmarkArgs="JettyOverhead DecodeBenchmark"  # the Jetty and decode tables
./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 RoutingScale"     # the routing sweep, one fork
./gradlew :benchmarks:jmh -PbenchmarkArgs="-prof jfr"             # a flight recording per fork
./gradlew :benchmarks:wire                # the socket run, about eight minutes
./gradlew :benchmarks:wire -PwireSmoke    # one short step per server, to check the setup
./gradlew :benchmarks:wire -PwireScenario=stream  # the NDJSON and SSE responses instead
./gradlew :benchmarks:wire -PwireAb=origin/main   # this checkout against main; see Measuring a change
```

`-PbenchmarkArgs` is passed to JMH as written, so a class name filters the run
and `-f`, `-wi` and `-i` override forks and iterations. The JMH results are
also written to `benchmarks/build/jmh-result.json`, and the socket run's table
to `benchmarks/build/wire-result.md`. The benchmarks run on JDK 25 whatever JDK
runs Gradle, because the tasks name the toolchain.

The [machines](#the-machines) and [load averages](#a-quiet-machine-matters)
above are what a reproduction should be compared against. On different
hardware, compare the rows within one run rather than against the absolute
figures here.
