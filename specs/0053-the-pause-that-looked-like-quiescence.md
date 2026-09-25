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

- [x] **`spec-0053-settle-as-long-as-the-stall`** ([#145](https://github.com/matthewjones372/pelican/pull/145)) — `settled(still:, within:)`
      replacing `settled(within:)`, with the caller passing `STALL_MILLIS`.
      Done when: the settle cannot return while the counter has moved within
      the last `STALL_MILLIS`, `./gradlew build` is green on 21, 23 and 25, and
      the added cost to a passing run is recorded here.

## Acceptance

```bash
./gradlew :example:test --tests "*UnboundedStreamTest*" --rerun-tasks
./gradlew build
```

## Measured

Entry one, on `48f959f`. Three runs before and three after, reading the test's
own time out of `example/build/test-results/test/`:

| settle | run 1 | run 2 | run 3 |
|---|---|---|---|
| two agreeing samples | 1.492 s | 1.463 s | 1.139 s |
| still for `STALL_MILLIS` | 1.642 s | 1.661 s | 1.640 s |

**The cost is ~290 ms, not the half second predicted.** The old settle did not
return after one 50 ms sample: it ran while the buffers filled and then agreed,
so part of the 500 ms was already being paid. Instrumenting the settle directly
shows it now returns after 1152, 1052 and 1060 ms — roughly 550 ms of the
counter still climbing, then the 500 ms of stillness the fix requires.

The spread is the other half of the result. Before, the three runs vary by
353 ms; after, by 21 ms. The settle is now floored, so what used to be the
variable part of the test — *when* a pause happened to fall — no longer moves
the total. A test whose duration is constant is also a test whose verdict does
not depend on the machine's mood, which is the property the fix was for.

### The stale comment was worse than stale

The last open question asked why CI buffered 156,222 when `BOUNDED_AHEAD`'s
comment claimed "around twenty thousand small frames in practice, and this is
ten times that". Probing the settle's return value answers it:

```
0053-PROBE settled=148018 afterMillis=1152
0053-PROBE settled=148018 afterMillis=1052
0053-PROBE settled=148018 afterMillis=1060
```

**148,018, identical across three runs.** Not a loaded runner behaving oddly —
this is simply what the buffers between the handler and a stalled socket hold.
The CI figure of 156,222 is the same number with a different socket under it.

So the comment was not merely out of date, it was wrong by a factor of seven in
the direction that matters: `settled shouldBeLessThan BOUNDED_AHEAD` has about
a third of headroom, not ten times. That assertion is much closer to failing
than its own comment claimed, and nobody would have known from reading it. The
comment now states the measured figure.

Raising the constant is left alone deliberately — it is a judgement about what
the test should tolerate, not part of making the settle honest, and the number
to raise it *to* now exists.

### Raising the bound

The finding above left `BOUNDED_AHEAD` with a third of headroom instead of the
ten times its comment claimed, and recommended deciding separately. Decided:
**1,000,000**.

The reason it took a second measurement is that the first only found one end.
`BOUNDED_AHEAD` guards a two-sided failure — too low and a busy runner's buffers
trip it, too high and a stream nothing back-pressures slips under it — and the
number was being argued about against the buffering end alone. So the other end
was measured: an unthrottled source into `Sink.ignore`, nothing pulling back,
timed over five million elements and scaled to the ~1050 ms the settle occupies.

| in a 1050 ms window | measured |
|---|---|
| buffered, with a stalled reader | 148,018 / 148,018 / 148,018 local, 156,222 CI |
| **collected**, nothing back-pressuring | **7,075,471 / 8,823,529 / 10,214,007** |

Forty-five times apart, and the old constant sat almost against one wall: 1.35×
over the buffering it had to clear, 35× under the collection it had to catch. It
would have flaked on a runner buffering a third more than this one long before
it ever caught a collected stream.

A million is the midpoint in the ratio that matters — 6.4× over the worst
buffering seen, 7.1× under the slowest collection seen. That is close enough to
the geometric mean of the two poles (≈1,045,000) to call it that, and it is a
number a reader can hold.

**The probe had to be written twice.** The first attempt cancelled each run's
`CompletionStage` and started the next; `cancel` does not stop a Pekko stream, so
runs two and three competed with run one and read 275,104 and 93,979 against its
6.4 million. Only the first run was clean. Taking a fixed count and timing it,
with a fresh actor system per run, gives the three figures above — consistent to
within 40%, where the cancelled version varied by seventy times.

## Open questions

- ~~**Should `BOUNDED_AHEAD` rise above 200,000?**~~ **Answered: yes, to
  1,000,000.** See **Raising the bound** below — the second end got measured
  too, and 200,000 turned out to be badly placed rather than merely tight.
- **Is `STALL_MILLIS` the right stillness, or should both grow?** Still open,
  and deliberately. 500 ms on each side is a second per run; keep it and revisit
  only if it recurs. **Seventeen green `main` runs since the fix, nine of them
  since the bound rose, each running this test on three JDKs — and that is not
  enough to close this.** 0048's settle also looked settled for longer than that
  before a lull faked a sample. Closing this question on a day of green is the
  mistake this spec exists to record, so it stays open until either a recurrence
  or a stretch long enough to mean something.
- ~~**Should `BOUNDED_AHEAD` be the primary assertion?**~~ **Both, as
  recommended.** Boundedness alone would pass for a producer that never stops
  but stays under the ceiling, which is not what the test's name says — and the
  measurement since has made the pair sharper rather than redundant, since the
  two ends now bound a 45× gap rather than sitting against one wall.
- ~~**Why did this run buffer 156,222 when the constant's comment says "around
  twenty thousand small frames in practice"?**~~ **Answered: the comment was
  wrong.** 148,018 locally, three times over. See **Measured**.
- ~~**Does 0048 need a `Measured` section?**~~ **No — but it needed to say this
  spec exists, and did not.** Retro-fitting numbers to a shipped spec is not
  worth it; this page is the record. What was worth it is that 0048's own third
  open question — *"Are two agreeing samples enough? … revisiting only if it
  proves otherwise"* — had proved otherwise, and nothing on that page said so.
  It is struck there now, pointing here. A record nobody can find from where
  they are standing is not a record.
