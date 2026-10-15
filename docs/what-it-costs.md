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
the last byte and counted in responses a second. `-PwireScenario=async`
alternates a GET whose handler returns a completed stage with a GET to a path
nothing routes, answered 404: the requests Jetty can answer without leaving its
selector. `both` runs each scenario in turn.

```bash
git fetch origin
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=both  # on main: the noise, about 35 to 45 minutes
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=rest  # on a branch: the JSON GET and POST, about 17 minutes
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=stream  # on a branch: the streams, about 15 to 25 minutes
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=async  # on a branch: the staged GET and the 404
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireSmoke  # one round, one short step: checks the setup in a minute
```

`-PwireRounds=<n>` changes the three rounds and `-PwireAbPekko` adds Pekko.
The first A/B run also builds the baseline, a few minutes more.

**Jetty's thread pool.** The Jetty server JVM reads `pelican.jetty.pool`:
`queued` is `start()`'s pool, Jetty's `QueuedThreadPool` running handlers on
virtual threads, and `virtual` is Jetty's `VirtualThreadPool`, where its
selectors and acceptors are virtual threads too. `-PwirePool=virtual` serves
every Jetty run on it. `-PwireAbPool` compares the two within this checkout,
`VirtualThreadPool` as A and the queued pool as B, with no second build:

```bash
./gradlew :benchmarks:wire -PwireAbPool -PwireScenario=both  # the two pools, A then B, three rounds
./gradlew :benchmarks:wire -PwireAbPool -PwireSmoke          # checks the setup in a minute
```

**Main against main.** `origin/main` as A and as B, three rounds each, run on
the cloud instance with nothing else running. Any difference below that is
inside these ranges is noise on this machine.

```bash
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=both
```

The machine: 4 cores, Intel Xeon @ 2.10GHz, one thread per core, 16 GB, no
swap, Ubuntu 24.04.5 LTS, a Linux 6.18.44 Firecracker VM, JDK 25.0.4.1+1-LTS
for the benchmark and JDK 21 for Gradle. Load averages were 0.00, 0.00, 0.00 on
the freshly booted machine before the run and 4.72, 4.05, 4.05 after it (the
run took about 55 minutes). During the run the load generator and server share
the four cores, so the per-run loads in the file were 6 to 11.

**Reading it.** At the middle rates the medians of identical builds agree on
p50 to within about 4%, but p99 moves by 7% to 21% and p99.9 by 20% to 50%
between sides, and a single side's own p99 range is often 2x to 5x wide. A
result on this machine is therefore trusted only when the two ranges are
disjoint and the gap is large: roughly 5% on p50, and a factor of 2 or more on
p99 and p99.9. The highest sustained rate moved one step on the JSON scenario
(B failed to keep up at 50000 req/s in one round of three, so its highest sustained rate there was 40000),
so one rung of the ladder is also noise. Across the whole table only 2 of 48
rows came out "outside the spread" with identical builds.

#### Rest (JSON GET and POST)

Highest sustained req/s: A 50000 (runs: 50000, 50000, 50000), B 50000 (runs: 50000, 50000, 40000).

| req/s | percentile | A median | A range | B median | B range | B vs A | outside the spread |
|---|---|---|---|---|---|---|---|
| 1000 | p50 | 508µs | 490µs to 515µs | 528µs | 478µs to 530µs | +3.8% | no |
| 1000 | p99 | 2.6ms | 1.7ms to 8.4ms | 3.1ms | 3.0ms to 7.2ms | +18.7% | no |
| 1000 | p99.9 | 7.4ms | 3.8ms to 73.6ms | 23.5ms | 7.2ms to 84.7ms | +217.2% | no |
| 5000 | p50 | 204µs | 199µs to 215µs | 198µs | 188µs to 207µs | -2.8% | no |
| 5000 | p99 | 978µs | 629µs to 1.2ms | 1.1ms | 917µs to 1.2ms | +13.0% | no |
| 5000 | p99.9 | 3.8ms | 3.7ms to 6.2ms | 5.7ms | 5.5ms to 6.4ms | +49.6% | no |
| 10000 | p50 | 215µs | 210µs to 217µs | 213µs | 199µs to 217µs | -0.8% | no |
| 10000 | p99 | 1.7ms | 1.4ms to 3.2ms | 1.5ms | 1.4ms to 3.2ms | -11.9% | no |
| 10000 | p99.9 | 7.8ms | 7.5ms to 53.9ms | 10.5ms | 7.8ms to 26.7ms | +34.7% | no |
| 20000 | p50 | 268µs | 267µs to 276µs | 271µs | 265µs to 277µs | +1.3% | no |
| 20000 | p99 | 6.2ms | 3.3ms to 7.1ms | 5.7ms | 4.9ms to 6.1ms | -7.4% | no |
| 20000 | p99.9 | 26.0ms | 12.2ms to 30.9ms | 20.5ms | 14.7ms to 40.0ms | -21.3% | no |
| 30000 | p50 | 357µs | 336µs to 386µs | 354µs | 319µs to 380µs | -0.7% | no |
| 30000 | p99 | 8.3ms | 7.8ms to 10.2ms | 10.0ms | 7.0ms to 13.5ms | +21.1% | no |
| 30000 | p99.9 | 20.6ms | 19.6ms to 20.6ms | 28.3ms | 16.2ms to 38.9ms | +37.8% | no |
| 40000 | p50 | 721µs | 611µs to 762µs | 608µs | 536µs to 1.0ms | -15.8% | no |
| 40000 | p99 | 10.5ms | 9.2ms to 11.6ms | 9.4ms | 8.9ms to 12.8ms | -10.0% | no |
| 40000 | p99.9 | 17.0ms | 16.9ms to 23.2ms | 20.1ms | 16.9ms to 25.9ms | +18.0% | no |
| 50000 | p50 | 3.1ms | 3.0ms to 4.8ms | 2.7ms | 2.7ms to 11.5ms | -10.1% | no |
| 50000 | p99 | 57.4ms | 27.8ms to 60.5ms | 53.4ms | 37.1ms to 280.2ms | -7.0% | no |
| 50000 | p99.9 | 86.8ms | 48.6ms to 87.8ms | 73.7ms | 49.0ms to 308.0ms | -15.0% | no |
| 60000 | p50 | 1241.5ms | 1224.7ms to 1461.7ms | 988.8ms | 888.1ms to 1089.5ms | -20.4% | yes, faster |
| 60000 | p99 | 1922.0ms | 1916.8ms to 1954.5ms | 1902.1ms | 1839.2ms to 1965.0ms | -1.0% | no |
| 60000 | p99.9 | 1991.2ms | 1978.7ms to 1995.4ms | 1960.3ms | 1929.4ms to 1991.2ms | -1.6% | no |

#### Stream (NDJSON and SSE)

Highest sustained responses/s: A 4000 (runs: 4000, 4000, 4000), B 4000 (runs: 4000, 4000, 4000).

| responses/s | percentile | A median | A range | B median | B range | B vs A | outside the spread |
|---|---|---|---|---|---|---|---|
| 250 | p50 | 994µs | 986µs to 1.0ms | 982µs | 945µs to 1.1ms | -1.2% | no |
| 250 | p99 | 4.5ms | 4.4ms to 5.4ms | 4.2ms | 4.0ms to 6.8ms | -7.1% | no |
| 250 | p99.9 | 10.3ms | 9.7ms to 43.1ms | 25.3ms | 24.4ms to 29.4ms | +145.7% | no |
| 500 | p50 | 869µs | 855µs to 906µs | 892µs | 889µs to 966µs | +2.7% | no |
| 500 | p99 | 3.5ms | 3.2ms to 4.3ms | 6.1ms | 4.0ms to 7.5ms | +73.6% | no |
| 500 | p99.9 | 10.1ms | 7.5ms to 148.6ms | 45.0ms | 40.2ms to 54.5ms | +344.3% | no |
| 1000 | p50 | 824µs | 797µs to 855µs | 779µs | 755µs to 814µs | -5.5% | no |
| 1000 | p99 | 80.1ms | 4.4ms to 93.2ms | 3.7ms | 2.6ms to 4.5ms | -95.4% | no |
| 1000 | p99.9 | 159.3ms | 40.4ms to 208.0ms | 9.1ms | 6.5ms to 15.1ms | -94.3% | yes, faster |
| 2000 | p50 | 717µs | 696µs to 738µs | 753µs | 664µs to 775µs | +4.9% | no |
| 2000 | p99 | 6.7ms | 4.0ms to 9.0ms | 4.2ms | 3.8ms to 4.4ms | -37.8% | no |
| 2000 | p99.9 | 23.9ms | 12.3ms to 53.7ms | 11.6ms | 11.3ms to 15.0ms | -51.6% | no |
| 3000 | p50 | 851µs | 762µs to 982µs | 786µs | 715µs to 905µs | -7.6% | no |
| 3000 | p99 | 5.4ms | 3.9ms to 7.4ms | 5.1ms | 4.8ms to 6.4ms | -5.9% | no |
| 3000 | p99.9 | 12.0ms | 9.2ms to 15.6ms | 13.5ms | 9.8ms to 46.5ms | +12.3% | no |
| 4000 | p50 | 1.1ms | 1.0ms to 1.2ms | 1.1ms | 942µs to 1.2ms | +5.8% | no |
| 4000 | p99 | 6.8ms | 6.7ms to 9.4ms | 11.5ms | 8.1ms to 25.5ms | +68.6% | no |
| 4000 | p99.9 | 12.5ms | 11.3ms to 27.0ms | 36.7ms | 14.7ms to 73.7ms | +192.9% | no |
| 6000 | p50 | 458.5ms | 41.9ms to 644.3ms | 428.9ms | 40.0ms to 431.0ms | -6.5% | no |
| 6000 | p99 | 1051.7ms | 213.8ms to 1317.0ms | 1234.2ms | 169.9ms to 1432.4ms | +17.3% | no |
| 6000 | p99.9 | 1098.9ms | 260.8ms to 1431.3ms | 1285.6ms | 211.0ms to 1504.7ms | +17.0% | no |

### Non-blocking invocation

**What is measured.** Spec 0085's `spec-0085-nonblocking` branch (A, head
f736701e) against `origin/main` (B), three alternating rounds each, with
`-PwireAb`. The change answers routing, 404 and 405, refusals and handlers
that return a stage on Jetty's selector without a dispatch, and hops
synchronous handlers to a virtual thread. `async` is the path it targets;
`rest` is the path it adds a hop to, run to check for a regression. The first
measurement found synchronous handlers slower than main because the hop cost a
second dispatch to resume the connection; the hop thread now resumes the
connection itself, so a synchronous request costs one dispatch like main, and
the tables below replace that first measurement. In the tables, "B vs A" is
main relative to this branch, so a negative number means main had the lower
latency, and "yes, slower" or "yes, faster" describes main.

**Machine.** Intel Xeon Processor at 2.10GHz, 4 cores (`nproc` 4), 15GiB memory,
Ubuntu 24.04.5, Linux 6.18.44, JDK 25.0.4.1 for the benchmarks and JDK 21.0.12
running Gradle. Load average before and after each run, one, five and fifteen
minutes:

| run | before | after |
|---|---|---|
| rest | 1.32, 0.79, 0.35 | 10.18, 6.86, 4.83 |
| async | 8.00, 6.54, 4.76 (started right after rest) | 11.04, 7.22, 5.93 |

The load climbs during each run because the load generator shares the four
cores with the server, so neither run was on a quiet machine in the sense of
[a quiet machine matters](#a-quiet-machine-matters); the per-round figures are
in `wire-ab.md`. Nothing else ran on the machine. No main against main run
exists on this page for this machine, so the spread used here is each side's
own three rounds.

```bash
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=rest
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=async
```

**async (a staged GET alternating with a 404).** Highest sustained req/s: A 60000 (runs: 60000, 60000, 60000), B 60000 (runs: 60000, 50000, 60000).

| req/s | percentile | A median | A range | B median | B range | B vs A | outside the spread |
|---|---|---|---|---|---|---|---|
| 1000 | p50 | 391µs | 379µs to 416µs | 433µs | 397µs to 445µs | +10.7% | no |
| 1000 | p99 | 2.0ms | 1.6ms to 2.9ms | 2.1ms | 1.8ms to 2.3ms | +4.0% | no |
| 1000 | p99.9 | 5.2ms | 4.0ms to 27.9ms | 21.4ms | 4.5ms to 29.4ms | +312.0% | no |
| 5000 | p50 | 176µs | 168µs to 195µs | 184µs | 168µs to 185µs | +4.5% | no |
| 5000 | p99 | 1.5ms | 1.2ms to 4.1ms | 1.1ms | 925µs to 1.3ms | -30.3% | no |
| 5000 | p99.9 | 28.3ms | 4.1ms to 74.2ms | 13.6ms | 4.9ms to 53.3ms | -52.0% | no |
| 10000 | p50 | 162µs | 142µs to 177µs | 189µs | 188µs to 191µs | +16.2% | yes, slower |
| 10000 | p99 | 2.7ms | 908µs to 2.9ms | 1.1ms | 925µs to 2.4ms | -60.9% | no |
| 10000 | p99.9 | 24.8ms | 18.1ms to 47.5ms | 5.7ms | 5.0ms to 33.0ms | -77.1% | no |
| 20000 | p50 | 174µs | 173µs to 189µs | 225µs | 215µs to 242µs | +29.7% | yes, slower |
| 20000 | p99 | 3.2ms | 3.1ms to 3.4ms | 4.2ms | 3.0ms to 5.4ms | +30.4% | no |
| 20000 | p99.9 | 24.1ms | 22.7ms to 30.1ms | 29.5ms | 11.7ms to 31.3ms | +22.5% | no |
| 30000 | p50 | 199µs | 196µs to 231µs | 249µs | 236µs to 258µs | +25.2% | yes, slower |
| 30000 | p99 | 6.3ms | 3.5ms to 88.4ms | 5.5ms | 5.5ms to 24.3ms | -11.4% | no |
| 30000 | p99.9 | 43.2ms | 11.6ms to 150.2ms | 23.2ms | 19.1ms to 94.6ms | -46.1% | no |
| 40000 | p50 | 272µs | 254µs to 282µs | 341µs | 335µs to 423µs | +25.2% | yes, slower |
| 40000 | p99 | 5.9ms | 5.3ms to 11.6ms | 7.7ms | 5.8ms to 7.8ms | +30.5% | no |
| 40000 | p99.9 | 11.3ms | 10.8ms to 44.1ms | 17.8ms | 11.2ms to 20.2ms | +57.0% | no |
| 50000 | p50 | 434µs | 390µs to 777µs | 678µs | 630µs to 689µs | +56.0% | no |
| 50000 | p99 | 10.1ms | 9.5ms to 13.8ms | 8.6ms | 8.4ms to 10.8ms | -14.3% | no |
| 50000 | p99.9 | 20.6ms | 18.9ms to 55.8ms | 16.1ms | 14.0ms to 17.5ms | -21.9% | yes, faster |
| 60000 | p50 | 1.6ms | 1.4ms to 1.7ms | 2.6ms | 1.5ms to 4.4ms | +59.0% | no |
| 60000 | p99 | 21.7ms | 21.1ms to 22.8ms | 31.3ms | 25.8ms to 100.0ms | +44.4% | yes, slower |
| 60000 | p99.9 | 35.9ms | 35.0ms to 52.4ms | 45.3ms | 45.2ms to 146.9ms | +25.9% | no |
| 70000 | p50 | 219.4ms | 16.3ms to 283.9ms | 870.3ms | 631.2ms to 1109.4ms | +296.7% | yes, slower |
| 70000 | p99 | 836.2ms | 458.2ms to 845.2ms | 1942.0ms | 1930.4ms to 1953.5ms | +132.2% | yes, slower |
| 70000 | p99.9 | 872.9ms | 497.3ms to 917.0ms | 1989.1ms | 1986.0ms to 1992.3ms | +127.9% | yes, slower |

**rest (the JSON GET and POST, synchronous handlers).** Highest sustained req/s: A 40000 (runs: 40000, 40000, 40000), B 50000 (runs: 50000, 40000, 50000).

| req/s | percentile | A median | A range | B median | B range | B vs A | outside the spread |
|---|---|---|---|---|---|---|---|
| 1000 | p50 | 498µs | 470µs to 532µs | 504µs | 439µs to 556µs | +1.3% | no |
| 1000 | p99 | 2.6ms | 2.0ms to 2.7ms | 2.2ms | 1.7ms to 3.4ms | -14.8% | no |
| 1000 | p99.9 | 6.1ms | 6.0ms to 8.1ms | 5.4ms | 4.5ms to 45.7ms | -11.3% | no |
| 5000 | p50 | 215µs | 205µs to 244µs | 205µs | 201µs to 216µs | -5.0% | no |
| 5000 | p99 | 1.1ms | 835µs to 1.2ms | 1.0ms | 670µs to 1.3ms | -8.8% | no |
| 5000 | p99.9 | 5.3ms | 5.1ms to 8.1ms | 6.9ms | 5.0ms to 7.1ms | +31.0% | no |
| 10000 | p50 | 234µs | 211µs to 239µs | 218µs | 215µs to 222µs | -7.2% | no |
| 10000 | p99 | 1.4ms | 1.1ms to 2.3ms | 4.3ms | 1.4ms to 4.8ms | +201.7% | no |
| 10000 | p99.9 | 8.1ms | 7.5ms to 13.5ms | 39.8ms | 9.5ms to 66.8ms | +394.2% | no |
| 20000 | p50 | 259µs | 252µs to 271µs | 259µs | 256µs to 276µs | +0.3% | no |
| 20000 | p99 | 4.4ms | 4.3ms to 4.5ms | 5.2ms | 4.9ms to 10.2ms | +17.4% | yes, slower |
| 20000 | p99.9 | 19.3ms | 17.1ms to 32.7ms | 17.9ms | 11.3ms to 47.6ms | -7.5% | no |
| 30000 | p50 | 412µs | 314µs to 451µs | 339µs | 316µs to 402µs | -17.8% | no |
| 30000 | p99 | 9.4ms | 8.1ms to 25.3ms | 11.0ms | 6.5ms to 27.4ms | +16.5% | no |
| 30000 | p99.9 | 38.2ms | 29.7ms to 102.4ms | 41.9ms | 39.3ms to 85.7ms | +9.7% | no |
| 40000 | p50 | 790µs | 628µs to 1.0ms | 600µs | 485µs to 904µs | -24.1% | no |
| 40000 | p99 | 10.7ms | 9.6ms to 14.7ms | 9.8ms | 8.2ms to 13.1ms | -8.6% | no |
| 40000 | p99.9 | 17.1ms | 15.8ms to 32.0ms | 16.4ms | 15.7ms to 24.9ms | -3.7% | no |
| 50000 | p50 | 7.9ms | 6.5ms to 261.5ms | 2.4ms | 2.4ms to 2.7ms | -69.5% | yes, faster |
| 50000 | p99 | 147.2ms | 125.1ms to 620.8ms | 45.9ms | 42.5ms to 260.2ms | -68.8% | no |
| 50000 | p99.9 | 172.8ms | 171.7ms to 650.6ms | 69.5ms | 67.4ms to 339.7ms | -59.7% | no |

**Verdict, async.** This branch is still faster at the median: from 10000 to
40000 requests a second main's p50 is 16% to 30% higher and outside the spread
at each rate. At 1000 and 5000 req/s and at 50000 and 60000 the p50 ranges
overlap. The tails show no difference by the 2x rule: the only p99 and p99.9
gaps outside the spread are 60000 req/s p99 (21.7ms against 31.3ms), 50000
p99.9 (main lower, 16.1ms against 20.6ms) and the overloaded 70000 step, none
of them 2x apart before overload. The highest sustained rate is 60000 for both
(A 60000 three times, B 60000, 50000, 60000), so the ladder ceiling does not
separate them.

**Verdict, rest.** This branch is no worse than main beyond the noise from 1000
to 40000 requests a second: every p50, p99 and p99.9 range overlaps except p99
at 20000 req/s (4.4ms against 5.2ms, main 17% higher, under the 2x bar). The
synchronous regression of the first measurement is gone. Main held 50000 req/s
in two of three runs where this branch held 40000 in all three, so at 50000 main
is lower at p50 (2.4ms against 7.9ms) with disjoint ranges; that is one rung of
the ladder, which the noise rule treats as noise, but it is the one place the
branch trails and a repeat would settle it.

### Thread pool

`VirtualThreadPool` (A) against `start()`'s queued pool (B), one build, ABABAB,
three rounds, `-PwireScenario=both`, on the 4-core cloud instance below. The
machine was otherwise idle: load average 0.07 before and 3.61 after, and 8.6 to
10.8 during the REST runs and 4.6 to 8.0 during the streams (the load generator
is on the same machine).

```bash
./gradlew :benchmarks:wire -PwireAbPool -PwireScenario=both
```

Machine: Intel Xeon @ 2.80GHz, 4 cores, 15 GiB, Ubuntu 24.04.5 on Linux
6.18.44, benchmarks on JDK 25.0.4 with JDK 21 running Gradle.

Highest sustained rate, per run and the median of three:

| scenario | A, virtual | B, queued |
|---|---|---|
| REST, req/s | 40000 (40000, 40000, 40000) | 30000 (40000, 30000, 30000) |
| stream, responses/s | 4000 (4000, 3000, 4000) | 3000 (2000, 3000, 3000) |

Latency at a few shared rates, median of three with the range in brackets. The
pools disagree about which is faster, and the differences outside each side's
own range are marked.

| scenario | rate | p99, A | p99, B | outside the spread |
|---|---|---|---|---|
| REST | 5000 | 1.4ms (1.4 to 1.7) | 998µs (957µs to 1.1) | B faster |
| REST | 10000 | 1.8ms (1.7 to 6.6) | 2.5ms (2.2 to 3.5) | no |
| REST | 20000 | 6.2ms (4.0 to 8.1) | 5.5ms (4.9 to 6.5) | no |
| REST | 30000 | 12.2ms (12.1 to 14.0) | 15.1ms (13.4 to 19.2) | no |
| REST | 40000 | 20.9ms (15.7 to 32.0) | 133.9ms (64.8 to 1088.4) | A faster |
| stream | 250 | 4.2ms (3.9 to 4.3) | 3.6ms (3.5 to 3.6) | B faster |
| stream | 2000 | 6.1ms (4.3 to 6.1) | 6.8ms (5.1 to 20.6) | no |
| stream | 3000 | 10.8ms (6.9 to 27.8) | 12.4ms (9.7 to 173.7) | no |
| stream | 4000 | 79.0ms (62.8 to 306.4) | 240.9ms (100.6 to 381.2) | no |

The full tables are in `benchmarks/build/wire-ab.md` after a run. Beyond these:
the queued pool is also faster outside the spread at p50 for REST at 5000 and
10000 and for streams at 2000, and at p99.9 for REST at 5000.

**Verdict.** The virtual pool holds a higher rate before it falls behind, 40000
requests a second against 30000, and is far better in the overloaded steps
(REST at 40000, where the queued pool's p99 was 64.8ms to 1088.4ms). Below
saturation it is no better and, at 5000 requests a second on REST and 250
responses a second on streams, measurably slower at p99. It is not faster
everywhere and slower nowhere, so `start()` keeps the queued pool and
`-Dpelican.jetty.pool=virtual` stays an opt-in for a service that runs close to
its limit. There was no A against A run, so the spread is each side's own range
over three rounds.

### Gathered frames, measured and dropped

Spec 0085's second entry wrote the frames a stream had ready as one write, up
to Jetty's `outputAggregationSize` (8 KiB by default), instead of one write per
frame. Branch `spec-0085-frames` (#326) as A against main as B, three rounds,
ABABAB, on 15 October 2026:

```bash
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=stream
```

Machine: 4 cores of an Intel Xeon @ 2.80GHz, 15 GiB, Ubuntu 24.04, the
benchmark on JDK 25.0.4 and Gradle on JDK 21. Load average 0.41 before and 4.90
after; 3.5 to 6.6 during the runs. Both sides sustained 2000 responses a second
in every round and fell behind at 3000.

| responses/s | percentile | A, gathered | B, main | outside the spread |
|---|---|---|---|---|
| 250 | p99 | 11.2ms (7.9 to 29.4) | 6.6ms (6.1 to 7.3) | B faster |
| 250 | p99.9 | 35.4ms (34.3 to 65.4) | 17.4ms (14.9 to 22.1) | B faster |
| 1000 | p50 | 1.2ms (1.1 to 1.3) | 1.1ms (1.0 to 1.2) | no |
| 1000 | p99 | 4.7ms (4.1 to 6.2) | 5.0ms (4.2 to 6.0) | no |
| 2000 | p50 | 1.7ms (1.6 to 1.9) | 1.5ms (1.5 to 1.6) | no |
| 2000 | p99 | 12.6ms (11.5 to 26.8) | 9.5ms (9.1 to 17.1) | no |

No other cell was outside the spread. The change was not kept because it was
no faster than main at any rate and its tail was worse at 250 responses a
second.

### Encoding to bytes, measured and dropped

Spec 0085's third entry added `BodyCodec.encodeTo`, which Jackson and kotlinx
overrode to encode straight to bytes instead of to a `String` and then to
bytes. Branch `spec-0085-encode` (#327), measured in isolation with JMH and
over the wire against main:

```bash
./gradlew :benchmarks:jmh -PbenchmarkArgs="EncodeBenchmark"
./gradlew :benchmarks:wire -PwireAb=origin/main -PwireScenario=rest
```

Machine: 4 vCPUs of an Intel Xeon @ 2.10GHz, 16 GB, JDK 25.0.4. Load average
1.29 before both runs, 1.43 after the JMH run and 10.88 after the wire run.

Time per call with its error, then bytes allocated per call:

| body | Jackson, via String | Jackson, via bytes | kotlinx, via String | kotlinx, via bytes |
|---|---|---|---|---|
| 100B | 272 ± 30ns, 744B | 333 ± 81ns, 568B | 146 ± 17ns, 448B | 353 ± 34ns, 760B |
| 1KB | 1330 ± 116ns, 2600B | 2634 ± 438ns, 1496B | 778 ± 66ns, 2304B | 2081 ± 280ns, 4984B |
| 64KB | 121 ± 16µs, 328,369B | 131 ± 16µs, 120,001B | 41.5 ± 2.6µs, 131,328B | 133 ± 12µs, 331,425B |

Jackson's bytes path allocates 43% less at 1KB and 63% less at 64KB, but takes
about twice as long at 1KB and overlaps at 64KB. kotlinx's bytes path is slower
and allocates more at every size.

Over the wire, A is the branch and B is main. Both sustained 40000 requests a
second (40000, 40000, 50000 for each).

| req/s | percentile | A, bytes | B, main | outside the spread |
|---|---|---|---|---|
| 10000 | p99 | 2.7ms (1.2 to 3.7) | 1.2ms (0.86 to 1.3) | no |
| 30000 | p50 | 434µs (374 to 446) | 369µs (326 to 373) | B faster |
| 30000 | p99 | 7.2ms (6.6 to 8.5) | 7.9ms (7.2 to 8.9) | no |
| 40000 | p99 | 21.1ms (14.0 to 28.5) | 14.8ms (13.7 to 19.6) | no |

The change was not kept because the allocation it saved did not make any
request faster, and the one difference outside the spread favoured main.

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
