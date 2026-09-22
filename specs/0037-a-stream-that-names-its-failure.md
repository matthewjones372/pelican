# 0037 — The bridge to a stream that names its failure

## Problem

A streaming handler is handed a bare `Source<T, NotUsed>` and an upload a
bare `StreamIn<T>`. The typed stream DSL — `Stream<E, A>` over Pekko, with a
non-null element bound and an `Exit` that carries a failure as a value — is a
library of its own (decided by the maintainer in chat, 2026-09-03: nothing in
it is HTTP, so it does not live here). Its spec 0001 is in that repository.
What Pelican owes it is the seam.

## Not doing

- **No stream operators here.** The library owns `Stream`; Pelican converts.
- **No change to `streamedNow`.** `Stream<Nothing, T>.toSource()` already
  fits it, and the binder keeps its one Pekko shape.
- **Nothing until the library is published.** This spec is blocked on that
  and says so, rather than vendoring a snapshot.

## Shape

`pelican-streams`: `pelican-pekko` plus the library, asserted by the usual
classpath test.

```kotlin
ingestOrders handledBy { rows ->
    rows.toStream()                                  // Stream<Nothing, CreateOrder>
        .mapOrFail { it.customer ?: fail(NoCustomer(it)) }
        .catchAll { Stream.empty() }
        .runFold(0) { n, _ -> n + 1 }
        .run(system)
        .thenApply { Tally(it.getOrElse(0)) }
}
```

- `StreamIn<T>.toStream(): Stream<Nothing, T>`, on the system the request
  arrived on, as `runWith` is today.
- `Either` into `Outcome` for a handler answering with a value is
  `pelican-arrow`'s `toOutcome`, unchanged.

## Why this shape

One function and a module boundary, so a service that never streams never
sees the library, and the library never learns what an endpoint is.

## Stack

- [x] **`spec-0037-bridge`** — module, `toStream`, the `modules.md` row, a
      reference paragraph.
      Done when: `./gradlew build` is green with the module included.

## Measured

The library is published: **`io.github.matthewjones372:lark-stream:0.4.0`**, in
`matthewjones372/lark`, on Maven Central since 2026-09-18. Its own README
describes it in this spec's words — *"a stream that names its failure"*. The
block this spec put on itself is lifted.

The API is the one sketched here, near enough to compile against: `Stream.from(
source)`, `mapOrFail`, `runFold`, `runCollect`, `run(system)`, and
`Stream<Nothing, A>.toSource()` — which is what made the *"no change to
`streamedNow`"* claim true. And because `pelican-pekko` already publishes
`StreamIn<T>.toSource()`, the seam is one line:

```kotlin
fun <T : Any> StreamIn<T>.toStream(): Stream<Nothing, T> = Stream.from(toSource())
```

The whole published surface of the module is that function.

### The dependency, which this spec could not have known about

This draft is dated 2026-09-03. The *other* spec 0037 — Pekko becomes
`compileOnly` so no `org.apache.pekko` entry appears in any Pelican POM — was
decided 2026-09-04, a day later. Its **Shape** here (*"`pelican-pekko` plus the
library"*) therefore predates that decision, and taken literally would undo it:

```kotlin
// lark-stream/build.gradle.kts
api(platform("org.apache.pekko:pekko-bom_2.13:1.2.1"))
api("org.apache.pekko:pekko-stream_2.13")
```

An `api(lark-stream)` republishes `pekko-stream_2.13` transitively, so a service
on the Scala 3 cross-build gets both suffixes and refuses to start — exactly the
bug the other 0037 exists to prevent, arriving through a module that never names
Pekko.

**Both halves are `compileOnly`,** chosen by the maintainer, 2026-09-22. The
consumer already declares Pekko; they now declare `lark-stream` beside it, and
`DependenciesTest` asserts the published runtime classpath holds neither.

### Two things this spec had slightly wrong

**`Exit` has no `getOrElse`.** The snippet in **Shape** reads
`it.getOrElse(0)`; `Exit` is a three-case sealed interface — `Done`, `Failed`,
`Died` — with no accessors, and matching on it is the point. The reference and
the doc comment show the `when`.

**The system is not supplied at conversion.** This spec says `toStream` is *"on
the system the request arrived on, as `runWith` is today"*. lark takes it at the
far end instead, at `run(system)`, so `toStream` takes none and the caller
chooses. In practice that is the same system: a service starts it and closes
over it, which is what `ToStreamTest` does.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. ~~**A module, or one function in `pelican-pekko`?**~~ **Taken: the module.**
   The reasoning got stronger than the draft knew — a function in
   `pelican-pekko` would have put `lark-stream`'s `_2.13` Pekko on every Pekko
   service's classpath, not merely the library.
2. **Does `Exit` need an `Outcome` conversion?** `Exit.Failed(e)` to a
   declared failure reads well as `toOutcome(failure)`. Recommend waiting for
   a handler that wants it — still unanswered, and now concrete: `Died` is the
   case such a conversion would have to decide about, since it is the only one
   that should still become a 500.
3. **Should `pelican-pekko` expose the request's actor system?** A handler that
   does not already own one has no way to reach it, so `run(system)` needs a
   system from somewhere. Every service has one; a library caller might not.
   Recommend leaving it until someone is stuck, since exposing it widens a
   published surface for a case nobody has hit.
