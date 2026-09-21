# 0049 — The stop that reports an interrupt

## Problem

Three CI sightings, three different tests, one trace:

```
java.util.concurrent.CompletionException at CompletableFuture.java:368
    Caused by: java.util.concurrent.ExecutionException at Promise.scala:99
        Caused by: java.lang.InterruptedException at AbstractQueuedSynchronizer.java:1167
```

| where | reported as | JDK |
|---|---|---|
| #122 | `SwaggerUiOAuthTest` | 25 — passed on re-run |
| local `:pelican-pekko:test` | not recorded | not recorded — passed on re-run |
| #126 | `RequestLogTest > executionError` | 25 — passed in a second run of the same commit |

The #126 sighting rules out the diff: `48d4f6d` ran the `build` workflow twice,
from `push` and from `pull_request`, and JDK 25 failed in
[35609715893](https://github.com/matthewjones372/pelican/actions/runs/35609715893)
and passed in
[35609926805](https://github.com/matthewjones372/pelican/actions/runs/35609926805).

The middle frame names the mechanism. `Promise.scala:99` in scala-library
2.13.17 — the version `:example:testRuntimeClasspath` resolves — is the line of
`Promise.resolve` that wraps a `ControlThrowable`, an `Error`, or an
`InterruptedException` in `new ExecutionException("Boxed Exception", t)`.

So the interrupt was what a `Future` was **completed with**. That excludes the
obvious reading: a test thread interrupted while parked in `join()` throws
`CompletionException` caused *directly* by `InterruptedException`, with no
`ExecutionException` between. A Pekko-owned thread was interrupted, the
interrupt became a future's failure, and `join()` reported it faithfully.
Nothing here is a test being flaky; something reports a failure it should not.

Every sighting shares one call site. `executionError` is Gradle's name for a
failure outside a test method, and `RequestLogTest` has no test by that name —
it has `@AfterAll fun shutdown() = server.stop()`. `SwaggerUiOAuthTest` calls
`server.stop()` in a `finally`. `Server.kt:33`:

```kotlin
fun stop() { stopAsync().toCompletableFuture().join() }

fun stopAsync(): CompletionStage<Unit> =
    binding.unbind()
        .thenCompose { /* ... */ system.terminate(); system.getWhenTerminated() }
        .thenApply { stopped.countDown() }
```

Nineteen test classes bind a real server and every one of them ends here. That
is the shape of the evidence: rare, spread across unrelated tests, never twice
on the same one.

The suspect is in that chain. `thenCompose` and `thenApply` without `Async` run
on whichever thread completed the previous stage — here, a thread of the very
`ActorSystem` the middle stage is terminating. The chain asks the system being
shut down to carry its own shutdown's continuations.

## Not doing

- **No retry, anywhere.** Not a Gradle `maxRetries`, not a re-run rule in CI.
  The failure is a real one being manufactured; hiding it hides the next real one.
- **No `@Disabled`, no quarantine, no ignored test.**
- **Nothing to route-testkit tests.** They bind no socket, never call `stop()`,
  and have never shown this.
- **No change to `block()`.** It parks on a `CountDownLatch` and is not in the
  chain.
- **No fix in entry one.** It buys the diagnosis, as 0039 and 0046 bought their
  numbers.

## Shape

`stop()` returns, or throws something a caller can act on. An interrupt landing
inside Pekko's own teardown — after `unbind()` has already completed, so the
port is released and the server is by every caller-visible measure stopped — is
not a failure `stop()` should invent for its caller.

```kotlin
// the call that must not throw because Pekko interrupted itself
server.stop()
```

Two ways to get there:

```kotlin
// (a) keep the continuations off the system that is going away
binding.unbind()
    .thenComposeAsync({ /* terminate */ }, offSystem)
    .thenApplyAsync({ stopped.countDown() }, offSystem)

// (b) say what a termination interrupt means, once
private fun CompletionStage<*>.ignoringTeardownInterrupt(): CompletionStage<Unit> = ...
```

## Why this shape

(a) addresses the cause if the diagnosis holds, and decides nothing about what
`stop()` promises. Its cost is an executor `stopAsync` has to own — a thread
pool to stop a thread pool.

(b) is narrower than it looks: it would swallow a boxed `InterruptedException`
only from the termination stage, only after `unbind()` succeeded. But it is
still a decision that a class of failure is uninteresting, and if the cause is
not shutdown-interrupts-itself, it hides that rather than fixing it.

Recommend (a), conditional on entry one confirming the cause; (b) only if entry
one shows the interrupt comes from somewhere `stopAsync` does not control. Doing
(b) first would end the flake and end the investigation with it.

## Stack

- [ ] **`spec-0049-name-the-interrupt`** — a test that stops many bound servers
      in a loop, with the boxed cause's full stack recorded rather than
      summarised, until it reproduces.
      Done when: a captured trace names the thread and the executor that
      interrupted it, written into this spec under **Measured**, and says which
      of `unbind()` and `getWhenTerminated()` failed.
- [ ] **`spec-0049-stop-off-system`** — whichever of (a) or (b) **Measured**
      supports.
      Done when: the entry-one loop passes where it failed, and `./gradlew build`
      is green on 21, 23 and 25.

## Acceptance

```bash
./gradlew :pelican-pekko:test --tests "*StopUnderTeardownTest*" --rerun-tasks
./gradlew build
```

## Open questions

- **Which future fails?** `unbind()` or `getWhenTerminated()`. The trace does
  not say and the answer changes the fix. Recommend instrumenting both rather
  than reasoning about it — this is entry one's whole job.
- **Which executor interrupts?** Candidates: Pekko shutting down a dispatcher
  whose threads still hold continuations; `CoordinatedShutdown` hitting a phase
  timeout; Gradle's test worker tearing down after the last test. Recommend
  ruling out Gradle first by reproducing outside a test.
- **Why only JDK 25?** Both CI sightings are 25, 21 and 23 have never shown it,
  and the local one's JDK was not recorded. That may be a real scheduling change
  or may be three coin flips. Recommend treating it as unexplained rather than
  as a JDK bug, and not gating the fix on an answer.
- **Should `stopAsync` bound its wait?** `getWhenTerminated()` has no deadline
  here, so a system that never terminates parks `stop()` forever. Recommend
  leaving it out of this spec unless entry one finds a timeout is the thing
  being interrupted.
