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
./gradlew :benchmarks:jmh                       # everything
./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 Pekko"    # one file, one fork
./gradlew :benchmarks:jmh -PbenchmarkArgs="-prof jfr"     # a flight recording per fork
```

JMH 1.37, average time in nanoseconds per request, three forks, five
one-second warmup iterations and five one-second measured ones per fork. Errors
are JMH's 99.9% confidence interval over the fifteen measured iterations.
Allocation is `-prof gc`'s `gc.alloc.rate.norm`, which is on by default,
counts every thread rather than only the one calling, and — unlike a timing —
is the same number every run.

Two machines appear on this page. The Pekko, Jetty, decode and wire tables name
the cloud instance. The http4k, routing scale and empty-endpoint tables were
taken earlier on the Apple M3 and say so below.

- **The cloud instance**, which took the Pekko, Jetty, decode and wire tables:
  an Intel Xeon at 2.80GHz, 4 cores (one thread each), 16GB, Ubuntu 24.04.5 LTS,
  Linux 6.18.44 in a Firecracker microVM, on JDK 25.0.4.1, which is the
  toolchain `benchmarks/build.gradle.kts` names. Gradle itself ran on OpenJDK
  21.0.12. Pekko 1.7.1, Pekko HTTP 1.4.1, Jetty 12.1.13.
- **An Apple M3** (four performance and four efficiency cores), 24GB, macOS
  26.5.2, on Temurin 21.0.9+10, which took the older tables: the http4k ones,
  the routing scale and the empty endpoint. They have not been retaken, so do
  not read them against the cloud instance's numbers.

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

Some of the tables below hold http4k rows, taken while `pelican-http4k` was on
main. That module and
[its benchmark](https://github.com/matthewjones372/pelican/blob/a6c20b20350dddb21ac4cbf2d22f88eb8756081e/benchmarks/src/main/kotlin/io/github/matthewjones372/pelican/benchmarks/Http4kOverheadBenchmark.kt)
were taken off `main` for 1.0, and are in the history at commit
[`a6c20b2`](https://github.com/matthewjones372/pelican/tree/a6c20b20350dddb21ac4cbf2d22f88eb8756081e);
they are meant to come back after 1.0. The numbers are left in because they
were really measured, before the modules were removed, and because what they
show is a claim about the interpreter rather than about one server. Each such
table says so. Running them again means checking out `a6c20b2` and running
`:benchmarks:jmh` there.

## http4k, measured before the module was removed (at `a6c20b2`)

One endpoint, `GET /items/{itemId}?limit=`, answering JSON. Taken on the Apple M3.

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

The tables in this section were taken on the Apple M3, not the cloud instance.

Every table above uses an API of **one** endpoint, which measures decoding and
rendering and says nothing about the thing that varies most between services.
Registered with a router directly, the routes are reduced with
`Directives.concat` into an ordered scan. The endpoint under test is declared
last — the worst case a scan has, and the one a service acquires by adding
endpoints over time.

`./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 RoutingScale"` — a class of its
own, so the six-minute run above is unchanged. The two http4k columns were
measured before the module was removed, at `a6c20b2`, as above;
`RoutingScaleBenchmark` on main runs the Pekko column and its hand-written
control.

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
not measured here either.

## An endpoint with nothing to decode, measured before http4k was removed (at `a6c20b2`)

`GET /ping`, answering text. Taken on the Apple M3.

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
sealed with `Route.function(system)`, so none of them pays for a socket, the
same choice the in-memory transport in `pelican-test-pekko` makes. Pelican is
measured twice: with `toRoute(system, Handlers.onDispatcher)`, which runs the
synchronous handler on Pekko's dispatcher like the hand-written routes do, and
on a virtual thread, which is what `toRoute` does by default.

`./gradlew :benchmarks:jmh -PbenchmarkArgs="PekkoOverhead"`, on the cloud
instance described above, with the settings above (three forks, five and five
one-second iterations, a 512MB heap). The load average was 9.85 when the run
started, left over from the wire run before it, and 2.06 when it ended. The run
took about six minutes.

| | per request | allocated |
|---|---|---|
| **Pelican, interpreted onto Pekko, handler on the dispatcher** | **1584 ± 68ns** | **3185 ± 64B** |
| Pelican, handler on a virtual thread (the default) | 78,499 ± 3352ns | 14,028 ± 116B |
| Pekko, idiomatic (`PathMatchers` + `parameterOptional`) | 1959 ± 99ns | 4841 ± 3B |
| Pekko, `PathMatchers` with the query read off the request | 1492 ± 96ns | 3801 ± 2B |
| Pekko, one directive and both read off the request | 1228 ± 54ns | 2401 ± 2B |

Against the idiomatic route, Pelican on the dispatcher is 375ns and 1.6KB
under. That is a statement about `parameterOptional`, which the idiomatic row
and the one below it price at about 470ns and a kilobyte on their own. Take that
one directive out and a hand-written route draws level: 92ns apart, inside the
two errors added together, and Pelican allocates 616 bytes less. Write the whole
thing as a single `extractRequest` that matches the path and reads the query
itself, which is what the interpreter does, and the hand-written version is
**356ns and 784 bytes** ahead, which is the cost of the interpreter on this
endpoint.

The virtual-thread row is 50 times the dispatcher row in time and 10.8KB above
it in allocation. A synchronous handler that never blocks can opt out with
`Handlers.onDispatcher`. The time gap is far larger than the extra allocation
would explain, and this machine has four cores to hand a request between; I have
not looked into where the time goes, and a machine with more cores may show a
smaller gap.

## Reading a body

The GET cases above never materialise anything: the request has no entity and
the response is strict. `POST /items` with a JSON body is where Pekko's streams
actually turn, because `toStrict` materialises. Same command, machine and run as
the table above.

| | per request | allocated |
|---|---|---|
| **Pelican, handler on the dispatcher** | **79.8 ± 4.5µs** | **14,247 ± 106B** |
| Pelican, handler on a virtual thread (the default) | 105.1 ± 5.5µs | 15,699 ± 150B |
| Pekko, hand-written `extractStrictEntity` | 97.5 ± 31.3µs | 12,234 ± 352B |

Two orders of magnitude more than the routing above. Pelican on the dispatcher
allocates 2.0KB more than the hand-written route, and its time is not
distinguishable from it: the hand-written row's error of 31µs covers the
difference. Pelican on a virtual thread is the slowest of the three, 25µs above
the dispatcher. See
[`ChunkedBodyLimitTest`](../backends/pekko/pelican-pekko/src/test/kotlin/io/github/matthewjones372/pelican/pekko/ChunkedBodyLimitTest.kt)
for why the interpreter keeps a second way of reading a body.

## Jetty

The same GET and the same JSON POST, served by `toHandler` and by a
`Handler.Abstract` written against Jetty 12 core that matches the path, reads
the query with `Request.extractQueryParameters`, reads the body with
`Content.Source.asByteArrayAsync` and writes the same Jackson bytes. Each runs
on its own started `Server` with `PELICAN_URI_COMPLIANCE`, and every request
goes through a `LocalConnector`.

**These numbers are not comparable with the Pekko tables above, column to
column.** A `LocalConnector` has no socket, but it is a real connector: each
request arrives as bytes, is parsed as HTTP/1.1 by Jetty's parser on Jetty's
thread pool, and its response is generated back into bytes that the caller
waits for. Pekko's `Route.function` does none of that; it takes an
`HttpRequest` that is already an object and returns one. The Jetty rows include
a parse, a generate and two thread handoffs per request, so they say what
Pelican costs on Jetty against Jetty by hand, and nothing about which server is
faster. That comparison needs a socket under both.

`./gradlew :benchmarks:jmh -PbenchmarkArgs="JettyOverhead DecodeBenchmark"`,
with the settings above: three forks, five and five one-second iterations, a
512MB heap. Jetty 12.1.13, on JDK 25.0.4.1, on the cloud instance described
above. The load average was 2.06 at the start and 2.37 at the end; the run took
about ten minutes.

| | per request | allocated |
|---|---|---|
| **Pelican on Jetty, handler on the Jetty thread** | **117 ± 14µs** | **17,330 ± 78B** |
| Pelican on Jetty, handler on a virtual thread (the default) | 130 ± 13µs | 26,883 ± 265B |
| Jetty core, hand-written | 122 ± 13µs | 16,047 ± 69B |
| **Pelican, `POST /items`, on the Jetty thread** | **123 ± 13µs** | **18,158 ± 137B** |
| Pelican, `POST /items`, on a virtual thread | 146 ± 10µs | 27,635 ± 175B |
| Jetty core, hand-written `POST /items` | 120 ± 17µs | 17,076 ± 86B |

Pelican on the Jetty thread and the hand-written handler overlap inside their
error on both requests. Most of each figure is the connector's thread handoffs,
which Pelican and the hand-written handler pay alike, so the interpreter's own
time is below what this benchmark can resolve. Allocation is the steadier half.
Against Jetty by hand, Pelican on the Jetty thread allocates **1283 bytes** more
on the GET and **1082 bytes** more on the POST. Moving a synchronous handler onto
a virtual thread, which `toHandler` does unless given `Handlers.onDispatcher`,
costs about **9.5KB** a request on top. On the POST it is also the one row whose
time sits above the others, by about 23µs over the hand-written handler, at the
edge of the errors; on the GET it overlaps. A handler that never blocks can opt
out of it.

## Pekko and Jetty over a socket

The tables above are in process, and the Jetty ones parse HTTP where the Pekko
ones do not, so neither says which server is faster. `:benchmarks:wire` does:
it starts Pelican on Pekko and Pelican on Jetty, each with its own `start()`
and its defaults (synchronous handlers on a virtual thread per request; Jetty's
`QueuedThreadPool` of 200 with virtual threads for handlers, Pekko's default
dispatcher), each in a JVM of its own with a 1GB heap, and drives them in turn
over loopback with the same GET and JSON POST as the tables above, alternating.

The driver is open-model: requests leave on a fixed schedule whether or not
earlier ones have answered, and each one's latency runs from when it should
have left, so a stall shows in every request queued behind it. It runs on
Jetty's `HttpClient` with at most 256 requests in flight, because the JDK's
client fell behind its own schedule at 20,000 to 40,000 requests a second and
above that was measuring itself. Each server is warmed for 30 seconds at 5,000
requests a second, then each rate runs 5 seconds unrecorded and 15 recorded. A
server stops keeping up at the first rate whose p99 reaches 100ms, that loses
more than 0.1% of its requests, or whose senders fall two seconds behind.

```bash
./gradlew :benchmarks:wire              # about eight minutes; the table also lands in benchmarks/build/wire-result.md
./gradlew :benchmarks:wire -PwireSmoke  # one short step per server, to check the setup
```

Measured on the cloud instance described above: an Intel Xeon at 2.80GHz, 4
cores, 16GB, Ubuntu 24.04.5 LTS, Linux 6.18.44, JDK 25.0.4.1, Pekko 1.7.1, Pekko
HTTP 1.4.1, Jetty 12.1.13. The command was `./gradlew :benchmarks:wire`, and it
took 5m 58s. **The driver and both servers shared those four cores**, so the
ceiling in this table is the pair's, not the server's alone. Load average on the
machine was 1.70 before Pekko's warmup and 14.17 after its last rate; Jetty
started at 13.28 (the average lags, so most of that is Pekko's own run) and ended
at 10.70. The machine was idle at the start (0.25 one minute after boot).

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

Requests are the recorded ones, over 15 seconds. Pekko at 30,000 sent 338,323 of
the 450,000 it was due to, because its senders fell more than two seconds behind
schedule, so that run stopped early.

Reading it: Pekko's last rate that kept up was 20,000 requests a second and
Jetty's was 30,000. Jetty's first failure, at 40,000, is the senders falling
behind rather than errors, as Pekko's was at 30,000. At the rates both sustain,
Jetty's tails are lower: p99 is 1.3 to 2.3ms against 6.7 to 8.1ms at 1,000 to
10,000 requests a second, and p99.9 is 6 to 10ms against 23 to 50ms. At 20,000
the gap narrows: p99 14.1ms against 21.9ms and p99.9 40.7ms against 45.7ms, so
at that rate both are close to their limit on four cores. Median latency at
5,000 and 10,000 is 283 to 324µs on Jetty and 663 to 807µs on Pekko.

Two cautions. Both servers' 1,000 requests a second row has a higher p99.9 than
their 5,000 row, which I have not explained; the first row after the 30-second
warmup may still be warming. And one run per server, in a fixed order with Pekko
first, is no evidence about run-to-run variation: it says nothing about whether
a second run would put the ceilings in the same place.

## Reading a body: Jackson and kotlinx

`DecodeBenchmark` reads one JSON body of each size with no route and no
server, so only the codec is measured. "via String" decodes the bytes to a
`String` first, which is what a codec's `decodeFromString` takes; "via bytes"
hands the codec the bytes, which is `JacksonCodecs`' `UTF8StreamJsonParser` and
`KotlinxCodecs.decodeFrom`'s stream reader.

`./gradlew :benchmarks:jmh -PbenchmarkArgs="JettyOverhead DecodeBenchmark"`,
three forks, five and five one-second iterations, JDK 25.0.4.1, on the cloud
instance described above (the run in the Jetty section; load average 2.06 to
2.37).

| body | Jackson, via String | Jackson, via bytes | kotlinx, via String | kotlinx, via bytes |
|---|---|---|---|---|
| 100B | 884 ± 78ns, 1608B | 780 ± 22ns, 1536B | 547 ± 26ns, 936B | 699 ± 50ns, 1152B |
| 1KB | 2521 ± 114ns, 3456B | 1748 ± 80ns, 2456B | 2788 ± 216ns, 2784B | 1493 ± 125ns, 2072B |
| 64KB | 264 ± 11µs, 132,546B | 115 ± 7µs, 263,726B | 181 ± 9µs, 131,809B | 180 ± 10µs, 312,513B |

Allocation is within 21 bytes either side on every cell. At 100 bytes kotlinx
via String is the fastest and the only row clear of the others. At 1KB kotlinx
via bytes is fastest, clear of Jackson via bytes by about 50ns beyond their
errors. At 64KB Jackson via bytes is fastest by a wide margin: 115µs against
180µs for either kotlinx route and 264µs for Jackson via String. On allocation,
kotlinx allocates less than Jackson on small bodies by either route, and at 64KB
the byte readers of both libraries allocate more than twice what the String
route does, kotlinx's stream reader the most at 313KB for a 64KB body.

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

Everything else the old document claimed is still standing, the Pekko claims
as retaken on the cloud instance: Pelican is still cheaper than an idiomatic Pekko route, still level
with one that drops `parameterOptional`, still a few hundred nanoseconds behind
one written as a single directive, and, on a POST, no slower than a hand-written
`extractStrictEntity` (within its error; Pelican allocates 2KB more).

## For scale

A loopback socket round trip is tens of microseconds and a database call is
milliseconds, so on a realistic endpoint this is a fraction of a percent. It
does not grow with the endpoint either — the cost is the per-request bag of
decoded values, the `Params` around it and the response, and none of those
scale with how many inputs are declared or how big the payload is. An endpoint
that decodes nothing pays much the same as one that decodes two, which on the
smaller absolute numbers of an empty endpoint reads as a larger ratio.

One caution about reproducing any of this. A benchmark is only as quiet as the
machine under it, and the Apple M3 tables were taken on an otherwise idle one, and the cloud
instance's were taken with nothing else running but a load average of about 2
during the JMH runs (JMH's own threads), and much higher during the wire run,
where the driver is part of the load. The same
command on the same laptop, with an IDE indexing a fresh module and eight
gigabytes of swap in use, reported two to five times these figures with error
bars to match — one row came back at thirty-five times. If a score arrives with
an error anywhere near its own size, that is the machine talking and not the
library, and the run is worth repeating rather than reading.
