# 0072 — A timeout claimed without a clock

## Problem

`HealthTest`'s hung-check case asserts the probe answers in under a second
(`tookMillis shouldBeLessThan 1_000` against a 100ms timeout). It runs in
`./gradlew build`, and `org.gradle.parallel=true` puts it beside every other
module's tests, so the bound measures the machine as well as the code.

The same shape has already failed twice in this stack: `HttpCheckTest` timed
out on a cold WireMock in CI, and `KafkaCheckTest` took 3083ms against a 3000ms
bound in a local build. Both were fixed by hand. `HealthTest` has not failed
yet; it is the one left. The Kafka and Redis container tests assert an upper
bound too, and the CI step runs those modules' container tasks in parallel.

Pelican has no rule about this. Proofload's AGENTS.md does: a test that
measures elapsed time cannot share a machine.

## Not doing

- **A `timing` tag and task**, as proofload has. One test does not earn the
  machinery, and the gate below proves more than the clock did.
- **Changing how checks time out.** Behaviour stays; only the tests change.
- **The other suites' clocks** (`UnboundedStreamTest`, the client tests). A
  sweep of those is its own spec if this rule is adopted.

## Shape

Claim that the probe did not wait for the check, rather than how long it
took:

```kotlin
@Test
@Timeout(30)                                  // a hang guard, not a claim: see below
fun `a probe answers a check that never returns, and calls it timed out`() {
    val released = CountDownLatch(1)          // never counted down by the test
    val health = health(detail = true) {
        ready("slow", timeout = 100.milliseconds) { released.await(); Status.Pass }
    }

    val started = System.nanoTime()
    val response = clientFor(health).response(health.ready, Unit)

    // The check cannot have finished: nothing released it. An answer at all is the claim.
    released.count shouldBe 1
    response.status shouldBe 503
    // A slow machine only adds time, so a lower bound is safe to share.
    (System.nanoTime() - started).nanoseconds shouldBeGreaterThanOrEqualTo 100.milliseconds
}
```

- The upper bound goes. The gate proves the probe answered without the
  check; the lower bound proves it waited for the timeout first.
- `@Timeout(30)` stops a regression that waits for every check from hanging
  the build. It bounds a failure, not the code: thirty seconds is far past
  any loaded machine, and it never decides a pass.
- The CI container step runs with `--no-parallel`, so the Kafka and Redis
  upper bounds, which are 0069's Done-when, run one module at a time.
- AGENTS.md gains one rule under Testing: an elapsed-time upper bound is not
  asserted in a task that shares the machine; assert ordering with a gate, or
  a lower bound.

## Why this shape

A gate is a stronger claim than a clock. "Under a second" passes on a probe
that waited 900ms for a check it should have abandoned; "the check is still
blocked and the answer is here" does not. The alternative is proofload's:
tag the test `timing`, exclude it from `build`, run it alone in its own task
and keep it out of Kover. That keeps the upper bound but adds a task, a Kover
exclusion and a CI step for one assertion. Recommended: the gate.

## Stack

- [ ] **`spec-0072-gate`** — `HealthTest`'s hung-check case asserts the gate
      and the lower bound, not the upper bound.
      Done when: the test passes with the machine loaded (run `build` twice in
      parallel), and fails if `Health` is changed to wait for every check.
- [ ] **`spec-0072-rule`** — `--no-parallel` on the CI container step, and the
      rule in AGENTS.md.
      Done when: the CI log shows the container tasks running one after another.

## Acceptance

```bash
./gradlew :pelican-health:test
./gradlew containerTests containerTestsOldestKafka --no-parallel
```

## Decisions

Each open question in the draft took its recommendation.

1. **The container tests' upper bounds** stay. They are 0069's Done-when and
   generous (5s against a 2s timeout); `--no-parallel` on the CI step runs
   them one module at a time.
2. **The AGENTS.md rule** is adopted, not only the test fixed: two of the
   three such bounds in the health stack have already failed once.
3. **The other suites' clocks** (`UnboundedStreamTest`, the client tests) are a
   separate spec, after checking which of them bound from above.
