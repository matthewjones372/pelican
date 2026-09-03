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

- [ ] **`spec-0037-bridge`** — module, `toStream`, the `modules.md` row, a
      reference paragraph.
      Done when: `./gradlew build` is green with the module included.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **A module, or one function in `pelican-pekko`?** A function there would
   put the library on every Pekko service's classpath. Recommend the module.
2. **Does `Exit` need an `Outcome` conversion?** `Exit.Failed(e)` to a
   declared failure reads well as `toOutcome(failure)`. Recommend waiting for
   a handler that wants it.
