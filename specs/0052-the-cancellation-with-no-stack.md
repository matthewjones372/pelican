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

**The throw site is unknown, and spec 0051 does not recover it.** 0051 makes
Gradle print the *type* it could not rebuild, which is how the exception above
is named at all. It cannot restore the stack: reconstruction failed, so the
original frames were never rebuilt on the daemon side, and the test-report XML
carries Gradle's own trace rather than Pekko's. We know what was thrown and
nothing about where.

That is the whole difficulty. `StageWasCompleted` is a
`SubscriptionWithCancelException.NonFailureCancellation` — Pekko's signal that a
subscription was cancelled because its stage had already finished — and without
frames there is no saying which stage, on which side of the socket.

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

Entry one buys the stack, because nothing can be decided without it. That is
the alternative spec 0051 named and deferred: a listener inside the test JVM
that records the throwable before Gradle ever serialises it.

```kotlin
/** Gradle cannot rebuild a Scala `case object`, so its stack is gone by the time a report is written. This keeps it. */
class LogRealFailure : TestWatcher {
    override fun testFailed(context: ExtensionContext, cause: Throwable) {
        // to stdout, which Gradle forwards verbatim
    }
}
```

Registered where it costs nothing to have and something to lack — a
`junit-platform.properties` auto-registration, so no test has to remember it.

## Why this shape

0051 recommended the one-line logging change and said the listener was worth
building "only if something is found that the message does not name". This is
that thing. The type is named; the frames are not; and the frames are the whole
question.

A listener is also the smaller commitment it looks like: it observes and
prints, changes no behaviour, and can be deleted the day Gradle learns to keep
a stack it could not rebuild.

The alternative is to reason from the three candidate sites and patch the most
likely one. That is how a flake becomes two flakes.

## Stack

- [ ] **`spec-0052-keep-the-stack`** — a JUnit `TestWatcher`, auto-registered,
      printing the real throwable and its stack before Gradle serialises the
      failure.
      Done when: a test throwing `StageWasCompleted` shows Pekko's own frames in
      the console, and `./gradlew build` is green.
- [ ] **`spec-0052-the-fix`** — whatever the frames from entry one show.
      Done when: the reproduction from entry one stops failing, and `main` is
      green on 21, 23 and 25 across consecutive runs.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Does a `TestWatcher` see it?** The exception may surface from a stream
  thread rather than the test thread, in which case JUnit never reports it as
  the failure cause and the listener sees nothing. Recommend checking that
  first, before building the registration: if it does not, the answer is an
  uncaught-exception handler on the transport's dispatcher instead.
- **Which side throws — client or server?** Both are in one JVM here, so a
  stack will say, and nothing before then will. Recommend not speculating in
  the meantime.
- **Is `StreamConverters.asInputStream` the wrong bridge?** It is a blocking
  reader over a stream whose lifetime the caller controls, which is the shape
  that produces cancellation races. Possibly the finding, possibly a red
  herring; entry one decides.
- **How reproducible is it locally?** 0049 needed a soak of tens of thousands
  of iterations and never hit it. Recommend budgeting for the same and using CI
  as the reproducer if a loop will not do it — three sightings in a day of
  ordinary runs suggests CI provokes it more readily than a tight loop does.
