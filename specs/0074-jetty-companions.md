# 0074 — Jetty's companions

## Problem

Spec 0073 serves an API on Jetty. A service that also serves its document,
its MCP tools, or tests in memory through the server still needs a Pekko
module for each: `pelican-pekko-docs`, `pelican-pekko-mcp`,
`pelican-test-pekko`. `pelican-streams` reaches surefold through
`surefold-stream-pekko`, and the importer refuses to write handler stubs for any
backend but Pekko.

## Not doing

- **New docs or MCP behaviour.** Each module does what its Pekko twin does.
- **The client.** Spec 0075.
- **surefold on a Jetty runtime.** surefold's core `Stream.blocking` is enough.

## Shape

```kotlin
api.startWithDocs(port = 8080, docs = docs(reference = reference()))  // pelican.jetty.docs
api.toHandler(handlers).withMcp()                                     // pelican.jetty.mcp
val client = api.inMemory()                                           // pelican.test.jetty, over LocalConnector
rows.toStream()                                                       // pelican.streams.jetty
```

- `pelican-jetty-docs`: `docsHandler`, `handlerWithDocs`, `startWithDocs`, the
  same paths, pages and `/reference` as `pelican-pekko-docs`.
- `pelican-jetty-mcp`: `mcpHandler`, `handlerWithMcp`.
- `pelican-test-jetty`: `inMemory()`, `inMemory(server)`, `PelicanServer.client()`.
- `pelican-streams-jetty`: `StreamIn<T>.toStream()` on `Stream.blocking`, whose
  `wake` closes the request's content and `close` closes the iterator; and
  `toOutcome`, shared with the Pekko module.
- The importer's `Backend.JETTY`, with stubs importing `pelican.jetty`.

## Why this shape

One module per Pekko module keeps a migration to an import swap. The
alternative for streams is one backend-neutral `pelican-streams`; that changes
the Pekko module's dependencies after 1.0. Recommended: a separate
`pelican-streams-jetty`, with `toOutcome` moved to a shared place only if it
can be without breaking the Pekko module's API.

## Stack

- [ ] **`spec-0074-docs`**: `pelican-jetty-docs`.
      Done when: Pekko's docs tests pass against Jetty's handler.
- [ ] **`spec-0074-mcp`**: `pelican-jetty-mcp`.
      Done when: a tools/list over Streamable HTTP answers as on Pekko.
- [ ] **`spec-0074-test`**: `pelican-test-jetty`.
      Done when: `pelican-test-pekko`'s tests pass on the Jetty transport.
- [ ] **`spec-0074-streams`**: `pelican-streams-jetty`.
      Done when: `pelican-streams`' tests pass on Jetty, and a stopped run closes the upload.
- [ ] **`spec-0074-importer`**: `Backend.JETTY` and its stubs.
      Done when: a generated Jetty stub file compiles in the importer's tests.

## Acceptance

```bash
./gradlew build
```

## Decisions

Matt delegated the open questions on 2026-10-14; each took its recommendation.

1. **Streams on Jetty is its own module**, so `pelican-streams` keeps its 1.0 dependencies.
