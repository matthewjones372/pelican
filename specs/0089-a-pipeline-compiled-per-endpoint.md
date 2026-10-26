# 0089 — A pipeline compiled per endpoint

## Problem

Each backend carries its own copy of the steps between the socket and the handler
(roadmap item 2), and on Gannet those steps cost more than Gannet does. On HttpArena's
`baseline` (gcannon, closed model, 4096 connections, server pinned to 2 of 4 cores,
JDK 25) Pelican on Gannet serves 131k requests/s inline (`Handlers.onDispatcher`) and
94k by default (virtual threads). A minimal handler on Gannet's own API serves 198k when
it reads the body with `readAll`, and 285k when it reads exactly `Content-Length`. That
285k is the ceiling this spec measures against.

Per request, from async-profiler (user CPU, `cpu --all-user`, scaled by `/proc`
user time; bytes from `alloc --total`) and attributed to the innermost stage frame:

| stage | inline µs (%) | inline B | default µs (%) | default B | raw (`readAll`) µs / B |
|---|--:|--:|--:|--:|--:|
| handoff to handler thread | 0.02 (0.3) | 130 | 2.58 (21.5) | 924 | 0 / 0 |
| build request (path, headers) | 0.44 (5.4) | 952 | 0.77 (6.4) | 879 | 0.10 / 256 |
| routing | 0.10 (1.3) | 108 | 0.08 (0.7) | 113 | 0 / 0 |
| plain-input decoding | 0.34 (4.2) | 1277 | 0.34 (2.8) | 1262 | 0.06 / 91 |
| body: Gannet `readAll` | 1.99 (24.7) | 16104 | 2.06 (17.2) | 15661 | 1.88 / 16116 |
| body: deadline timer | 1.56 (19.3) | 519 | 0.91 (7.6) | 473 | 0 / 0 |
| body: decode | 0.18 (2.2) | 455 | 0.19 (1.6) | 991 | 0 / 0 |
| handler | 0.02 (0.2) | 65 | 0.01 (0.1) | 68 | 0.06 / 73 |
| encode, build reply | 0.05 (0.6) | 130 | 0.05 (0.4) | 158 | 0 / 0 |
| write (`respond`) | 0.38 (4.7) | 281 | 0.74 (6.2) | 428 | 0.18 / 238 |
| Gannet: parse, ring, flush | 1.15 (14.2) | 1559 | 2.12 (17.7) | 1510 | 0.99 / 1481 |
| GC | 1.40 (17.4) | | 1.64 (13.7) | | 0.56 |
| user CPU, total | 8.06 | 21645 | 11.99 | 22534 | 4.15 / 18293 |
| kernel (`/proc` sys) | 6.69 | | 8.75 | | 5.63 |

Two costs are most of the gap, and a throwaway switch measured each (inline, two runs).
pelican-gannet's `readStrictBody` schedules a 10 s deadline on a shared
`ScheduledThreadPoolExecutor` per request and cancels it without removal (about 400k dead
tasks stay queued, each holding a `TimedOut` whose stack trace was filled), so `siftDown`,
the stack walk and the GC they cause cost 1.56 µs plus most of the GC excess. Removing
it: 179–181k. Gannet's `readAll` zeroes two 16 KiB buffers per body; reading
`Content-Length` bytes into one array instead: 236–241k, 5.0 KB per request. With both,
the default path reaches 118–126k. What is then left of Pelican over raw Gannet is 1.2 µs
user, 0.3 µs kernel and 2.8 KB: building the request 0.26 µs, plain inputs 0.24 µs,
routing 0.09 µs, the body codec 0.10 µs and `respond` headers 0.19 µs.

## Not doing

- **Gannet changes.** The exact-size body read is being fixed in Gannet itself; this spec
  depends on that change and only calls it. A zero-copy head and pre-encoded header bytes
  are Gannet issues too.
- **Gannet's byte-level prototype.** `bvr.arena.UringArena`, quoted as "raw Gannet" at
  443k (428–443k here, the same day), is not Gannet's API and is not a target.
- **Threads and codec output.** The body read before the hop, `nonBlocking()` and codecs
  encoding into the backend's buffer are spec 0090. `Handlers.default` stays on virtual threads.
- **Leaving `CompletionStage<Any?>`.** The chain keeps it; core gains no dependency.
- **HTTP/2, a compiler plugin, generated routes.** Descriptions stay values.

## Shape

Core gains `spi.RequestView` (method, raw path, raw query, `header(name)`, `headers(name)`)
and `spi.EndpointPlan`, built once per endpoint by `toHandler` from the same `Endpoint`
value. The plan holds the decoders in declaration order and a slot per input:

```kotlin
val plan = endpoints.plan(codecs)                    // once, at toHandler; a broken codec fails here
val match = plan.route(view)                         // walks the raw path in place; null declines
val params = match.decode(view, body)                // slots, decoded in Pekko's order; throws refusals as today
getSum handledNow { (a, b) -> a + b }                // unchanged
```

| candidate | inline cost today | verdict |
|---|--:|---|
| no per-request deadline task or exception | 1.56 µs + GC | **in**, +38% measured; see **Deadline** below |
| exact-size body read (Gannet's change) | 1.99 µs, 16 KB | **in** once Gannet ships it, measured +32% on top |
| route tree walked over the raw path | 0.10 + path 0.26 µs | **in**, est. +3% |
| lazy view, not copied strings and maps | in the row above | **in** with it; Gannet's own copy stays |
| decoders resolved per endpoint, slots | 0.34 µs, 1.3 KB | **in**, est. +3% |
| fewer success-path wrappers | 0.07 µs | **in**, folded into `-success`, ~1% |
| exceptions not used for control flow | the `TimedOut` above | **in**; refusals still throw (AGENTS.md) |
| header lines encoded once, copied as bytes | 0.19 µs | **later**: needs a Gannet API, ≤2% |

Typed guarantees, each kept the same way: the handler's `I` is still `inputs.extract`
over `Params`; `Params.get` of an undeclared key still throws, because the plan has no
slot for it; `successNamedBy` and `failureNamedBy` still check every reply; the route
and the document are still read from one `Endpoint`; percent-decoding, dot segments,
`+` as a space, 400/401/406/408/413 and their bodies are byte-for-byte as today, as the
parity suite and the differential tests check, except the strict UTF-8 of decision 5; a
codec still fails at startup.

## Why this shape

Compile what the description already knows (routes, decoders, slots) once, and read the
request without copying it, so the remaining per-request work is decoding the values the
handler asked for. **Deadline:** option A, the shared scheduler with `removeOnCancelPolicy`
and the exception made only on firing, was measured: 154–158k, with the carriers at 155%
CPU, because every request takes the executor's lock. Option B, a sweeper: each strict
read pushes itself onto a lock-free stack and marks itself done, and every 250 ms one
thread drops what is done and fails whatever is overdue, so a request pays two CASes, a
done deadline is held for one sweep at most, and the exception exists only when it fires.
Option C, a body deadline on Gannet's own tick. Recommended: B now, C as a Gannet issue.

## Stack

- [ ] **`spec-0089-arena`**: `raw-gannet` (Gannet's API, exact `Content-Length`) and `benchmarks/arena.sh` (gcannon, PIDs, a quiet-machine gate).
      Done when: one run prints raw, inline and default in one table.
- [x] **`spec-0089-deadline`** ([#344](https://github.com/matthewjones372/pelican/pull/344)): pelican-gannet's strict-body deadline kept by a sweeper, its exception made on firing.
      Done when: the 408 test passes and inline baseline ≥ 170k.
- [x] **`spec-0089-sized-read`**: pelican-gannet on the Gannet release with the exact-size read.
      Done when: Pelican-on-Gannet allocates ≤ 6 KB per baseline request and inline ≥ 225k.
      Closed with no code: the snapshot `main` builds against (`0.1.0-20261025.140125-2`) reads exactly
      `Content-Length`. In `spec-0089-arena`'s run Pelican allocates 5.0 KB a request (`raw-gannet` 2.3 KB);
      inline is 211k (208–214k) against `raw-gannet`'s 279k (275–285k), 76%. The same build served 238k earlier
      the same day; interleaved with today's, it serves 209–214k, so the machine moved and the 225k is quoted
      against a different day's ceiling. Later entries are judged against `raw-gannet` in the same run.
- [ ] **`spec-0089-view`**: `RouteIndex` matching in place, and dot segments resolved without a split, in core; Gannet adopts.
      Done when: routing `/baseline11` allocates nothing (JMH `-prof gc`, `RequestPathBenchmark`).
- [ ] **`spec-0089-view-gannet`**: `RequestView`; Gannet reads the request through it.
      Done when: building the request allocates only the view, which takes the credentials object's place.
- [x] **`spec-0089-plan`**: `EndpointPlan` with slot-backed `Params` and the query scanned once; Gannet adopts.
      Done when: plain inputs allocate only the decoded values.
      `Slots` is a `MutableMap` over the plan's array, so `Params`, `decodeCaller`, the multipart reader and the
      index's captures read and write it unchanged, and a key with no slot is absent. The slots belong to the endpoint,
      so the index finds it first (`RouteIndex.find`) and then decodes its captures (`decodeCaptures`).
      Measured: `PlainInputsBenchmark` 152 B/op (the slots and the two values) against 1224 B/op for the form
      reading; Pelican on Gannet 3.15 KB a request from 4.40–4.53 KB (`raw-gannet` 2.32–2.35 KB); `gannet-inline`
      +10.6% over its parent, 87% of `raw-gannet` from 79%, five interleaved rounds.
- [x] **`spec-0089-strict-utf8`**: decision 5, core's query and form decoding strict UTF-8, before Jetty adopts it.
      Done when: `StrictDecodingTest` gives one 400 for escapes that are not UTF-8 on all three backends.
      One allocation-free scan per pair (`checkFormEncoded`) replaces the escape check; Jetty and Pekko call it through
      `spi.refuseIllegalQuery` until their own entries move them onto the plan.
- [x] **`spec-0089-jetty`**: Jetty on the view, the plan, core's dot segments and `spi.succeeded()`; its copies deleted.
      Done when: its parity suite passes and Jetty's baseline does not regress.
      Jetty's dot-segment copy is core's `withoutDotSegments`, not nothing: Jetty refuses a path climbing above the root
      itself, but hands `/items/x/..` over unresolved. Measured against `-strict-utf8`, six interleaved rounds:
      `jetty` 75.9k from 73.8k (+4.2% per round, -1.4..+19.8%), `jetty-dispatcher` 104k from 91k (+14.4%, +7.9..+47.9%);
      4.7 KB a request from 6.3–6.4 (`jetty`), 3.4 KB from 5.2–5.8 (`jetty-dispatcher`); `gannet-inline` unchanged.
- [ ] **`spec-0089-pekko`**: Pekko on the view and plan, its copies deleted.
      Done when: its parity suite passes.
- [x] **`spec-0089-success`**: a completed stage answered without chaining; reply headers built once.
      Done when: the success path allocates at most two objects of its own (JMH).
      On Gannet; Jetty and Pekko take `spi.succeeded()` with their own entries. Only a plain `CompletableFuture`
      is read in place, since every query on the minimal stage `completedStage` returns throws. "Built once" is
      the head of a reply carrying only its content type; a head with a handler's headers is still built per reply.
      Measured: `GannetHandlerBenchmark` 296 B/op for a whole `/baseline11` (568 on `-plan`, 1680 on `-view-gannet`),
      the reply's stage and its `Strict` the success path's own; Pelican on Gannet 2.81–2.88 KB a request from
      3.10–3.14 (`raw-gannet` 2.29–2.30); `gannet-inline` +1.4%, `gannet` +4.1% over `-plan`, five interleaved rounds.

Predicted inline / default (measured where a switch existed, else from the table): today
131k / 94k; `-deadline` ~175k / ~110k; `-sized-read` ~230k / ~120k; `-view` ~237k;
`-plan` ~244k; `-success` ~247k. Jetty: these entries remove about 1.5 µs of its 33 µs
(Pelican's own frames are 8% of Jetty's user CPU), so +3–5%.

## Risks

- **Gannet's release.** Shipped in the snapshot; `-sized-read` closed on it.
- **Decoding drift.** One shared decoder replaces three copies; the differential tests
  and the parity suite are the oracle, and they run before each backend adopts it.
- **A view outliving its request.** `RequestView` is never stored; a handler keeps slots.
- **Gates.** The slot array is not an accumulator and needs no `FunctionalStyleTest` entry;
  `EndpointPlan.kt` has one for the copy `Slots.entries` hands `lensInputs`. The
  sweeper's stack needs none: each deadline is its own link, not an element of a collection.
- **Noise.** This machine is shared; `arena.sh` waits for an idle machine and alternates
  contestants, and a number is quoted only with its run-to-run spread.

## Acceptance

Same machine, same day, each contestant warmed 10 s and measured 10 s, median of three:

```bash
./gradlew build
benchmarks/arena.sh baseline raw-gannet gannet-inline gannet jetty
```

- `gannet-inline` ≥ 240k and ≥ 82% of `raw-gannet` (about 285k) in the same run.
- Pelican's own allocation over `raw-gannet` ≤ 1.5 KB per baseline request (today 3.3 KB).
- `jetty` ≥ 63k; no backend's parity suite, golden file or document changes.

## Decisions

Matt took each recommendation on 2026-10-25.

1. **The target is Gannet's API**: `raw-gannet` reads exactly `Content-Length` (285k).
   What lies beyond that API is Gannet's work, tracked in Gannet issues.
2. **Split.** Threading and codec output moved to spec 0090, with its own targets.
3. **An illegal escape in an undeclared query parameter stays a 400**, as today, checked
   by a scan that allocates nothing. An escape of two ASCII hex digits passes; anything else is
   put to `URLDecoder`, whose message stays the 400's detail, since what it refuses has varied
   by JDK (25 reads the digits with `HexFormat`; the `parseInt` older ones used took `%+1`).
4. **`nonBlocking()` does not detect a blocking handler**; spec 0090 records the non-goal.
5. **Query strings and form bodies are strict UTF-8 in core** (Matt, 2026-10-25, option B),
   so that Jetty's move onto the shared decoder changes no answer of its own. Escapes whose
   bytes are not UTF-8 (truncated, overlong, a surrogate, past U+10FFFF, split by a literal)
   are a 400, `Illegal query string`, detail `The percent-encoded bytes are not UTF-8`, for
   names and values alike and for undeclared pairs, as decision 3's escapes are; an illegal
   escape in the same name or value wins, with `URLDecoder`'s message. A form body carrying
   them is a 400 with that detail. Before: Gannet and Pekko served `?q=%e9` as U+FFFD (200)
   and read `?%f0` as a key named U+FFFD; Jetty answered `?q=%e9` with a 500 and dropped
   `?%f0`. Pekko's own parser still refuses `?q=%zz` in words of its own
   (`JettyDifference.ILLEGAL_ESCAPES_IN_QUERY`); Jetty's detail for it becomes `URLDecoder`'s.
