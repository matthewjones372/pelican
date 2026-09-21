# 0050 — The timeout nobody chose

## Problem

One line governs every test in the build, `build.gradle.kts:194`:

```kotlin
systemProperty("junit.jupiter.execution.timeout.default", "60s")
```

Nothing records why. It arrives in `ddbbf40`, the repository's root commit — no
parent, 552 files, 89,200 insertions — whose message is about `client-pekko`
belonging on a kept list. No spec mentions it, no page under `docs/` mentions
it, `AGENTS.md` does not mention it. There is no `@Timeout` anywhere else in
the tree, so this is the only timeout the repository has and it governs all of
it: 1,564 test methods across 177 classes in twenty modules, and — because
`junit.jupiter.execution.timeout.default` cascades to
`...beforeall.method.default` and `...afterall.method.default` — every fixture
too.

Measured from a full local `build` (total suite time 310.7s):

| slowest method | time | of budget |
|---|---|---|
| `DoesNotCompileTest > a failure of the same payload type compiles, whichever end` | 20.68s | 34% |
| `BorrowedSystemDocsTest > the endpoints and the document are both served on a borrowed system` | 16.94s | 28% |
| `StillCompilesTest > "endpoints"` | 14.27s | 24% |
| `GroupFilterReachesTheClientTest > an endpoint outside the group is untouched` | 10.84s | 18% |

Everything else is under 15%. So the number has never fired, and on this
machine it is roughly three times the slowest thing it governs.

It is doing two jobs and is the wrong number for both.

**As a hang-breaker** — stop one wedged test burning the whole CI job — 60s is
far tighter than it needs to be. Nothing here legitimately runs for a minute,
so the backstop could sit at minutes and still catch a hang long before the
job's own limit.

**As a performance budget** it is an assertion nobody wrote and nobody owns.
The three tests nearest it invoke the Kotlin compiler or bind a socket: the
ones whose duration is most the machine's business. A runner three times slower
than this container fails `DoesNotCompileTest`, and the failure reads as a
timeout rather than as "this runner is slow".

That is 0048's failure mode — a timing constant chosen by feel, fine until the
machine is slower — with the whole build in its blast radius rather than one
test.

There is a second cost. The timeout is enforced by interrupting the running
thread: `SameThreadTimeoutInvocation$InterruptTask` calls `Thread.interrupt()`,
and `SameThreadTimeoutInvocation` then reports a `TimeoutException` with
whatever the interrupt broke attached to it. An interrupt delivered mid-flight
into Pekko or into the compiler produces a second, unrelated-looking failure
beside the real one. Spec 0049 spent real effort establishing that this
mechanism was *not* the cause of its flake; that effort was only necessary
because the mechanism is here at all.

## Not doing

- **Not removing the timeout.** A suite with no backstop lets one wedged test
  burn the CI job, which is worse than a number nobody chose.
- **Not adding `@Timeout` across the tree.** 1,564 annotations to replace one
  line is not an improvement.
- **Not making any test faster.** The runtimes above are the subject, not the
  target.
- **Nothing to 0049.** This mechanism was ruled out as the cause of that flake.
  The two are unrelated and neither blocks the other.

## Shape

Split the two jobs, and write down which one the number is doing.

```kotlin
// A backstop, not a budget. Nothing here legitimately runs for minutes, so a
// test that does is wedged rather than slow; the slowest measured method is
// 20.7s (spec 0050). A tighter number would be a performance assertion, which
// the compiler tests would fail on a slow runner.
systemProperty("junit.jupiter.execution.timeout.default", "5m")
```

Where hanging rather than slowness is the real risk — a socket that never
accepts, a stream that never completes — an explicit `@Timeout` on that test
says so, close to the test, at a value chosen for it.

## Why this shape

A backstop and a budget want opposite numbers. A backstop should be far above
anything real so it never fires except on a genuine hang, and it costs nothing
to set generously because only a hang pays it. A budget should be close to the
real duration or it asserts nothing — but a budget on wall-clock time is a
claim about the machine, and this suite runs on three JDKs across containers
and GitHub runners of unknown load. The repository has already decided how it
feels about that: 0048 replaced a guessed wall-clock settle with an observation
precisely because the guess failed on a slower machine.

The alternative is to keep 60s and write down why 60s. That is cheaper and
honest, and if someone did measure this once it is the right answer. But
nothing in the tree suggests anyone did, and a documented guess is still a
guess that `DoesNotCompileTest` will eventually fail on a slow runner.

## Stack

- [ ] **`spec-0050-backstop-not-budget`** — the one line moves, carrying the
      reason and the measurement in a comment.
      Done when: `./gradlew build` is green on 21, 23 and 25, and the comment
      names the slowest measured method so the next person can re-check it.
- [ ] **`spec-0050-timeouts-where-hanging-is-real`** — explicit `@Timeout` on
      the tests that can genuinely wedge rather than merely run long.
      Done when: each annotation's value is justified by that test's measured
      duration, not by a shared constant.

The second entry is the first thing to cut. It is worth doing only if the
answer to the last open question below is a short, specific list.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **What backstop value?** Recommend 5 minutes: fifteen times the slowest
  measured method, so load or a slow runner cannot reach it, and still far
  under the CI job's own limit. 2 minutes would also do and is less of a wait
  when it does fire.
- **Is the root-commit reading right?** `ddbbf40` has no parent and the history
  was squashed or re-rooted there, so the line may predate this repository and
  have had a reason in whatever it was squashed from. Worth one question to
  whoever did the squash before treating it as unowned.
- **Should lifecycle methods get a different number from tests?** JUnit allows
  `...beforeall.method.default` separately. A fixture that binds a server and
  terminates an actor system is doing different work from a test. Recommend
  one number until something needs two.
- **Which tests can actually hang?** Needed before entry two is worth building.
  Candidates: anything binding a socket, `UnboundedStreamTest`, and `stop()`
  itself, whose wait has no deadline — which is 0049's last open question and
  the one place these two specs touch.
