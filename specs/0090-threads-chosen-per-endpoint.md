# 0090 — Threads chosen per endpoint

## Problem

Under `Handlers.default` every request hops from the server's thread to a virtual
thread, and on Gannet the hop is the largest single cost Pelican adds. On HttpArena's
`baseline` (gcannon, closed model, 4096 connections, server pinned to 2 of 4 cores,
JDK 25) Pelican on Gannet serves 131k requests/s inline (`Handlers.onDispatcher`) and
94k by default. Per request (async-profiler, user CPU; bytes from `alloc --total`; the
full per-stage profile is in spec 0089):

| stage | inline µs / B | default µs / B |
|---|--:|--:|
| handoff to handler thread | 0.02 / 130 | 2.58 / 924 |
| body: Gannet `readAll` | 1.99 / 16104 | 2.06 / 15661 |
| write (`respond`) | 0.38 / 281 | 0.74 / 428 |
| Gannet: parse, ring, flush | 1.15 / 1559 | 2.12 / 1510 |
| user CPU, total | 8.06 / 21645 | 11.99 / 22534 |
| kernel (`/proc` sys) | 6.69 | 8.75 |

Spec 0089's deadline and exact-size read, measured by throwaway switches, bring the
default path to 118–126k; the hop, about 7 µs a request, is what remains between it and
inline. The other backends pay it the same way: `jetty-dispatcher` serves 77k against
`jetty`'s 61k (+26%), and Pekko 26.5k against 21k. Separately, on `json` Jackson is 73%
of the CPU, and its String-then-bytes path about 10% of that; on `baseline` encoding
costs 0.05 µs.

## Not doing

- **Detecting a handler that blocks.** `nonBlocking()` is a promise the caller makes;
  nothing checks it. A Gannet carrier-stall warning would be a Gannet issue.
- **A different default thread.** `Handlers.default` stays on virtual threads.
- **The request view, plan, routing, decoders and deadline.** Spec 0089; this spec
  builds on it.
- **`nonBlocking` in the description.** It stays out of `Endpoint` and the document.
- **Encoding into a buffer for every codec.** `json` only, and only if measured faster.

## Shape

```kotlin
getSum handledNow { (a, b) -> a + b }                  // unchanged: runs on a virtual thread
(getSum handledNow { (a, b) -> a + b }).nonBlocking()  // stays on the carrier/selector under Handlers.default
jsonCodec.encodeTo(value, sink)                        // the backend's output buffer; no String, no byte[] copy
```

- **Body before the hop.** On Gannet, with no authenticator, a strict body is read on the
  server's thread before the hop, so the virtual thread starts with the bytes in hand.
- **`ServerEndpoint.nonBlocking()`**, chosen per binding and honoured by all three
  backends: the handler runs where the request arrived, at inline speed.
- **`BodyCodec.encodeTo(value, sink)`** writes straight into the backend's output
  buffer; it takes over spec 0085's encode entry.

## Why this shape

The hop buys isolation from a handler that blocks, and most handlers on a hot path never
block, so the choice belongs to each binding rather than to the server. Option A,
`ServerEndpoint.nonBlocking()`, is set where the route is bound; option B, a flag on the
`Endpoint`, would put threading into the description and the document. Recommended and
decided: A. Reading the body before the hop helps every endpoint, marked or not.

## Stack

- [x] **`spec-0090-carrier-body`**: on Gannet, a strict body read on the carrier before the hop, when there is no authenticator.
      Done when: default baseline ≥ 130k.
      Only the read moves: what it throws is kept and raised where the body is decoded, so a 406 or a bad parameter
      still answers first. Measured against its parent, five interleaved rounds: `gannet` 165k (158–169k) from 135k
      (132–148k), +25.1% per round (+10.6..+25.5%); `gannet-inline` unchanged (+0.1%); `raw-gannet` 295k (268–298k).
      4.14–4.32 KB a request from 4.32–4.37.
- [ ] **`spec-0090-nonblocking`**: `nonBlocking()`, honoured by all three backends.
      Done when: under `Handlers.default` a non-blocking endpoint is within 5% of inline (`gannet-nonblocking` contestant).
- [ ] **`spec-0090-encode`**: `BodyCodec.encodeTo(value, sink)`, which takes over spec 0085's encode entry.
      Done when: measured on `json` (`gannet-encode` contestant), kept only if faster.

Predicted, on top of spec 0089: `-carrier-body` default ~130k; `-nonblocking` any
endpoint so marked ~245k on Gannet, and on Jetty what `jetty-dispatcher` measured
over `jetty`, +26%.

## Risks

- **A `nonBlocking()` handler that blocks stalls every connection on its core.** Opt-in
  per binding, and documented with that sentence.
- **Noise.** Measured with spec 0089's `arena.sh`, quoted with run-to-run spread.

## Acceptance

Same machine, same day, each contestant warmed 10 s and measured 10 s, median of three:

```bash
./gradlew build
benchmarks/arena.sh baseline raw-gannet gannet-inline gannet gannet-nonblocking jetty
benchmarks/arena.sh json gannet gannet-encode
```

- `gannet` (default threads) ≥ 130k.
- A `nonBlocking()` endpoint under `Handlers.default` (`gannet-nonblocking`) ≥ 230k.
- No backend's parity suite, golden file or document changes.

## Decisions

Matt took each recommendation on 2026-10-25.

1. **Split from spec 0089**, which keeps the pipeline; this spec takes threading and
   codec output, with its own targets.
2. **No blocking detection**: `nonBlocking()` does not try to detect a handler that
   blocks (see Not doing).
