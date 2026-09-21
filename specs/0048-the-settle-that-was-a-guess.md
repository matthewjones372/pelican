# 0048 — The settle that was a guess

## Problem

`UnboundedStreamTest`'s stalled-consumer test waits a fixed 500 ms for Pekko's
buffers to quiesce, snapshots the produced counter, then asserts nothing more is
produced over the next 500 ms:

```kotlin
Thread.sleep(SETTLE_MILLIS)
val settled = produced.get()
Thread.sleep(STALL_MILLIS)
produced.get() shouldBe settled
```

On a slower or busier machine 500 ms is not enough, so the pipeline is still
filling when the snapshot is taken and the test fails with a number that looks
alarming and means nothing:

```
the source produced 16319 elements with nobody reading
expected:<131699L> but was:<148018L>
```

Measured in a container: that from a full `build`, then 48,411 on each of three
re-runs of the test alone — it fails every time, not occasionally. On those runs the
companion assertion — `settled shouldBeLessThan BOUNDED_AHEAD`, 200,000 —
passes, so back-pressure is working and buffering is bounded. What is wrong is
the wall clock, not the behaviour under test.

The test's own KDoc names this failure mode and then walks into a version of it:
*"A memory ceiling needs a forced collection to mean anything and fails on a
machine under load, which is how a test that guards something real ends up
deleted for flaking."* It avoided the heap ceiling and set a timer instead.

## Not doing

- **No tolerance on the assertion.** "Nearly nothing produced" is a weaker claim
  than the one this test exists to make, and a threshold is the same guess
  moved.
- **No larger `SETTLE_MILLIS`.** A bigger guess is still a guess, and it slows
  every run to buy nothing on the machine that is slower still.
- **Nothing to the second test.** `a consumer that keeps up gets every element`
  reads to a count and has no timer in it.
- **Nothing to `SlowConsumerTest`.** Its `Thread.sleep(STALL_MILLIS)` provokes a
  write failure rather than waiting for quiescence, so it makes no assumption
  this changes.

## Shape

Wait for quiescence rather than assume it. The counter stops moving when demand
stops reaching the source; how long that takes is the machine's business.

```kotlin
/** Settled when two consecutive samples agree. A source that never settles is the bug this test is for. */
private fun AtomicLong.settled(within: Duration): Long {
    val deadline = System.nanoTime() + within.inWholeNanoseconds
    var last = get()
    while (System.nanoTime() < deadline) {
        Thread.sleep(SAMPLE_MILLIS)
        val now = get()
        if (now == last) return now
        last = now
    }
    fail("the source was still producing after $within with nobody reading: $last elements")
}
```

The test then reads:

```kotlin
val settled = produced.settled(within = SETTLE_TIMEOUT)
Thread.sleep(STALL_MILLIS)
produced.get() shouldBe settled
```

`BOUNDED_AHEAD` and the second `withClue` are unchanged, measured against the
same `settled`.

## Why this shape

The claim stays exact. After quiescence, zero further production is still the
assertion — what changes is how quiescence is established, from a fixed sleep
that a slow machine invalidates to an observation that any machine can make.
Tolerance would have traded the claim for the timing; this trades nothing.

It also fails better in the case the test exists for. A stream that something
collected never quiesces, so the deadline is reached and the failure says the
source was still producing — which is the finding — rather than a pair of
six-digit numbers that a reader has to subtract.

`SETTLE_TIMEOUT` is a deadline rather than a delay: only a real failure waits
the whole of it, so it can be generous without costing a passing run anything.
The fast path is now bounded by how quickly the pipeline actually settles, which
on a healthy machine is shorter than today's 500 ms.

## Stack

- [x] **`spec-0048-settle-by-observation`** ([#119](https://github.com/matthewjones372/pelican/pull/119)) — `settled(within:)` replaces
      `SETTLE_MILLIS`, with the deadline failing in the source's name.
      Done when: `./gradlew :example:test --tests "*UnboundedStreamTest*"
      --rerun-tasks` passes three times running in a container where it fails
      three times today, and a passing run is no slower.

## Acceptance

```bash
for i in 1 2 3; do ./gradlew :example:test --tests "*UnboundedStreamTest*" --rerun-tasks || break; done
./gradlew build
```

## Open questions

- **What deadline?** It is only paid on failure. Recommend 10 s, matching
  `SOCKET_TIMEOUT_MILLIS` in the same file, so the two waits in this test agree.
- **What sample interval?** Recommend 50 ms: ten samples inside today's 500 ms,
  and short enough that a healthy run settles sooner than it does now.
- **Are two agreeing samples enough?** A pause in scheduling could fake one.
  Recommend two and a 50 ms gap as sufficient given the source is unthrottled —
  it produces continuously when it produces at all — and revisiting only if it
  proves otherwise.
- **Does this hide a regression that a fixed settle would catch?** A slow settle
  would now pass where it once failed. Recommend accepting that: the test's
  claim is that production stops, not how quickly, and `BOUNDED_AHEAD` already
  bounds how far ahead it may get.
