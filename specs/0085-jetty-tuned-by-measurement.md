# 0085 — Jetty, tuned by measurement

## Problem

Spec 0077 measured Pelican on Jetty within error of hand-written Jetty, so what is
left to gain is in how Pelican hands bytes to Jetty and how `start()` sets Jetty up,
not in routing. Five places look costly and none has been measured on its own:

- A streamed response writes one frame per `Response.write` and waits for its
  callback before pulling the next, so small NDJSON and SSE frames cost a write each.
- `BodyCodec` encodes to a `String`, which is then copied into a `ByteArray`: two
  copies of every JSON response.
- `start()` runs `QueuedThreadPool` with a virtual-thread executor; Jetty 12 also has
  `VirtualThreadPool`, built for one virtual thread per request.
- Every request is dispatched off the selector, even where Pelican never blocks
  (routing, 404/405, a handler returning a stage).
- The connector keeps Jetty's defaults for buffers, selectors and the `Date` and
  `Server` headers, and offers no HTTP/2 cleartext.

## Not doing

- **Pekko.** Its default-hop cost (spec 0077's 78µs) is its own spec.
- **A change kept on argument.** Each entry lands only with a measured gain, or with
  its result recorded and the change dropped.
- **Breaking the codec seam.** The new encoder has a default, as `decodeFrom` did.

## Shape

```kotlin
interface BodyCodec<T> {
    fun encodeToString(value: T): String
    fun encodeTo(value: T): ByteArray = encodeToString(value).toByteArray(UTF_8)  // new, overridden by Jackson and kotlinx
}

api.start(port = 8080)  // unchanged call; the tuning is inside
```

- Streamed frames are gathered into one write while more are ready, up to a buffer,
  and flushed when the producer would block; backpressure is unchanged.
- `start()`'s pool, invocation types and connector settings are whatever the
  measurements choose, each documented with its number.

## Why this shape

One knob per entry, each measured A/B on the same cloud machine with
`:benchmarks:wire` and the JMH Jetty table, so a gain is attributable and a loss
is visible. The alternative is to change them together and measure once, which
cannot say which change helped. Recommended: one at a time, in expected order of payoff.

## Stack

- [ ] **`spec-0085-baseline`**: an A/B mode for `:benchmarks:wire` (two builds, same
      machine, alternating runs) and a streaming scenario (NDJSON and SSE, small frames).
      Done when: main against main shows the run-to-run noise in the doc.
- [ ] **`spec-0085-frames`**: gathered frame writes.
      Done when: the streaming scenario is measured against main, kept only if faster.
- [ ] **`spec-0085-encode`**: `BodyCodec.encodeTo`, overridden by Jackson and kotlinx, used by both backends.
      Done when: JMH allocation and the wire table are measured against main.
- [ ] **`spec-0085-nonblocking`**: non-blocking invocation where Pelican never blocks;
      synchronous handlers still on virtual threads.
      Done when: measured against main, and a blocking handler cannot stall the selector (a test).
- [ ] **`spec-0085-pool`**: `VirtualThreadPool` against today's pool.
      Done when: measured, and the faster one is `start()`'s default.
- [ ] **`spec-0085-connector`**: buffer, selector, header and h2c settings, one at a time.
      Done when: each kept setting has its number in the doc.

## Acceptance

```bash
./gradlew build
./gradlew :benchmarks:wire -PwireAb=<ref>
```

## Decisions

Matt asked for this on 2026-10-15. Measurements run on a cloud instance, A/B on one machine per entry.
