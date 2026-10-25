# 0089 — A pipeline compiled per endpoint

## Problem

Each backend carries its own copy of the steps between the socket and the handler
(roadmap item 2), and on Gannet those steps cost more than Gannet does. On HttpArena's
`baseline` (gcannon, closed model, 4096 connections, server pinned to 2 of 4 cores,
JDK 25) Pelican on Gannet serves 131k requests/s inline (`Handlers.onDispatcher`) and
94k by default (virtual threads). A minimal handler on the same Gannet serves 198k when
it reads the body with `readAll`, and 285k when it reads exactly `Content-Length`.
The 443k quoted for "raw Gannet" is `bvr.arena.UringArena`, the byte-level prototype
Gannet came from (428–443k here, the same day), not Gannet's exchange API.

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

Two costs are most of the gap, and a throwaway switch measured each (inline, two runs):
`readStrictBody` schedules a 10 s deadline on a shared `ScheduledThreadPoolExecutor`
per request, cancels it without removal (about 400k dead tasks stay queued, each holding
a `TimedOut` whose stack trace was filled), so `siftDown`, the stack walk and the GC they
cause cost 1.56 µs plus most of the GC excess. Removing it: 179–181k. Reading
`Content-Length` bytes into one array instead of Gannet's `readAll`, which zeroes two
16 KiB buffers per body: 236–241k, 5.0 KB per request. With both, the default path
reaches 118–126k. What is then left of Pelican over raw Gannet is 1.2 µs user, 0.3 µs
kernel and 2.8 KB: building the request 0.26 µs, plain inputs 0.24 µs, routing 0.09 µs,
the body codec 0.10 µs and `respond` headers 0.19 µs.

## Not doing

- **Gannet changes.** Its `readAll` buffers, a zero-copy head and pre-encoded header
  bytes are Gannet issues, filed there, not stack entries here.
- **A different default thread.** `Handlers.default` stays on virtual threads.
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
(getSum handledNow { (a, b) -> a + b }).nonBlocking()  // stays on the carrier/selector under Handlers.default
```

How each candidate fared against the profile:

| candidate | inline cost today | verdict |
|---|--:|---|
| no per-request deadline task or exception | 1.56 µs + GC | **in**, +38% measured; see **Deadline** below |
| exact-size body read | 1.99 µs, 16 KB | **in**, measured +32% on top |
| route tree walked over the raw path | 0.10 + path 0.26 µs | **in**, est. +3% |
| lazy view, not copied strings and maps | in the row above | **in** with it; Gannet's own copy stays |
| decoders resolved per endpoint, slots | 0.34 µs, 1.3 KB | **in**, est. +3% |
| fewer success-path wrappers | 0.07 µs | **in**, folded into `-success`, ~1% |
| exceptions not used for control flow | the `TimedOut` above | **in**; refusals still throw (AGENTS.md) |
| header lines encoded once, copied as bytes | 0.19 µs | **later**: needs a Gannet API, ≤2% |
| codecs writing into the output buffer | 0.05 µs on baseline | **json only**: Jackson is 73% of `json`'s CPU, its String-then-bytes path ~10% |
| per-endpoint non-blocking declaration | the 7 µs hop | **in**: inline speed per endpoint |

Typed guarantees, each kept the same way: the handler's `I` is still `inputs.extract`
over `Params`; `Params.get` of an undeclared key still throws, because the plan has no
slot for it; `successNamedBy` and `failureNamedBy` still check every reply; the route
and the document are still read from one `Endpoint`; percent-decoding, dot segments,
`+` as a space, 400/401/406/408/413 and their bodies are byte-for-byte as today, as the
parity suite and the differential tests check; a codec still fails at startup.

## Why this shape

Compile what the description already knows (routes, decoders, slots, codecs) once, and
read the request without copying it, so the remaining per-request work is decoding the
values the handler asked for. **Deadline:** option A, the shared scheduler with
`removeOnCancelPolicy` and the exception made only on firing, was measured: 154–158k,
with the carriers at 155% CPU, because every request takes the executor's lock. Option
B, a sweeper: each strict read appends itself to a lock-free queue and marks itself done,
and one thread fails whatever is overdue every 250 ms, so a request pays a CAS and the
exception exists only when it fires. Option C, a body deadline on Gannet's own tick.
Recommended: B now, C as a Gannet issue. **Non-blocking:** option A,
`ServerEndpoint.nonBlocking()`, chosen per binding; option B, a flag on the `Endpoint`,
which would put threading into the description and the document. Recommended: A.

## Stack

- [ ] **`spec-0089-arena`**: `raw-gannet` contestant and `benchmarks/arena.sh` (gcannon, PIDs, a quiet-machine gate).
      Done when: one run prints raw, inline and default in one table.
- [ ] **`spec-0089-deadline`**: Gannet's strict-body deadline kept by a sweeper, its exception made on firing.
      Done when: the 408 test passes and inline baseline ≥ 170k.
- [ ] **`spec-0089-sized-read`**: a declared length read into one exact array; chunked grows from 64 bytes.
      Done when: Pelican-on-Gannet allocates ≤ 6 KB per baseline request and inline ≥ 225k.
- [ ] **`spec-0089-view`**: `RequestView`, `RouteIndex` matching in place, dot segments without a split; Gannet adopts.
      Done when: routing and building allocate nothing for `/baseline11` (JMH `-prof gc`).
- [ ] **`spec-0089-plan`**: `EndpointPlan` with slot-backed `Params` and the query scanned once; Gannet adopts.
      Done when: plain inputs allocate only the decoded values.
- [ ] **`spec-0089-jetty`**: Jetty on the view and plan, its copies deleted.
      Done when: Jetty's parity suite passes and its baseline does not regress.
- [ ] **`spec-0089-pekko`**: Pekko on the view and plan, its copies deleted.
      Done when: Pekko's parity suite passes.
- [ ] **`spec-0089-success`**: a completed stage answered without chaining; reply headers built once.
      Done when: the success path allocates at most two objects of its own (JMH).
- [ ] **`spec-0089-carrier-body`**: on Gannet, a strict body read on the carrier before the hop, when there is no authenticator.
      Done when: default baseline ≥ 130k.
- [ ] **`spec-0089-nonblocking`**: `nonBlocking()`, honoured by all three backends.
      Done when: under `Handlers.default` a non-blocking endpoint is within 5% of inline.
- [ ] **`spec-0089-encode`**: `BodyCodec.encodeTo(value, sink)`, which takes over spec 0085's encode entry.
      Done when: measured on `json`, kept only if faster.

Predicted inline / default (measured where a switch existed, else from the table): today
131k / 94k; `-deadline` ~175k / ~110k; `-sized-read`
~230k / ~120k; `-view` ~237k; `-plan` ~244k; `-success` ~247k; `-carrier-body` default
~130k; `-nonblocking` any endpoint so marked ~245k. Jetty: the core entries remove about
1.5 µs of its 33 µs (Pelican's own frames are 8% of Jetty's user CPU), so +3–5%; `nonBlocking`
is worth what `jetty-dispatcher` measured over `jetty`, +26% (77k against 61k). Pekko is
dominated by its hop the same way (26.5k against 21k).

## Risks

- **A `nonBlocking()` handler that blocks stalls every connection on its core.** Opt-in
  per binding, and documented with that sentence.
- **Decoding drift.** One shared decoder replaces three copies; the differential tests
  and the parity suite are the oracle, and they run before each backend adopts it.
- **A view outliving its request.** `RequestView` is read before the hop and never stored;
  what a handler keeps is the decoded slots.
- **Gates.** The slot array needs a `FunctionalStyleTest` entry with its reason; the
  sweeper's queue another.
- **Noise.** This machine is shared; `arena.sh` waits for an idle machine and alternates
  contestants, and a number is quoted only with its run-to-run spread.

## Acceptance

Same machine, same day, each contestant warmed 10 s and measured 10 s, median of three:

```bash
./gradlew build
benchmarks/arena.sh baseline raw-gannet gannet-inline gannet jetty
```

- `gannet-inline` ≥ 240k and ≥ 82% of `raw-gannet` (sized read) in the same run.
- `gannet` (default) ≥ 130k; a `nonBlocking()` endpoint under it ≥ 230k.
- Pelican's own allocation over `raw-gannet` ≤ 1.5 KB per baseline request (today 3.3 KB).
- `jetty` ≥ 63k; no backend's parity suite, golden file or document changes.

## Open questions

1. **Close to raw Gannet, or to the 443k prototype?** Recommended: target Gannet on its
   exchange API (285k); the rest is Gannet's work, tracked there.
2. **Split this spec?** It runs past a page. Recommended: if trimmed, split
   `-carrier-body`, `-nonblocking` and `-encode` into a threading-and-codecs spec.
3. **Does an illegal escape in an undeclared query parameter stay a 400?** Today it does.
   Recommended: yes, checked by a scan that allocates nothing.
4. **Should `nonBlocking()` detect a blocking handler?** Recommended: not in this spec;
   a Gannet carrier-stall warning is its issue.
