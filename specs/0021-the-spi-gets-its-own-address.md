# 0021 — The SPI gets its own address

> Completion note (2026-08-26): `multipartBoundary` and `MultipartBody.decode`
> — the multipart helpers this spec named — landed in `spi` with the RC.
> `formCodec`, `parseFormBody` and `renderFormBody` deliberately stay in the
> root package: a generated client imports `formCodec` from core, so they are
> caller-facing surface, pinned by the `clients` fixture, not interpreter
> plumbing.

## Problem

About twenty functions exist only so interpreters in other modules can call
them — `handlerFor`, `readStrictBody`, `renderError`, `statusFor`,
`routeIndex`, `failureNamedBy`, `formCodec`, the multipart helpers — and all
of them sit in the root `io.github.matthewjones372.pelican` package beside
the user DSL, sharing its autocomplete and its compatibility promise
(`pelican-core.api:57,83-88,491-495,879-904,1243,1364-1367` and friends).
They cannot be `internal`: backends are separate modules by design. But a
user typing `re…` is offered `renderError`, and after 1.0 the plumbing can
never move without breaking every backend — including the fourth one spec
0014 wants to build against exactly this surface.

## Not doing

- **No new module.** The SPI stays in pelican-core; only the package changes.
- **No interface extraction.** The functions keep their shapes; this is a
  move, not a redesign.
- **No opinion on what a third-party backend may use.** The package is the
  documentation of intent, not a seal.

## Shape

```kotlin
package io.github.matthewjones372.pelican.spi

// unchanged signatures, new address; interpreters update their imports
fun Api.handlerFor(endpoint: Endpoint<*, *>): ServerHandler?
fun renderError(...): RenderedError
```

`docs/modules.md` gains one paragraph: the `spi` package is for interpreter
and transport authors, is covered by the same BCV gate, and is not the DSL.

## Why this shape

A package move is the cheapest possible fence and the only chance to build it
is before the freeze — moving later breaks all three (soon four) backends at
once; moving now breaks nobody but this repo's own interpreters, updated in
the same stack. The alternative — `@PelicanSpi` opt-in annotation — was
considered: it warns rather than separates, pollutes call sites with opt-ins,
and still leaves the names squatting in the user package.

## Stack

- [x] **`spec-0021-spi-move-core`** — the package move in pelican-core, old
      names gone, dumps regenerated.
      Done when: `pelican-core.api` shows the SPI only under `.spi` and the DSL surface is unchanged.
- [x] **`spec-0021-spi-move-backends`** — import updates in pelican-pekko,
      pelican-http4k, pelican-ktor, pelican-mcp, the docs modules, and
      `docs/modules.md`.
      Done when: `./gradlew build` is green with no other source change.

## Acceptance

```bash
./gradlew apiDump build
```

## Open questions

1. Which names are genuinely SPI? The list above is from reading the three
   interpreters; the implementer should derive it mechanically — everything
   in core referenced by a backend module and never by `example/` or the
   docs. Recommend deriving, then reviewing the list in the PR description.
2. Do `CorsPolicy`/`corsPolicy` move? They are user-facing settings *and*
   interpreter plumbing. Recommend they stay in the root package; only the
   folding helpers move.
3. Does `lensInputs` move? It is a user escape hatch, not plumbing.
   Recommend it stays.
