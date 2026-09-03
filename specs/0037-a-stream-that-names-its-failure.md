# 0037 — A stream that names its failure

## Problem

A Kotlin service on Pekko Streams writes the `javadsl`: `japi.Pair`,
`NotUsed`, a `Supervision.Decider` set as an attribute far from the operator
it governs, and one error channel typed `Throwable` for the whole graph.
Teams that model failure as a value put an `Either` in every element, split
it with `divertTo(sink, predicate)`, and cast the survivors back.

Two things drop an element without a trace, and both are documented: a
`mapAsync` whose `CompletionStage` completes with `null` "is not passed
downstream", and a `Resume` decider means "the element is dropped and the
stream continues". Kotlin makes the first trivial — a nullable lookup lifted
into a future — and nothing in the types says so. A null was swallowed in
production this way and left no evidence of where.

Pelican has the value that says what a handler can fail with, `Outcome<E, T>`,
and hands streaming handlers a bare `Source<T, NotUsed>`. The stream is where
the declared-failure idea stops.

## Not doing

- **No second engine.** Every operator delegates to Pekko; `toSource()` and
  `Stream.from(source)` are the escape hatch, so a missing operator is one
  line away. No graph DSL, no materialised values beyond `NotUsed`.
- **No supervision surface.** There is no `resume`. Dropping an element is
  written as `divertErrors(to = sink)`, with the sink named.
- **No coroutines, no Arrow.** `run` answers a `CompletionStage` as
  `pelican-pekko` does, and `pelican-arrow` is already the `Either` seam.

## Shape

`pelican-streams`: core plus `pekko-stream`, no `pekko-http`, asserted by the
usual dependency test.

```kotlin
val settled: CompletionStage<Exit<IngestError, Int>> =
    Stream.from(rows.toSource())                                     // Stream<Nothing, Row>
        .mapOrFail { row -> row.customer ?: fail(NoCustomer(row.id)) } // Stream<NoCustomer, Customer>
        .mapAsync(4) { customer -> ledger.settle(customer) }           // CompletionStage<Either<Declined, Receipt>>
        .map { it.toOutcome() }                                        // pelican-arrow, one import
        .divertErrors(to = declinedSink)                               // Stream<NoCustomer, Receipt>
        .runFold(0) { n, _ -> n + 1 }
        .run(system)
```

- `Stream<out E, out A : Any>`. The bound is the fix: `map { it.customer }` on
  a nullable field does not compile; `mapOrFail` names what a missing one means.
- `Exit<E, A>` is `Done(value)`, `Failed(error: E)` or `Died(cause: Throwable)`.
  `run` completes normally with all three; a defect is matched on, not a
  failed future nobody read.
- `fail(e)` ends the stream with `E`, carried on Pekko's failure channel in a
  private wrapper that only `run` unwraps, so fusion is untouched.
- `mapAsync` turns a `null` completion into `Died(NullPointerException)`.
- `either()`, `absolve()`, and `divertErrors(to: Sink<L, *>)` over
  `Stream<E, Outcome<L, A>>` — the `divertTo` idiom with no predicate and no cast.
- `catchAll` and `orElse` leave a failure type. `Stream<Nothing, T>.toSource()`
  fits `streamedNow`; a stream still carrying an `E` has no status to become
  mid-response, so it does not fit until the handler says what a failure means.

## Why this shape

A typed view over `Source` keeps every Pekko guarantee and asks the compiler
to hold two lines: an element is never null, and a failure is a named value
until someone handles it. The alternative is a fresh engine over coroutines —
more Kotlin, but a second runtime beside the one the service runs, and
Pekko's operators to rewrite. `Outcome` rather than a stream-local `Either`:
one failure value from handler to pipeline.

## Stack

- [ ] **`spec-0037-stream`** — module, `Stream`, `Exit`, `from`, `map`,
      `mapOrFail`, `filter`, `runFold`, `runCollect`, `run`.
      Done when: a nullable `mapOrFail` body is in `DoesNotCompileTest` and
      `fail(e)` arrives at `run` as `Exit.Failed(e)`.
- [ ] **`spec-0037-async-and-split`** — `mapAsync`, `either`, `absolve`,
      `divertErrors`, `catchAll`, `orElse`.
      Done when: a `null` completion yields `Exit.Died`, and every
      `Outcome.Err` through `divertErrors` is counted at the sink.
- [ ] **`spec-0037-endpoints`** — `toSource`, `StreamIn.toStream`, the
      `modules.md` row, a reference section.
      Done when: `streamedNow { s.toSource() }` compiles only for
      `Stream<Nothing, T>`, pinned in `DoesNotCompileTest`.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Pelican module or its own repository?** Nothing in it is HTTP. Recommend
   the module: `Outcome` is the error value and the binders are the consumer.
2. **Where does the `StreamIn` bridge live?** Recommend `pelican-pekko`
   depending on `pelican-streams`: no new library on its classpath.
3. **`Stream` collides with `java.util.stream.Stream` at an import.**
   Recommend keeping it; `Flow` collides with two libraries.
4. **Arrow overloads** — `divertLefts` over `Stream<E, Either<L, A>>`, so an
   Arrow service never writes `toOutcome`? It needs a `pelican-streams-arrow`
   module, since neither existing module may carry the other's library.
   Recommend not yet: `.map { it.toOutcome() }` is one call; add the module
   if that line turns up in every pipeline.
5. **`Died` or a failed stage for defects?** Recommend `Died`: the swallowed
   null is the case for a defect being a value the caller must match.
