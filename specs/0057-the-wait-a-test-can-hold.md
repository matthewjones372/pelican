# 0057 — The wait a test can hold

## Problem

`RetryingTransport` schedules every backoff on
`CompletableFuture.delayedExecutor`, chosen inside a private function. Nothing
outside the class can see or replace it.

So the one claim about time in `RetryTest` is asserted with the wall clock:

```kotlin
val waiting = retryPolicy { jitter = 0.0; initialBackoff = Duration.ofMillis(500) }
val answer = transport.retrying(waiting).send(get).toCompletableFuture()

answer.cancel(true)
Thread.sleep(1_000)

attempts.get() shouldBe 1
```

That costs a second on every run. It also only proves that no retry happened
*within* that second. A retry that fired at 1.1 s would pass. The same limit
applies to anyone testing their own client wrapped in `retrying()`: they can
choose the policy, but not the clock it waits on.

## Not doing

- **No virtual clock for `RetryPolicy`.** Its waits are already pure values
  (`retryDelay` returns a `Duration`), and `RetryTest` asserts them directly.
- **No change to jitter's randomness.** `jitter = 0.0` already makes a wait
  exact, and the tests use it.
- **No helper in `pelican-test` yet.** The fake scheduler lives in `RetryTest`
  until a second caller needs one.
- **No coroutine or virtual-thread variant.** Core stays on `CompletionStage`.

## Shape

A one-method interface in core, passed to the transport. Its default is
today's behaviour:

```kotlin
/** Runs [task] once [after] has passed. */
fun interface RetryScheduler {
    fun schedule(after: Duration, task: Runnable)

    companion object {
        /** The JDK's shared daemon timer, which is what every retry used before this. */
        val jdk: RetryScheduler = RetryScheduler { after, task ->
            CompletableFuture.delayedExecutor(after.toMillis(), TimeUnit.MILLISECONDS).execute(task)
        }
    }
}

class RetryingTransport(
    private val delegate: ClientTransport,
    private val policy: RetryPolicy,
    private val scheduler: RetryScheduler,
) : ClientTransport {
    constructor(delegate: ClientTransport, policy: RetryPolicy = retryPolicy()) :
        this(delegate, policy, RetryScheduler.jdk)
}

fun ClientTransport.retrying(policy: RetryPolicy = retryPolicy()): ClientTransport
fun ClientTransport.retrying(policy: RetryPolicy, scheduler: RetryScheduler): ClientTransport
```

The test then takes no time and proves a stronger claim:

```kotlin
val pending = mutableListOf<Runnable>()
val answer = transport.retrying(waiting, RetryScheduler { _, task -> pending += task }).send(get)

answer.toCompletableFuture().cancel(true)
pending.forEach(Runnable::run)   // the retry's moment arrives, after the cancel

attempts.get() shouldBe 1
```

## Why this shape

The scheduler goes on the transport, not the policy. A policy is a value
describing *when* to retry. What does the waiting is an effect, and it belongs
with the decorator that already owns the other effect, `delegate.send`. The
alternative, a `scheduler` setting in `retryPolicy { }`, would make two
policies that differ only by a test fake unequal, for no gain.

A `fun interface` rather than `ScheduledExecutorService`: a fake executor
service has to implement a dozen methods, and this needs one. The JDK service
still fits in one line:
`RetryScheduler { d, t -> ses.schedule(t, d.toMillis(), MILLISECONDS) }`.

The scheduler is an overload beside each existing signature, not a defaulted
parameter on it, so the `pelican-core.api` dump only gains entries. This draft
first proposed `@JvmOverloads`, which keeps the two-argument JVM constructor
but not the synthetic `$default` constructor and `retrying$default` that
Kotlin callers are compiled against. Adding a parameter changes both of their
signatures.

## Stack

- [ ] **`spec-0057-retry-scheduler`**: `RetryScheduler`, the new parameter on
      `RetryingTransport` and `retrying`, `RetryTest`'s sleep replaced with a
      held task, a `FrozenCallSites` line, and `docs/reference.md`.
      Done when: `RetryTest` has no `Thread.sleep`, the cancel test fails if
      the `answer.isDone` guard in `attempt` is removed, and `apiCheck` shows
      additions only.

## Acceptance

```bash
./gradlew :pelican-core:test --tests "*RetryTest*"
./gradlew apiCheck
./gradlew build
```

## Open questions

- ~~**Should `schedule` return a handle so a cancel can remove the pending
  wait?**~~ **No.** The task still fires and returns early on `answer.isDone`,
  which holds a timer entry for at most `maxBackoff` (or `retryAfterCap`). One
  method is what makes the fake a lambda.
- ~~**`RetryScheduler` or a general name like `Scheduler`?**~~
  **`RetryScheduler`**, until something other than retry needs one. A general
  name in core is an invitation.
- ~~**Should `RetryScheduler.jdk` be public?**~~ **Yes**, so a wrapper (one that
  logs or meters waits) can delegate to it rather than copy it.
- ~~**Should the tests using `initialBackoff = Duration.ZERO` switch to the
  fake?**~~ **No.** These are `RetryTest`'s decorator cases and
  `RetryingTransportTest` in `pelican-client-pekko`. A zero wait already costs
  nothing, and they still hop to the JDK timer thread, which is the real path.
