# 0020 — One name, one meaning across backends

## Problem

The calls a user types first do not agree across the three backends. Pekko's
`start(host = "127.0.0.1", port = 8080)` and Ktor's
`start(port = 8080, host = "0.0.0.0")` flip both the positional order and —
worse — the bind default: a bare `start()` is loopback-only on Pekko and
listens on every interface on Ktor (`pekko Server.kt:50-54`,
`ktor Server.kt:48-52`); http4k has no host parameter at all. The
`PelicanServer` handed back diverges too: http4k and Ktor are `AutoCloseable`
with `block()` and `stop(): Unit`, Pekko is none of those and returns
`CompletionStage` from `stop()`. And `handledNow` — the most-typed identifier
in user code — means "synchronous" on Pekko/http4k but takes a `suspend`
lambda on Ktor (`ktor Handlers.kt:34`). All three asymmetries are silent
semantic changes if touched after 1.0; none is if touched now.

## Not doing

- **No shared server abstraction in core.** Each backend keeps its own
  `Server.kt`; only the shapes agree. Core stays dependency-free.
- **No change to wrong-method 404/405 divergence** — that one is pinned as a
  documented difference in `MethodMismatchTest` and stays.
- **No `handledBy` on Ktor.** The `CompletionStage` binder family stays a
  Pekko/http4k concern; Ktor's world is suspend.

## Shape

```kotlin
// identical on all three backends
val running = api.start(port = 8080)          // binds 127.0.0.1
api.start(port = 8080, host = "0.0.0.0")      // opting onto the network is spelled out
running.use { it.block() }                    // AutoCloseable everywhere, stop(): Unit
```

`handledNow` keeps its name on all three and the reference defines it as
"handled in place, on the request": in place is a thread on Pekko/http4k and
a coroutine on Ktor. The divergence becomes a documented meaning instead of
an accident, with one paragraph in the reference and a parity test asserting
all three names exist.

## Why this shape

Loopback-by-default is the safe default and the one Pekko already has;
`0.0.0.0` should be a visible choice, not an inheritance from the engine.
Port-first matches two of three backends today, so Pekko takes the one
breaking flip. The alternative for `handledNow` — renaming Ktor's binder
`handledSuspending` — buys naming purity at the cost of making the flagship
example differ per backend, which is the wrong trade for a library whose
pitch is "same description, any backend".

## Stack

- [x] **`spec-0020-start-alignment`** — `start(port, host = "127.0.0.1", ...)`
      on Pekko and Ktor; http4k gains the host parameter.
      Done when: a parity test pins signature and bind default on all three.
- [x] **`spec-0020-server-shape`** — Pekko `PelicanServer` gains
      `AutoCloseable`, `block()`, `stop(): Unit` (async stop stays as
      `stopAsync()`).
      Done when: the same `use { }` block compiles and passes against all three.
- [x] **`spec-0020-handled-now-doc`** — the "in place" definition in
      `docs/reference.md`, plus the binder-name parity test.
      Done when: the reference states the Ktor meaning and the test names all binders per backend.

## Acceptance

```bash
./gradlew apiDump build
```

## Open questions

1. Should Ktor's default host change silently or fail loudly for one release
   (refuse `start()` without a host)? Recommend silent change with a
   CHANGELOG entry — pre-1.0 is what the warning banner is for.
2. `stopAsync(): CompletionStage<Unit>` on all three, or Pekko only?
   Recommend all three; it is trivial on the other two and keeps symmetry.
3. Does `block()` on Pekko join the server actor system or a latch?
   Recommend a latch released by `stop()`, matching http4k's behaviour.
