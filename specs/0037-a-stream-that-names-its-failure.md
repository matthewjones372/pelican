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

- [x] **`spec-0037-bridge`** ([#148](https://github.com/matthewjones372/pelican/pull/148)) — module, `toStream`, the `modules.md` row, a
      reference paragraph.
      Done when: `./gradlew build` is green with the module included.
- [x] **`spec-0037-exit-to-outcome`** ([#160](https://github.com/matthewjones372/pelican/pull/160)) — a `toOutcome(failure)` on the
      `CompletionStage<Exit<E, A>>` that `run` returns, in `pelican-streams`,
      and the two examples that currently hand-roll it rewritten onto it.
      Done when: a handler answering `Outcome<E, A>` converts without naming
      `Exit` itself, a `Died` still reaches the interpreter as a throw, and
      `pelican-streams.api` grows exactly one line. **All three hold.**

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

### Where `system` comes from, and why nothing is exposed

A handler is given `Params` and nothing else — every binder erases to
`(Params) -> CompletionStage<Any?>` — and `Params` carries the request, its
attributes and the response headers. No system, no materializer.

The system is one field away. `PekkoFrames` holds the one the request arrived
on, `internal`, and the only public door onto it is `runWith(sink)`. So
exposing it would publish no new *capability*: a handler can already materialise
whatever graph it likes on that system by handing `runWith` a `Sink`. lark
cannot use that door only because `Run`'s graph is `internal` to lark, which
leaves `run(provider)` as the way in.

**Decided by the maintainer, 2026-09-23: nothing is exposed.** Nobody is stuck.
A service that needs the provider starts one and passes it to `start(system)` —
the documented form — then closes over it, which is what a service does anyway
and what `ToStreamTest` does. The one form that hands back no system in time is
`start(port = …)`, which creates its own inside the binding: `PelicanServer
.system` exists only after the route has been built. That is a two-line change
to such a service, not a wall.

It is the same reasoning applied to `Params.asMap` a day earlier: an accessor
with no extant user is a promise kept for nobody, and 1.0 is the release that
should not make one. If a service does get stuck, the field is one line from
public and this paragraph is where to start.

**What was wrong was the documentation, not the surface.** Both places that
show the call wrote `.run(system)` with `system` arriving from nowhere, which is
what made the question look like a missing accessor. Corrected alongside this
closing.

### The conversion, and why `Died` was never the hard part

The question asked whether `Exit` needs an `Outcome` conversion, and named
`Died` as the case such a conversion would have to decide. `Died` was already
decided, twice, by parties that are not this spec:

- lark's own `awaitExit` does `Done` → the value, `Failed` → `raise`, `Died` →
  rethrow the cause.
- `AGENTS.md`: declared failures are values in the return type, and throwing is
  for what nobody declared.

Both say the same thing, so the conversion has one shape rather than a choice.

What made it concrete is that the two places showing the call each hand-roll it,
and each gets it wrong the same way — `if (exit is Exit.Done) … else …`, an
`else` over a three-case sealed type, which is the one thing `AGENTS.md` says
never to write. A conversion whose own examples cannot be written by hand
without breaking the house rule is one the library should own.

```kotlin
fun <E : Any, A : Any> CompletionStage<Exit<E, A>>.toOutcome(
    failure: ErrorOutput<E>,
): CompletionStage<Outcome<E, A>> =
    thenApply { exit ->
        when (exit) {
            is Exit.Done -> ok(exit.value)
            is Exit.Failed -> failure(exit.error)
            is Exit.Died -> throw exit.cause
        }
    }
```

On the `CompletionStage` rather than on `Exit`, because `run(system)` returns
one and `handledByOrFail` takes one; converting at the `Exit` leaves the caller
a `thenApply` to write, which is exactly where the hand-rolled `else` came from.

Not in `pelican-arrow`, though lark pulls `arrow-core` transitively and
`awaitExit` is the Arrow-shaped door already: it blocks, through `lark-pekko`'s
`await`, which a handler returning a `CompletionStage` should not.

The bounds above were compiled against lark 0.4.0 and core rather than sketched,
because the last thing this spec sketched was `Exit.getOrElse`.

#### As built, and the one thing that had to be measured

The signature is the one above, unchanged. Two things the draft did not know:

**A third place was hand-rolling it.** The draft counted two — the reference and
the `toStream` KDoc. `ToStreamTest` was a third, with a private
`Exit<*, A>.valueOr(fallback)` at the bottom of the file that collapses `Failed`
and `Died` into one fallback. That is the exact sloppiness this entry removes,
written by the entry that introduced the seam.

**A defect arrives as `Exit.Died` on a *successful* stage.** Whether a throwable
raised inside a stage reaches `toOutcome` at all, or fails the stage before it,
decides whether the conversion can do anything about it — and nothing in lark's
signatures says which. Measured rather than assumed:

```
PROBE-DEFECT: success=true value=Died(cause=java.lang.IllegalStateException: boom on a) failure=null
```

So the conversion does see it, and rethrowing is what turns it back into the 500
the interpreter already renders. `Died` is tested directly as well as through a
socket, because an end-to-end 500 cannot say which branch produced it.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. ~~**A module, or one function in `pelican-pekko`?**~~ **Taken: the module.**
   The reasoning got stronger than the draft knew — a function in
   `pelican-pekko` would have put `lark-stream`'s `_2.13` Pekko on every Pekko
   service's classpath, not merely the library.
2. ~~**Does `Exit` need an `Outcome` conversion?**~~ **Answered: yes**, and its
   shape is forced rather than chosen — lark and `AGENTS.md` had both already
   decided `Died`. Drafted as `spec-0037-exit-to-outcome`; see **The
   conversion**.
3. ~~**Should `pelican-pekko` expose the request's actor system?**~~
   **Answered: no.** The handle is withheld but the capability is not —
   `runWith` already materialises on that system — and the service that needs
   the provider starts one and passes it to `start(system)`. The documentation
   was what needed fixing. See **Where `system` comes from**.
