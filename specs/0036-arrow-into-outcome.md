# 0036 — Arrow into Outcome

## Problem

Services already written against Arrow model their domain in `Either`, and a
Pelican handler wants an `Outcome`. Today every such handler carries the same
four lines of fold. The maintainer has decided (in chat, 2026-08-26): a small
extension module ships the conversion, so an Arrow codebase adopts Pelican
without translating its domain by hand.

## Not doing

- **No Arrow types in core.** Core's classpath stays the Kotlin standard
  library; this is a leaf module like every other third-party seam.
- **No binder surface.** `handledOrFail { service.find(id).toOutcome() }`
  already reads well; a `handledEither` per backend is more surface for no
  reach.
- **No Raise/effect integration yet.** Conversions first; anything deeper
  waits for a use to demand it.

## Shape

`pelican-arrow`: `pelican-core` plus `arrow-core`, nothing else, asserted by
the usual dependency test.

- `Either<E, A>.toOutcome(): Outcome<E, A>` — `Right` is `ok`, `Left` is
  `err`: the single declared failure, resolved where the response is written
  (spec 0036 lands beside `err(...)`, which is what makes this total).
- `Either<E, A>.toOutcome(failure: ErrorOutput<E>): Outcome<E, A>` — the
  naming form, for an endpoint declaring several failures.
- `Outcome<E, A>.toEither(): Either<E, A>` — the way back, for a generated
  client's caller who wants their domain type on the outside.

## Why this shape

Conversions beat a binder because they compose with every handler style that
exists and every one that is added later, and they cost one import. The
`err(...)` pairing keeps the no-argument form honest: with several declared
failures the conversion cannot know the status, so it answers exactly as a
bare `err` does — refused where the response is written, naming the choices.

## Stack

- [x] **`rc/one-client-transport`** — module, conversions, tests, docs row.
      Done when: `./gradlew build` is green with the module included.

## Open questions

None — decided by the maintainer in chat, 2026-08-26.
