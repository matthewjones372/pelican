# 0052 — The cancellation with no stack

## Problem

`main` is red. `GeneratedKotlinClientTest > an endpoint that answers two ways
makes the caller say which one it got()` fails intermittently with

```
org.apache.pekko.stream.SubscriptionWithCancelException$StageWasCompleted$
```

Three sightings, and the JDK is not the variable:

| where | job | JDK actually running |
|---|---|---|
| #114 | `build (23)` | **21** — before #134 every matrix job ran 21 |
| `main` `e55e59c` | `build (23)` | 23 |
| #136 | `build (25)` | 25 |

So it is not new and not JDK-specific. What changed is that #134 made 23 and 25
genuinely execute, which gave a long-standing intermittent failure two more
chances per run to appear. It was always there; `build (21)` was simply being
run three times under three names.

**The throw site is unknown, and nothing can read it off the exception.** Spec
0051 makes Gradle print the *type* it could not rebuild, which is how the
exception above is named at all. It does not give a stack — and neither would
anything else, because there is no stack to give. `StageWasCompleted` is a
`SubscriptionWithCancelException.NonFailureCancellation`, which implements
`scala.util.control.NoStackTrace` and so never captures frames; and it is a
Scala `object`, one instance constructed once, so even forcing capture records
where the singleton was initialised rather than where it was thrown. Both are
measured below.

That is the whole difficulty. Pekko's signal that a subscription was cancelled
because its stage had already finished carries no evidence of which stage, on
which side of the socket.

The test is two sequential calls through the generated client over a real
socket, and the body is fully consumed on both (`text()` reads it before the
status is examined). The transport bridges each response entity to an
`InputStream`:

```kotlin
private fun bodyStream(bytes: Source<ByteString, *>): InputStream =
    bytes.runWith(StreamConverters.asInputStream(READ_LIMIT), running)
```

and `send`'s completion has two paths that end a body early — a response
arriving after the caller gave up (`response.discardEntityBytes`) and a lost
race on `answer.complete` (`crossed.body.close()`). Either is a place a
cancellation could originate, which is a list of suspects rather than a finding.

## Not doing

- **No retry, no `@Ignore`, no quarantine.** `main` being red is the reason to
  look, not a reason to silence it.
- **Not guessing at a fix.** Three candidate throw sites and no stack is not
  enough to change `PekkoHttpTransport` on.
- **Nothing to 0049.** That is `InterruptedException` out of `ForkJoinPool`
  during actor-system *shutdown*, fixed and shipped. This is a stream
  cancellation during a live call. Same family, different mechanism.
- **Not reverting #134.** The matrix now tests what it names, which is the
  fix working, not the cause.

## Shape

**Entry one cannot be "recover the stack". There is no stack, and there cannot
be one** — established under **Measured** below. So entry one is the smallest
thing that can actually name a culprit: log at each of the three places the
transport can end a body, and see which one fires when the test fails.

```kotlin
// in PekkoHttpTransport, temporary and removed by entry two
answer.isDone -> { log("discarding: caller gave up"); response.discardEntityBytes(running) }
else -> {
    val crossed = clientResponse(response)
    if (!answer.complete(crossed)) { log("closing: lost the complete race"); crossed.body.close() }
}
```

## Why this shape

The first draft proposed a JUnit listener to capture the throwable before Gradle
serialised it, on the reasoning that Gradle had lost the frames. Gradle had not
lost them. They were never taken, and forcing them to be taken yields the wrong
site. Both are measured below.

What is left is that the throw site cannot be read off the exception at all, so
it has to be observed where our own code makes the call. Three places can end a
response body; logging all three costs a few lines and removes the guessing.

`StageWasCompleted` is also, by Pekko's own naming, a `NonFailureCancellation` —
a signal that a stage finished, not a fault. That a *non-failure* reaches a test
as a failure suggests the fix may be that the transport should not propagate one
at all. That is a hypothesis for entry two to confirm, not a licence to change
the transport now.

## Stack

- [x] **`spec-0052-name-the-canceller`** — a `CancellationTrace` transport
      decorator in `example`'s tests, recording the observer's stack when a
      Pekko cancellation surfaces. Test-scoped, so nothing published changes.
      Done when: the decorator's own tests prove it fires with frames, and the
      suite that fails in CI runs through it. **Both hold; the trace from a real
      CI failure goes under Measured when one appears.**
- [ ] **`spec-0052-the-fix`** — whatever entry one names. The standing
      hypothesis is that a `NonFailureCancellation` should not reach a caller
      as a thrown exception.
      Done when: the reproduction stops failing and `main` is green on 21, 23
      and 25 across consecutive runs.

## Acceptance

```bash
./gradlew build
```

## Measured

Three things established with a throwaway `TestWatcher` in `pelican-pekko`
throwing the real exception reflectively, on `1d225d5`.

**A `TestWatcher` does see the throwable.** The first open question asked
whether the exception surfaces on a stream thread where JUnit would never
report it. It does not — the watcher fired and named it:

```
PROBE-SAW: org.apache.pekko.stream.SubscriptionWithCancelException$StageWasCompleted$
```

**But it carries no stack, and never did.**

```
PROBE-FRAMES:
```

`SubscriptionWithCancelException$NonFailureCancellation extends RuntimeException
implements scala.util.control.NoStackTrace`, and overrides `fillInStackTrace()`
to return `this` without filling. Gradle never lost the frames; Pekko never took
them. No listener, on any thread, can recover what was not captured.

**And forcing them to be taken gives the wrong site.** Scala's `NoStackTrace`
has an escape hatch — `NoStackTrace$`'s static initialiser reads
`System.getProperty("scala.control.noTraceSuppression")`, and `fillInStackTrace`
calls the real one when it is `"true"`. With
`systemProperty("scala.control.noTraceSuppression", "true")` the stack fills, and
is useless:

```
org.apache.pekko.stream.SubscriptionWithCancelException$NonFailureCancellation.<init>(SubscriptionWithCancelException.scala:41)
org.apache.pekko.stream.SubscriptionWithCancelException$StageWasCompleted$.<init>(SubscriptionWithCancelException.scala:43)
org.apache.pekko.stream.SubscriptionWithCancelException$StageWasCompleted$.<clinit>(SubscriptionWithCancelException.scala:43)
java.base/java.lang.Class.forName0(Native Method)
```

It ends at `<clinit>`. `StageWasCompleted` is a Scala `object`: one instance,
constructed once, so its stack records where the singleton was first touched —
here, the probe's own `Class.forName`. In production it would record whichever
code first initialised the class, which has nothing to do with where it was
later thrown. The property is not worth setting.


### Entry one, as built

The draft put the logging inside `PekkoHttpTransport`. It went into
`example`'s tests instead, as a `ClientTransport` decorator the failing suite
wraps its transport in — `OrdersClient(baseUrl, codecs,
CancellationTrace(PekkoHttpTransport()))`. Nothing in a published module
changes, there is no `.api` churn, and nothing has to be removed by entry two.

It catches `SubscriptionWithCancelException.NonFailureCancellation` rather than
`RuntimeException`, which is both more precise and the only legal choice:
detekt's `TooGenericExceptionCaught` excludes `Interpreter.kt`, `Server.kt` and
`Responses.kt`, and nothing else.

Its own tests prove it fires rather than assuming it will — a cancellation on
read is reported with the reader's frames and rethrown untouched, an ordinary
`IOException` is left alone, a successful read reports nothing, and a
cancellation nested under another failure is still found. That mattered:
instrumentation that silently does not fire would leave the next CI failure as
uninformative as the four before it.

### The sighting, and what it eliminated

It came on 2026-09-22, on `build (25)` of an unrelated PR (#154, narrowing
`Params.asMap`). Same test, same exception, and `CancellationTrace` fired:

```
0052-TRACE send POST http://127.0.0.1:37133/users/1/orders/submit
  cancellation: org.apache.pekko.stream.SubscriptionWithCancelException$StageWasCompleted$
  chain: java.util.concurrent.CompletionException <- …$StageWasCompleted$
    example.trace.CancellationTrace.send$lambda$0(CancellationTrace.kt:32)
    …
    io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport.send$lambda$2(PekkoHttpTransport.kt:81)
    …
    io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport.deadline$lambda$0(PekkoHttpTransport.kt:125)
```

**None of the three suspects is involved.** Entry one's shape proposed logging
the places the transport can end a body — `discardEntityBytes`, the lost
`complete` race, and `asInputStream`. The cancellation reaches none of them. It
arrives as the **failure of `singleRequest`'s own stage**: line 125 is
`deadline`'s `failure != null` branch and line 81 is `send`'s, so both are
propagating a failure that was handed to them.

That answers the second open question — **client side, and before a body
exists at all** — and falsifies the premise entry one was built on. Worth
saying plainly: the instrumentation was pointed at the wrong half of the
transport and still earned its keep, because what it ruled out is what made the
next step obvious.

The call is a `POST`, which Pekko's pool will not retry for being
non-idempotent. That is consistent with a pooled connection going away under an
in-flight request, and it is the reading the fix below assumes no more of than
it has to.

### What this entry does, and what it does not

**`asIo` now maps a `NonFailureCancellation` to `IOException`**, as it already
did for `StreamTcpException`. The argument is the one that function's own doc
makes, applied to a second case it should always have covered: a Pekko
`RuntimeException` crossing the SPI reads as neither refusal nor accident, so
`RetryPolicy`'s default `failures` — `it is IOException` — does not retry it,
and a caller sees an object with no stack and no message instead of a connection
that went away. It carries no message of its own, so one is written.

**This does not stop the cancellation happening, and the entry is not ticked.**
It changes what a caller is told and whether a retry policy will act; the race
inside the pool is untouched, and a caller without a retry policy — which is
every call in `GeneratedKotlinClientTest` — still fails. Entry two's Done-when
asks for the reproduction to stop and `main` to be green across consecutive
runs, and that is not met.

**What is missing is the rest of the stack.** `CancellationTrace` capped frames
at fourteen, and the sighting spent all fourteen on the decorator and the two
transport stages, cutting off exactly where the answer begins: whatever inside
Pekko completed the exchange. The cap is now 40. The next sighting should name
the origin, and that is what a root-cause fix waits on rather than another
guess.

## Open questions

- ~~**Does a `TestWatcher` see it?**~~ **Answered: yes**, and it does not help.
  See **Measured**.
- ~~**Which side throws — client or server?**~~ **Answered: the client, and
  before a body exists.** It is `singleRequest`'s own stage failing, not any of
  the three places the transport ends a body. See **The sighting**.
- ~~**Is `StreamConverters.asInputStream` the wrong bridge?**~~ **Not this
  time.** The cancellation never reaches it — it arrives while the response head
  is still awaited. The question stands on its own merits for body-read races,
  but it is not this flake.
- ~~**Should a `NonFailureCancellation` ever reach a caller?**~~ **Answered: not
  as itself.** It now crosses as an `IOException`, which is what the other
  transports raise and what a retry policy acts on. Whether it should reach a
  caller *at all* is the open part, and depends on the root cause below.
- **What completes the exchange?** The one thing still unknown, and the only
  thing a root-cause fix should be written on. The first sighting's frames were
  capped at fourteen and ran out before reaching it; the cap is 40 now. Until a
  sighting names it, the standing reading is a pooled connection going away
  under an in-flight non-idempotent request — consistent with the `POST`, and
  assumed no further than that.
- **How reproducible is it locally?** Unknown and probably poorly: 0049 needed
  a soak of tens of thousands of iterations and never hit its race. Three
  sightings in a day of ordinary CI suggests CI provokes this more readily than
  a loop will, so entry one's logging may have to land and wait.
