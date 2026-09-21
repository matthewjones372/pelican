# 0049 — The shutdown that interrupts itself

## Problem

Three times a green suite has gone red on one trace and back to green on a
re-run: PR #122 (`SwaggerUiOAuthTest`), PR #126 (`RequestLogTest`), and once
locally in `:pelican-pekko:test`.

```
java.util.concurrent.CompletionException at CompletableFuture.java:368
    Caused by: java.util.concurrent.ExecutionException at Promise.scala:99
        Caused by: java.lang.InterruptedException at AbstractQueuedSynchronizer.java:1167
```

#126 ran the same commit twice — 35609715893 failed, 35609926805 passed, same
SHA, minutes apart — so it is timing, not any diff.

The await is ours. `PelicanServer.stop()` (`Server.kt:34`) joins `stopAsync()`,
whose last stage is `system.getWhenTerminated()`; Gradle names it
`RequestLogTest > executionError` because that join is in `@AfterAll`. The rest
is Pekko's and the JDK's, traced under **Established**.

Two things make it ours to fix. The stage fails although the system *did*
terminate — the throw comes from housekeeping Pekko runs after the guardian is
dead. And `stop()` joins with no deadline, so the other outcome of the same race
is a build that parks rather than fails; `InMemoryTransport.close()`
(`InMemory.kt:70`) already takes a timeout, and the two paths disagree for no
reason anyone wrote down.

## Not doing

- **No retry, no `@Ignore`, no quarantine.** A rerun is what hid this three
  times.
- **Nothing to Pekko's shutdown ordering.** The race is inside
  `ActorSystemImpl`.
- **No change to `stopAsync()`'s stage type or to `block()`.**
- **Not chasing the JDK.** 21 interrupts where 23 and 25 do not; that is a
  deliberate change upstream, not a bug to file from here.

## Shape

One spelling for "wait until the system is really down", with a deadline, used
by both shutdown paths:

```kotlin
/** Pekko boxes an `InterruptedException` thrown by its own termination callbacks; the system is down regardless. */
internal fun CompletionStage<*>.awaitTerminated(timeout: Duration) =
    try {
        toCompletableFuture().get(timeout.toMillis(), MILLISECONDS)
    } catch (e: ExecutionException) {
        if (!e.isBoxedInterrupt()) throw e
    }
```

Anything that is not a boxed `InterruptedException` is rethrown, and a caller
whose own thread was interrupted gets its interrupt status back.

## Why this shape

`stop()` promises the port is unbound and the system is down. Both held in every
sighting; what failed was `stopScheduler()`, which Pekko runs *after*
termination, and Scala only turns its throw into the future's result because
`InterruptedException` is not `NonFatal`. Reporting that as a failure of
`stop()` reports the wrong thing. The alternative is to let it through and have
every caller handle it — the same handler in `example/`, in `pelican-test-pekko`
and in every user's `@AfterAll`. Recommend the helper.

The deadline is the smaller half but fails loudly rather than quietly: a
`join()` on a stage Pekko may never complete is a hung build with no message.

## Stack

- [x] **`spec-0049-await-terminated`** ([#133](https://github.com/matthewjones372/pelican/pull/133)) — the helper, failing test first: a stage
      completed with `ExecutionException("Boxed Exception",
      InterruptedException())` leaves `stop()` returning normally, any other
      failure still throws, and a stage that never completes fails on the
      deadline instead of parking. `PelicanServer.stop()` and
      `InMemoryTransport.close()` both move onto it.
      Done when: those three tests pass and `./gradlew build` is green.
- [x] **`spec-0049-test-jdk`** ([#134](https://github.com/matthewjones372/pelican/pull/134)) — `Test` tasks run on the JDK that launched the
      build rather than on the `jvmToolchain(21)` compile toolchain.
      Done when: `build (23)` and `build (25)` execute tests on 23 and 25, which
      today they do not.

## Acceptance

```bash
./gradlew build
```

## Established

From the JDK 21 and Pekko 1.2.1 sources and the test-report artifact of run
35609715893, not inferred:

- **The await.** Pekko runs the termination callbacks on the default dispatcher
  (`ActorSystem.scala:979`); one, registered at `:1041`, is `stopScheduler()`
  (`:1136`) → `LightArrayRevolverScheduler.close()` (`:181`) →
  `Await.result(stop(), 5s)`. On a `PekkoForkJoinWorkerThread` that goes through
  `ForkJoinPool.managedBlock`, where `tryCompensate` releases the worker's
  running count — so the pool now looks stoppable.
- **The interrupter is the JDK's own `ForkJoinPool`.** The last actor is gone,
  so the dispatcher's `shutdownAction` (`AbstractDispatcher.scala:248`) runs
  inline on the scheduler's timer thread (`:208`) and calls
  `Dispatcher.shutdown()` (`Dispatcher.scala:125`) → `ForkJoinPool.shutdown()`.
  JDK 21's `tryTerminate` sets `STOP` and then interrupts every worker of the
  pool, including the one parked above. Nothing calls `shutdownNow()` — on the
  default dispatcher a plain `shutdown()` is enough.
- **Measured.** [`StopCheck`](../tools/flake-0049/jdk/StopCheck.java) blocks a
  worker in `managedBlock`, then calls a plain `shutdown()`. On 21.0.9 it gives
  `InterruptedException at AbstractQueuedSynchronizer.java:1167`, the exact CI
  frame; on 23.0.1 and 25.0.4, a normal return. JDK 23 moved the interrupt out
  of `shutdown()` into a `STOP` check inside `compensatedBlock`.
- **The sightings are not on JDK 25.** `jvmToolchain(21)` sets the *Java*
  toolchain, so every `Test` task runs on 21 whatever the matrix says. The
  trace's `ForkJoinPool.java:3723`/`3740` are JDK 21's lines — 23 has 3965/3983,
  25 has 4308/4326 — and [the
  probe](../tools/flake-0049/probe/ProbeConfigurator.java) installed in
  `:example:test` prints `java.version=21.0.9`. All three jobs run one runtime;
  which loses the race is chance. Hence entry two.
- **Then Scala boxes it.** `Future.andThen` catches only `NonFatal`, which
  excludes `InterruptedException`, so `Transformation.run` catches `Throwable`
  instead (`Promise.scala:539` → `:477`) and completes the promise with
  `resolve(Failure(t))` — the `ExecutionException("Boxed Exception", …)` at
  `Promise.scala:99`.
- **Entry two costs nothing else.** `./gradlew build` with every `Test` task
  forced onto 23 and then 25 by
  [`init-testjdk.gradle`](../tools/flake-0049/init-testjdk.gradle) is green on
  both, all six gates included. Nothing in the suite depends on running at 21,
  so the change is the few lines it looks like.
- **And it moves the race off two of the three jobs — but not off the third.**
  On a pool already shut down, a worker blocking 200 consecutive times through
  `managedBlock` takes no interrupt at all on 23.0.1 or 25.0.4; on 21.0.9 the
  first block throws at `AbstractQueuedSynchronizer.java:1167`. `build (21)`
  still runs tests on 21, so the flake stays reachable there and entry one is
  still the fix. JDK 23's `STOP` check inside `compensatedBlock` could throw in
  principle — it was not reachable in this shape — and it reads differently when
  it does: an `InterruptedException` with no frame below `compensatedBlock`.

**Provoking it is harder than this.** ~48,000
[create-bind-stop](../tools/flake-0049/jdk/SyntheticLoop.java) iterations across
JDK 21 and 25 — pool pinned to two threads, CPU burners, a 1ms dispatcher
`shutdown-timeout`, ticks stretched to 200ms — produced nothing, and neither did
185 consecutive runs of the whole `:example:test` suite under
[`soak.sh`](../tools/flake-0049/soak.sh) with the probe installed on the
dispatcher's worker threads, which recorded no interrupt at all. The loop is the
wrong shape: the race wants the dispatcher's *scheduled* shutdown to land inside
the scheduler's close, which a tight loop never idles long enough to arrange. CI
has hit it three times in some hundreds of runs. Budget a soak, not an
afternoon.

## Open questions

- **Swallow, or say something?** Recommend swallowing: it says nothing a caller
  can act on, and a warning would land in every CI log.
- **Does the unrun `TaskRunOnClose` tail matter?** `close()` throws before
  running the remaining tasks, so a dispatcher's executor may go unshut. Pekko's
  FJ workers idle out, so recommend no — but it is the one real consequence.
