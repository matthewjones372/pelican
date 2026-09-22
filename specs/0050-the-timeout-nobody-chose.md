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
beside the real one.

That made it a fair suspect for 0049's `InterruptedException`, and ruling it
out took work: on every exit path `SameThreadTimeoutInvocation` checks whether
the interrupt task ran and, if it did, throws a `TimeoutException` from
`TimeoutExceptionFactory` with the original attached. A fired timeout is
reported as `TimeoutException: execution timed out after 60000 ms`, and all
three of 0049's sightings report a bare `CompletionException` — so this is not
what interrupted those threads. That investigation was only necessary because
the mechanism is here at all.

## Not doing

- **Not removing the timeout.** A suite with no backstop lets one wedged test
  burn the CI job, which is worse than a number nobody chose.
- **Not adding `@Timeout` across the tree.** 1,564 annotations to replace one
  line is not an improvement.
- **Not making any test faster.** The runtimes above are the subject, not the
  target.
- **Nothing to 0049.** That flake's interrupter is the JDK's own `ForkJoinPool`,
  established there. The two are unrelated and neither blocks the other.

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

- [x] **`spec-0050-backstop-not-budget`** — the one line moves, carrying the
      reason and the measurement in a comment.
      Done when: `./gradlew build` is green on 21, 23 and 25, and the comment
      names the slowest measured method so the next person can re-check it.
- [ ] ~~**`spec-0050-timeouts-where-hanging-is-real`**~~ — not happening; see
      Measured. — explicit `@Timeout` on
      the tests that can genuinely wedge rather than merely run long.
      Done when: each annotation's value is justified by that test's measured
      duration, not by a shared constant.

The second entry is the first thing to cut. It is worth doing only if the
answer to the last open question below is a short, specific list.

## Measured

Re-measured on `1d225d5`, after #134 made `Test` tasks run on the JDK that
launched the build. The first draft's figures were taken when every job ran 21
whatever its label, so they were worth re-taking; they held.

| slowest method | then | now |
|---|---|---|
| `DoesNotCompileTest > a failure of the same payload type compiles, whichever end` | 20.68s | **18.81s** |
| `BorrowedSystemDocsTest > the endpoints and the document are both served on a borrowed system` | 16.94s | 13.43s |
| `GroupFilterReachesTheClientTest > an endpoint outside the group is untouched` | 10.84s | 8.66s |
| `StillCompilesTest > "endpoints"` | 14.27s | 8.05s |

1,570 test methods; everything outside the top two is under 15% of the old 60s
budget. The backstop moves to **5 minutes**, sixteen times the worst real case,
which is the first open question's recommendation taken as written.

Nothing here has ever hit the timeout, so this entry changes no observable
behaviour. What it changes is what the number claims: a backstop that fires only
on a hang rather than a wall-clock budget that a slow runner fails.

### Entry two does not happen

The entry was conditional: *"worth doing only if the answer to the last open
question below is a short, specific list."* The list was surveyed. **It is
empty, not short** — and the two candidates the spec named are both already
bounded.

What a `@Timeout` would add is a deadline where nothing else has one. Every
category that could wedge has one:

| where a wait could hang | what already bounds it |
|---|---|
| a request to a server the tests bound | Pekko's own `request-timeout = 20 s`, then `idle-timeout = 60 s` |
| a raw `Socket` read | `soTimeout = 10_000` at all six sites that open one |
| a `CountDownLatch` | all four `await` calls pass a timeout |
| `server.stop()` | `STOP_TIMEOUT` via `awaitTerminated`, from 0049 |
| `UnboundedStreamTest` | `settled` fails on its deadline; reads have `soTimeout`; `stop()` as above |

The Pekko figures are read from the `reference.conf` in
`pekko-http-core_2.13-1.4.0.jar` on this build's classpath, not from memory —
the repository sets none of them, so the defaults are what applies. Both are
*tighter* than the 5-minute backstop entry one just set, which means the
framework underneath these tests already fails them before JUnit would.

**`UnboundedStreamTest` was the spec's own named candidate, and it cannot
hang.** Every wait in it has a deadline, one of which (`settled`) fails with a
sentence naming what the source was still doing. Adding `@Timeout` would give
it a second, worse deadline that reports "timed out" instead.

That leaves the 64 `.join()` and 25 `.get()` calls across 23 test files, which
is where a deadline is genuinely absent. But they are the argument *against* the
entry, not for it, on two counts. They are not a short list — that is most of
the integration suite, and 89 annotations is the "1,564 annotations to replace
one line" the spec already refused, scaled down but not in kind. And what would
hang there is an in-memory transport or our own future never completing, which
is a deadlock: `@Timeout` would report it as a timeout and send the next reader
looking for something slow, which is the misdirection this spec was written
about in the first place.

The compiler tests are the sharpest case. `DoesNotCompileTest` at 18.8s and
`StillCompilesTest` at 8.1s are the slowest methods in the suite and the ones
with nothing underneath them — exactly where a `@Timeout` is most tempting and
most wrong, since the spec's own argument is that a number close to their real
duration is a performance assertion a slow runner fails.

So 0050 closes on one line changed. The last open question is answered, and the
answer was that the work it gated was not worth doing.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **What backstop value?** ~~Recommend 5 minutes~~ — **taken: 5 minutes**,
  sixteen times the slowest measured method. 2 minutes would also have done and
  is less of a wait when it does fire; changing it is one line.
- **Is the root-commit reading right?** `ddbbf40` has no parent and the history
  was squashed or re-rooted there, so the line may predate this repository and
  have had a reason in whatever it was squashed from. Worth one question to
  whoever did the squash before treating it as unowned.
- **Should lifecycle methods get a different number from tests?** JUnit allows
  `...beforeall.method.default` separately. A fixture that binds a server and
  terminates an actor system is doing different work from a test. Recommend
  one number until something needs two.
- ~~**Which tests can actually hang?**~~ **Answered: none that are not already
  bounded**, the named candidates included. Both `stop()` and
  `UnboundedStreamTest` turned out to be the pattern rather than the exception —
  every category has a deadline, and the ones that do not are deadlocks a
  `@Timeout` would mislabel. See **Measured**; entry two is cut.
