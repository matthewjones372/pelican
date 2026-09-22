# 0053 — The pause that looked like quiescence

## Problem

`UnboundedStreamTest > a consumer that stops reading stops the producer` failed
again, on #143's `build (25)`:

```
the source produced 53912 elements with nobody reading
expected:<102310L> but was:<156222L>
```

Spec 0048 replaced that test's fixed 500 ms sleep with settle-by-observation.
What landed returns the moment **two consecutive samples agree**:

```kotlin
while (System.nanoTime() < deadline) {
    Thread.sleep(SAMPLE_MILLIS)   // 50 ms
    val now = get()
    if (now == last) return now
    last = now
}
```

The numbers say what happened: `settled` returned 102,310, and 500 ms later the
counter read 156,222. The producer had not stopped. It paused for under 50 ms —
a scheduling hiccup, a GC, a socket buffer draining — two reads agreed, and the
settle took that for quiescence.

0048 wrote this risk down and chose to wait for evidence:

> **Are two agreeing samples enough?** A pause in scheduling could fake one.
> Recommend two and a 50 ms gap as sufficient given the source is unthrottled —
> it produces continuously when it produces at all — and revisiting only if it
> proves otherwise.

It has proved otherwise. The premise — *it produces continuously when it
produces at all* — does not hold on a loaded CI runner.

**But the run never breached the bound, and that is the important part.**
`BOUNDED_AHEAD` is 200,000. The settle read 102,310 and the final count was
156,222: both under it, so the run's second assertion —

```kotlin
settled shouldBeLessThan BOUNDED_AHEAD
```

— passed. Back-pressure was working. The producer was bounded by the buffers
between it and a stalled reader, exactly as the test's name claims. What failed
was a strictly stronger claim the test makes in passing: that production has
stopped *at this instant* and stays stopped for the next 500 ms.

So the test is not detecting a defect. It is detecting its own detector.

## Not doing

- **No retry, no tolerance, no `@Ignore`.** A threshold on "nearly nothing
  produced" is the same guess moved, which 0048 already rejected.
- **No larger `SETTLE_TIMEOUT`.** It is a deadline, not a delay; the settle
  returned early rather than ran out.
- **Not deleting the quiescence assertion.** Production really does stop, and a
  test that only checked boundedness would pass against a producer that idles
  and resumes forever.
- **Nothing to the second test** in that file, which reads to a count and has
  no timer in it.
- **Nothing to 0052.** Different test, different mechanism, unrelated.

## Shape

Make the settle criterion and the assertion criterion the same duration. The
test asserts nothing is produced for `STALL_MILLIS`; quiescence should mean the
counter has already been unchanged for `STALL_MILLIS`.

```kotlin
/** Settled when the counter has not moved for [still]. A pause shorter than that cannot fake it. */
private fun AtomicLong.settled(still: Duration, within: Duration): Long {
    val deadline = System.nanoTime() + within.inWholeNanoseconds
    var last = get()
    var since = System.nanoTime()
    while (System.nanoTime() < deadline) {
        Thread.sleep(SAMPLE_MILLIS)
        val now = get()
        if (now != last) {
            last = now
            since = System.nanoTime()
        } else if (System.nanoTime() - since >= still.inWholeNanoseconds) {
            return now
        }
    }
    fail("the source was still producing after $within with nobody reading: $last elements")
}
```

Called as `produced.settled(still = STALL_MILLIS.milliseconds, within = SETTLE_TIMEOUT)`.

## Why this shape

It makes the test self-consistent. Today it accepts 50 ms of stillness as proof
of quiescence and then demands 500 ms of it — so the acceptance threshold is ten
times weaker than the assertion, and the gap is exactly where the failure lives.
Requiring the same window on both sides closes it: a pause that fools the settle
would have to be long enough to satisfy the assertion anyway.

The alternative is to require *N* agreeing samples. That is the same idea with
an extra number to choose, and it says nothing about how the number relates to
what is then asserted. Ten samples at 50 ms is 500 ms by another name; fewer is
a shorter guess than the assertion it guards.

This is still a wall-clock test. A pause longer than `STALL_MILLIS` would still
fool it, and nothing here makes it a proof. What it does is stop the test
failing on pauses shorter than the thing it measures, which is the failure
actually observed.

The honest cost: a passing run gets slower. Today the settle returns after two
agreeing samples, typically ~100 ms; after this it cannot return in under
`STALL_MILLIS`. Call it half a second added to one test.

## Stack

- [ ] **`spec-0053-settle-as-long-as-the-stall`** — `settled(still:, within:)`
      replacing `settled(within:)`, with the caller passing `STALL_MILLIS`.
      Done when: the settle cannot return while the counter has moved within
      the last `STALL_MILLIS`, `./gradlew build` is green on 21, 23 and 25, and
      the added cost to a passing run is recorded here.

## Acceptance

```bash
./gradlew :example:test --tests "*UnboundedStreamTest*" --rerun-tasks
./gradlew build
```

## Open questions

- **Is `STALL_MILLIS` the right stillness, or should both grow?** 500 ms on each
  side is a second per run. Recommend keeping 500 and revisiting only if it
  recurs — the same discipline 0048 used, which did produce the evidence, just
  later than anyone wanted.
- **Should `BOUNDED_AHEAD` be the primary assertion?** It held through the
  failure, which is an argument that it is the claim that matters and quiescence
  is the decoration. Recommend keeping both: boundedness alone would pass for a
  producer that never stops but stays under the ceiling, which is not what the
  test's name says.
- **Why did this run buffer 156,222 when the constant's comment says "around
  twenty thousand small frames in practice"?** Almost eight times the stated
  typical figure, still under the ceiling. Either the comment is stale or that
  runner behaved unusually. Worth a glance while building this, since it is the
  one number here nobody has re-measured since it was written.
- **Does 0048 need a `Measured` section?** It has none, so what its fix achieved
  was never recorded and this recurrence had nothing to be checked against.
  Recommend this spec serves as that record rather than editing a shipped spec.
