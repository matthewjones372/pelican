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

## Open questions

- ~~**Does a `TestWatcher` see it?**~~ **Answered: yes**, and it does not help.
  See **Measured**.
- **Which side throws — client or server?** Still unknown, and the exception
  cannot say. Entry one's logging is on the client side because that is where
  our code ends a body; if none of the three paths fires, the answer is the
  server and entry one needs a second pass there.
- **Is `StreamConverters.asInputStream` the wrong bridge?** It is a blocking
  reader over a stream whose lifetime the caller controls, which is the shape
  that produces cancellation races. Entry one's third log line is on it.
- **Should a `NonFailureCancellation` ever reach a caller?** Pekko's own name
  says no. Recommend treating that as entry two's likely answer rather than
  entry one's assumption — if the logging shows something else entirely, this
  would have been a comfortable wrong turn.
- **How reproducible is it locally?** Unknown and probably poorly: 0049 needed
  a soak of tens of thousands of iterations and never hit its race. Three
  sightings in a day of ordinary CI suggests CI provokes this more readily than
  a loop will, so entry one's logging may have to land and wait.
