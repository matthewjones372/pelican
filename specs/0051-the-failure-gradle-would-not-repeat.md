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
rebuilds it on the other side. A Scala `object` — the trailing `$` — has no
constructor it can call, so reconstruction fails and Gradle substitutes a
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

One line, in the block at `build.gradle.kts:191` that already configures every
`Test` task:

```kotlin
tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Gradle rebuilds a failure in the daemon and cannot rebuild a Scala
    // `object`, so it substitutes an exception naming the real one in its
    // message. SHORT, the default, prints class names and never messages.
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

- [ ] **`spec-0051-full-exception-format`** — `testLogging { exceptionFormat =
      TestExceptionFormat.FULL }` in the shared `Test` configuration, with the
      reason in a comment.
      Done when: a test made to throw a Scala `object` exception prints the real
      class name in the console output of `./gradlew build`, where today it
      prints `ClassNotFoundException`.

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
- **Which other exceptions does this hit?** `StageWasCompleted` is one Scala
  `object` among many in Pekko, and Kotlin `object` declarations have the same
  shape. Recommend not enumerating them: the fix does not depend on the list.
