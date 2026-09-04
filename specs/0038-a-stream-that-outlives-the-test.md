# 0038 — A stream that outlives the test

## Problem

Pelican hands a handler's `Source` to Pekko HTTP and never collects it, so an
unbounded response should cost a bounded amount of memory. Nothing measures
that.

`SlowConsumerTest` drives `Source.range(1, Int.MAX_VALUE)` but throttles it to
one element every thirty seconds: it proves the source is *closed* when the
consumer disappears, and says nothing about what happens when a producer is
faster than a reader. `UploadTimingTest` proves the first frame leaves before
the last one arrives — incrementality, not volume. There is no streaming
benchmark. A service betting on an endpoint that never ends has an argument
from the code path and no number.

The claim worth testing is not "memory stays under N megabytes", which is a
GC-timing test wearing a costume. It is **a stalled consumer stops the
producer**: if anything between the handler and the socket collected the
stream, production would continue with nobody reading, and that is observable
without touching the heap.

## Not doing

- **No heap assertion.** No `Runtime.totalMemory`, no forced GC, no allocation
  counter. Those fail on someone else's timing.
- **No response-side body limit.** The limit is request-side; an endpoint that
  never ends is the handler's decision. A cap on responses is its own spec.
- **No streaming benchmark.** JMH over a socket measures the socket.
- **No new interpreter behaviour.** This spec asserts what is already claimed.

## Shape

Two tests beside `SlowConsumerTest`, driving a genuinely unbounded source with
no throttle:

```kotlin
val produced = AtomicLong()

val firehose = endpoint { get("firehose"); sse<Tick>() }

firehose streamedNow {
    Source.fromIterator { generateSequence(0L) { it + 1 }.iterator() }
        .map { n -> produced.incrementAndGet(); Tick(n) }
}
```

1. **A stalled consumer stops the producer.** Read a few frames, stop reading,
   let the buffers fill, sample `produced`, wait, sample again. The two samples
   are equal: nothing is draining the source on the reader's behalf. The first
   sample is also asserted below a ceiling — equal samples alone pass with any
   bounded buffer, however large, because a big one still fills during the
   settle. Measured: ~20,400 elements ahead of a stalled reader, which is
   Pekko's stage buffers plus the TCP window; the ceiling is ten times that.
2. **A fast consumer gets everything, in order.** A million elements through
   `ndjson<Tick>()`, read as fast as the socket allows. All arrive, in
   sequence, and the run finishes in seconds — so nothing accumulates and
   nothing is quadratic.

## Why this shape

Equal samples under a stalled reader is the whole claim, stated in a way that
cannot flake: it is a fact about demand, not about a collector. The
alternative — assert a memory ceiling — needs a forced GC to mean anything and
fails on a machine under load, which is how a test that guards nothing ends up
being deleted for being flaky.

The second test exists because the first passes trivially if throughput is
broken: a producer that never gets going also stops when the reader does.

## Stack

- [x] **`spec-0038-unbounded-stream`** — one test class in
      `example/src/test/kotlin/example/backends/`, both tests, on the harness
      shapes `SlowConsumerTest` already uses.
      Done when: `./gradlew build` is green, and both tests fail on an
      interpreter that collects the source before answering.

## Acceptance

```bash
./gradlew build
./gradlew :example:test --tests '*UnboundedStreamTest*'
```

## Open questions

None — answered by the maintainer in chat, 2026-09-04, and recorded as decided:

- **It lives in `example/`**, beside `SlowConsumerTest`, on a real port. The
  route testkit cannot model a socket that stops being read.
- **500ms to settle, then a 500ms window** in which `produced` must not move —
  the numbers `SlowConsumerTest` already uses.
- **A million elements** through `ndjson`, dropping a decimal if the suite
  notices the time.
- **Written against the harness shape**, so a returning backend inherits it.
