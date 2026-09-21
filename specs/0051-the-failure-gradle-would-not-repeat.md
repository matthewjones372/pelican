# 0051 — The failure Gradle would not repeat

## Problem

A test failed on PR #114 and CI said this:

```
GeneratedKotlinClientTest > an endpoint that answers two ways makes the caller say which one it got() FAILED
    org.gradle.api.internal.tasks.testing.TestFailureSerializationException at TestEventSerializer.java:359
        Caused by: java.lang.ClassNotFoundException at URLClassLoader.java:445
```

The real exception was none of those. From the uploaded test-report XML:

```
An exception of type org.apache.pekko.stream.SubscriptionWithCancelException$StageWasCompleted$
was thrown by the test, but Gradle was unable to recreate the exception in the build process
```

A Pekko connection-teardown race, reported as a missing class. Recovering that
line took downloading the artifact and parsing the XML, on a pull request whose
whole diff was a formatter and a publishing plugin — so the log actively argued
for the wrong culprit.

Three things combine, and only the last is ours.

**Gradle reconstructs the exception in the daemon.** The test JVM serialises a
failure across a socket, and `TestEventSerializer$DefaultTestFailureSerializer`
rebuilds it on the other side. A Scala `case object` does not serialise as
itself: it has a `writeReplace()` returning a
`scala.runtime.ModuleSerializationProxy` that holds its own `Class`. The daemon
has no scala-library on its classpath, so it cannot load the proxy, and
reconstruction fails with `ClassNotFoundException:
scala.runtime.ModuleSerializationProxy`. Gradle substitutes a
`TestFailureSerializationException`.

**That substitute carries the truth in its message.** Gradle names the real
class there, which is why the XML has it.

**The console throws the message away.** `DefaultTestLoggingContainer`'s
constructor calls `setExceptionFormat(TestExceptionFormat.SHORT)`, and
`ShortExceptionFormatter` prints `Class.getName()`, `" at "`, the top frame and
the `Caused by:` chain — it never calls `getMessage()`. `DefaultTestLogging`'s
own default is `FULL`; the container overrides it. This repository configures no
`testLogging` anywhere, so it takes that default, on Gradle 9.7.1.

Every Pekko failure of this shape reads as `ClassNotFoundException`. On a
library built on Pekko that is a whole class of failure whose logs name the
wrong thing, and today it cost an afternoon on a two-line dependency bump.

## Not doing

- **Not chasing Gradle.** The reconstruction is theirs and the substitute
  already carries what we need. Nothing here needs filing upstream.
- **Not touching the artifact upload.** `build.yml` already uploads test
  reports on failure. The problem is that reading them is required, not that
  they are missing.
- **Nothing to the underlying flake.** Whatever races in Pekko's teardown is
  0049's business, and this change would not have fixed it — only named it.
- **No custom test listener.** The message already crosses the wire.

## Shape

One line, in the block that already configures every `Test` task:

```kotlin
tasks.withType<Test>().configureEach {
    // A Scala `case object` thrown by a test serialises
    // through `scala.runtime.ModuleSerializationProxy`, which the daemon
    // cannot load because it carries no scala-library. Gradle then reports a
    // `TestFailureSerializationException` naming the real type in its
    // *message* — and `SHORT`, the default, prints types and never
    // messages. See spec 0051.
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
}
```

With `FULL`, `FullExceptionFormatter` renders the message, so the line that took
an artifact download appears in the console log where the failure is.

## Why this shape

It is one line in a block that already exists, and it fixes the reporting rather
than the symptom: nothing about which exceptions Gradle can rebuild changes, only
whether we are told which one it could not.

The alternative is a JUnit listener in the test JVM that logs the real throwable
before Gradle ever serialises it. That is strictly more faithful — it sees the
exception itself rather than Gradle's description of it — but it is a new moving
part in every module's test runtime to recover information that is already being
transmitted and merely not printed. Recommend the one line, and the listener
only if something is found that the message does not name.

The cost is real and worth stating: `FULL` prints a whole stack trace for every
failing test, where `SHORT` prints two lines. A build with many failures gets
noisier. That is the right trade for a log that currently names the wrong class.

## Stack

- [x] **`spec-0051-full-exception-format`** — `testLogging { exceptionFormat =
      TestExceptionFormat.FULL }` in the shared `Test` configuration, with the
      reason in a comment.
      Done when: a test made to throw a Scala `object` exception prints the real
      class name in the console output of `./gradlew build`, where today it
      prints `ClassNotFoundException`.

## Measured

Reproduced and fixed locally on `e55e59c`, with a throwaway test in
`pelican-pekko` that throws the real exception reflectively:

```kotlin
val cls = Class.forName("org.apache.pekko.stream.SubscriptionWithCancelException$StageWasCompleted$")
throw cls.getField("MODULE$").get(null) as Throwable
```

**Before** — the CI failure, reproduced exactly:

```
ScratchMaskingTest > a test that throws a scala case object exception() FAILED
    org.gradle.api.internal.tasks.testing.TestFailureSerializationException at TestEventSerializer.java:359
        Caused by: java.lang.ClassNotFoundException at URLClassLoader.java:445
```

**After**, with `exceptionFormat = FULL`:

```
ScratchMaskingTest > a test that throws a scala case object exception() FAILED
    org.gradle.api.internal.tasks.testing.TestFailureSerializationException: An exception of type org.apache.pekko.stream.SubscriptionWithCancelException$StageWasCompleted$ was thrown by the test, but Gradle was unable to recreate the exception in the build process
        java.lang.ClassNotFoundException: scala.runtime.ModuleSerializationProxy
```

The real type is named without leaving the console.

Two claims in this spec's first draft were wrong, and the reproduction is what
established it.

**A Scala `case object` is not unreconstructible for want of a constructor.**
`javap` shows `StageWasCompleted$` with a public no-arg constructor. What
defeats the daemon is `writeReplace()`, which replaces the instance with a
`scala.runtime.ModuleSerializationProxy` — and the missing class in the second
line above is that proxy, not the Pekko type. The Gradle daemon carries no
scala-library at all.

**Kotlin `object` declarations do not have the same shape.** A Kotlin
`private object NotReconstructible : RuntimeException(...)` thrown from a test
in `pelican-core` reported as itself before any change:

```
ScratchMaskingTest > a test that throws an object exception() FAILED
    io.github.matthewjones372.pelican.ScratchMaskingTest$NotReconstructible at ScratchMaskingTest.kt:12
```

No `writeReplace`, so Gradle's placeholder path keeps the type and message. The
hazard is Scala's, not every `object`'s.

## Acceptance

```bash
./gradlew build
```

## Established

Read out of Gradle 9.7.1's own bytecode and the run 35635410581 artifact, not
from memory:

- `DefaultTestLoggingContainer.<init>` calls
  `setExceptionFormat(TestExceptionFormat.SHORT)`.
- `ShortExceptionFormatter` calls `Class.getName()` and `Throwable.getCause()`,
  and emits the literals `" at "` and `"Caused by:"`. There is no
  `getMessage()` call in it.
- `DefaultTestLogging`'s field initialiser sets `FULL`, so the `SHORT` above is
  an override rather than the underlying default.
- `TestEventSerializer$DefaultTestFailureSerializer` builds its
  `DefaultTestFailureDetails` from `getMessage()`, `getClass().getName()` and
  `Throwables.getStackTraceAsString(...)` of whatever it ended up with — which
  is why the XML shows the substitute's message in full.
- `grep` finds no `testLogging`, `exceptionFormat`, `showStackTraces` or
  `showExceptions` anywhere in the build.

## Open questions

- **Does this deserve a permanent regression test?** The claim is about console
  output, which no gate here reads. A test that throws a Scala `object` and
  asserts on the log would need to run a nested build. Recommend verifying once,
  by hand, and recording the before and after in this spec rather than building
  that machinery.
- **`FULL` everywhere, or only in CI?** A contributor running one failing test
  locally may prefer the shorter form. Recommend one setting everywhere until
  someone says otherwise — two behaviours is how a log stops being trusted.
- **Should `showCauses` and `showStackTraces` be set too?** They default true,
  so `FULL` alone should be enough. Worth confirming while building entry one
  rather than setting them blind.
- **Which other exceptions does this hit?** Every Scala `case object` thrown as
  a Throwable, because all of them serialise through
  `ModuleSerializationProxy` and the daemon has no scala-library. **Not** Kotlin
  `object` declarations: measured below, one reconstructs and prints its own
  type. Recommend not enumerating the Scala side: the fix does not depend on
  the list.
