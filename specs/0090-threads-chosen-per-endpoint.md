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
- [x] **`spec-0090-nonblocking`**: `nonBlocking()`, honoured by all three backends.
      Done when: under `Handlers.default` a non-blocking endpoint is within 5% of inline (`gannet-nonblocking` contestant).
      Read back as `ServerEndpoint.isNonBlocking`, and kept by `filteredBy`. It covers the handler and its filters:
      where a backend moves an authenticator or a streamed body's read first (Gannet, Jetty), it still does.
      Measured against its parent, five interleaved rounds: `gannet-nonblocking` 255k (249–262k), 100.1% of
      `gannet-inline` per round (95.0..105.7%) and 87.8% of `raw-gannet` (293k); 2.79–2.83 KB a request against
      `gannet-inline`'s 2.81–2.86 and `gannet`'s 4.07–4.15. Other contestants unchanged.
      Jetty does not match the prediction: `jetty-nonblocking` serves 47.5k (46.1–50.0k), 73.7% of `jetty` (64.7k)
      per round, where `jetty-dispatcher` serves 137.7%. Under `Handlers.default` Jetty calls Pelican on its selector,
      so a handler left there runs on it, and at two cores Jetty runs one selector. A throwaway build that hops a
      `nonBlocking()` handler to Jetty's own pool instead served 80.6k (72.0–81.3k), +30% over `jetty`, three rounds.
- [x] **`spec-0090-jetty-pool`**: decision 3, Jetty hops a `nonBlocking()` handler to its own pool, where
      `Handlers.onDispatcher` has Jetty run it, instead of running it on the selector.
      Done when: `jetty-nonblocking` serves more than `jetty`.
      Measured on this branch, five interleaved rounds: `jetty-nonblocking` 82.0k (65.3–88.4k) against `jetty`'s
      67.1k (64.3–68.2k), +22.2% per round (-4.2..+32.3%); `jetty-dispatcher` 94.2k, +40.5%; `raw-gannet` 294k.
      Jetty's spread is wide: two earlier sessions of the same code measured +12.0% and +7.3%.
- [x] **`spec-0090-encode`**: `BodyCodec.encodeTo(value, sink)`, which takes over spec 0085's encode entry.
      Done when: measured on `json` against its parent, kept only if faster.
      `BodySink` is core's (decision 6). Gannet's `respond` takes a `byte[]`, so every backend gathers a `json<T>()`
      success through `spi.encodedBytes` into one array, sized exactly up to 32 KiB; `JacksonCodecs` streams its
      UTF-8 buffer into it, with surrogate pairs combined so the bytes do not change. A lone surrogate, which the
      String's UTF-8 wrote as `?`, is now escaped. JMH (`EncodeBenchmark`, 50 items): 13.3 µs and 20.0 KB against
      14.1 µs and 20.2 KB through the String; into a buffer already there, as a backend's would be, 11.1 µs and 3.7 KB.
      `json`, five interleaved rounds against `spec-0090-jetty-pool`: `gannet` 57.6k from 51.5k (+11.6% per round,
      +7.3..+14.8%), `gannet-inline` 75.4k from 73.2k (+2.9%), `jetty` 30.4k from 27.6k (+10.2%), `pekko` 21.9k
      from 20.1k (+3.9%); `raw-gannet` 284k on `baseline`. Writing into Gannet's own buffer wants a `respond` that
      takes a writer: a Gannet change, not made here.
- [x] **`spec-0090-gannet-writer`**: pelican-gannet answers a `json<T>()` success on the carrier through Gannet's
      `respond(status, headers) { out -> }` (gannet#10, decision 7), encoding straight into the carrier's buffer.
      Done when: measured on `json` against its parent, kept only if faster.
      Off the carrier the payload is still gathered first: there Gannet answers before the writer runs, and a codec
      that throws would close the connection instead of answering 500. A throw on the carrier reaches Pelican with
      nothing committed and is answered as before. JMH (`GannetHandlerBenchmark`, the 50-item page through the
      handler, page built per call): 13.6 µs and 16.8 KB on the carrier against 15.2 µs and 33.0 KB gathered. `json`, five
      interleaved rounds against `main`: `gannet-nonblocking` 87.2k from 76.8k (+11.8% per round, +9.7..+15.1%),
      `gannet-inline` 85.0k from 78.9k (+7.8%, +4.7..+15.2%), `gannet` unchanged (58.0k, -1.2%); `raw-gannet` 294k
      on `baseline`.

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
benchmarks/arena.sh baseline raw-gannet gannet-inline gannet gannet-nonblocking jetty jetty-nonblocking
benchmarks/arena.sh json gannet gannet-inline   # against the parent build, interleaved
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

Matt took each of these on 2026-10-26, after `spec-0090-nonblocking` was measured.

3. **Jetty hops a `nonBlocking()` handler to its own pool**, not its selector: on the
   selector it served 26% under `jetty`, on the pool +30% over it.
4. **`nonBlocking()` covers the handler and its filters only.** An authenticator, or a
   streamed body's read, moves where a backend moves it first.
5. **Read back as `ServerEndpoint.isNonBlocking`.**
6. **`encodeTo` writes into a sink interface of pelican-core's own**, not an
   `OutputStream` or a `ByteBuffer`. If the gain needs Gannet to take more than a
   `byte[]`, that is a Gannet pull request of its own; Pelican stays on
   `0.1.0-SNAPSHOT`.
7. **Gannet's writer `respond`**: called on the carrier, the writer runs before anything is committed and a
   throw reaches the caller; called from another thread, a throw after the exchange is answered closes the
   connection.
