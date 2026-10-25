# 0088 — Served on io_uring

## Problem

Spec 0087 gives a per-core io_uring runtime with HTTP/1.1, but nothing a Pelican
`Api` can be served on. This spec is the interpreter, its `-docs` companion and the
wiring that proves it: the shared suite and the wire benchmark with three servers.

## Not doing

- **Everything 0087 leaves out:** TLS, HTTP/2, compression, non-Linux.
- **`-mcp`, `-test-uring`, `-streams-uring`, the importer's `Backend.URING`.** None is
  asked for by a parity suite; `-mcp` (80 lines on Jetty) and `-streams` (38) are
  near copies for a later spec. `-docs` is in: `StartParityTest` reflects on it.
- **Mounting in a foreign server.** There is no host to hand a handler to.

## Shape

`backends/uring/pelican-uring` and `pelican-uring-docs`, package
`io.github.matthewjones372.pelican.uring`, the same entry points as Jetty:

```kotlin
import io.github.matthewjones372.pelican.uring.start
import io.github.matthewjones372.pelican.uring.toHandler

val server = api.start(port = 0, host = "127.0.0.1")    // handlers = Handlers.default
server.stop()                                            // stopAsync(), block(), close()

api.start(handler = { toHandler() then docsHandler(docs) })  // composition seam
getUser handledNow { id, _ -> repo.find(id) }                // the 13 binders, Sequence for streams
```

- `UringHandler` returns a reply or declines, so `then` asks the next one: the seam
  `-docs`, pages and `MountedAlongsideTest` need. 404/405 merge as `Declines.kt` does.
- `Server.kt` holds `start`, `Handlers.kt` the binders, `HandlerThreads.kt` the
  `Handlers` class, `docs/Docs.kt` `startWithDocs`: the names the parity tests reflect on.
- `Params.request` is the runtime's request; `ByteStreamHandle` and `FrameReader`
  match Jetty's, reading through a timed blocking input with `wake()`.

**Threading.** `Handlers.default` and `onVirtualThreads` run a synchronous handler,
with its filters and authenticator, on a virtual thread, as Jetty and Pekko do; the
result returns through 0087's queue and `eventfd`. `Handlers.onDispatcher` runs it
inline in the connection's fiber on the carrier: fastest, and it stalls the core if
the handler blocks. `on(executor)` as on Jetty. `…By` handlers never move. Stream
iteration, multipart and NDJSON reads always leave the carrier.

## Why this shape

**Pipeline first?** `docs/roadmap.md` item 2 says to move the shared steps (plain
inputs, codec resolution, size refusal, CORS folding) into core before any further
backend. Option A: that hoist as its own spec before this one, leaving this
interpreter near Jetty's ~1,600 lines minus the hoisted part. Option B: copy from
Jetty now and hoist later across three. Recommended: A; a third copy is the case
the roadmap names, though it delays this spec by one.

**JDK floor** (`build.gradle.kts:209`). Option A: add `example` and `benchmarks` to
the floor-25 set; one line, but the JDK 21 job stops proving the example on 21.
Option B: move the backend-blind suites into a floor-21 test-kit module each backend
runs. Option C: a floor-25 source set in `example`, fiddly under `subprojects {}`.
Recommended: A now (benchmarks already runs on 25), B as its own spec.

**Tests.** Each entry ports its Jetty differential test with Jetty as the oracle (no
Scala on the classpath). `OnUring` joins `allBackends`; the suites that name a
backend get a row: `HeadRequestsTest`, `StartParityTest`, `ServerShapeParityTest`,
`BinderNameParityTest`, `UnboundedStreamTest`, `SlowConsumerTest`, `SseKeepAliveTest`,
`UploadTimingTest`, `MountedAlongsideTest`, `MethodMismatchTest`,
`ContentNegotiationTest`, `BareOkTest`, and a `UringDifference` enum in `Dialects.kt`.

## Stack

- [ ] **`spec-0088-serve`**: `toHandler`, `start`, routing, 404/405, decline seam, strict out.
      Done when: `ServedAsJettyServesTest` passes.
- [ ] **`spec-0088-params`**: path, query, header, cookie params; credentials and caller.
      Done when: params and callers read as Jetty reads them.
- [ ] **`spec-0088-bodies`**: strict bodies, limits, 408, raw, bytes, forms, multipart.
      Done when: an over-limit chunked body is a 413 on a connection that lives.
- [ ] **`spec-0088-failures`**: declared responses, refusals, CORS, HEAD, negotiation.
      Done when: failures, refusals and preflights answer as Jetty's do.
- [ ] **`spec-0088-threads`**: `Handlers`, the virtual-thread hop, `onDispatcher`.
      Done when: a 404 answers while every handler thread blocks, by a gate.
- [ ] **`spec-0088-stream-out`**: NDJSON, SSE, array; keep-alive; disconnect stops the producer.
      Done when: a slow consumer holds the producer, by a gate.
- [ ] **`spec-0088-stream-in`**: NDJSON in, timed reads, `wake`, streamed file part.
      Done when: the handler has the first frame before the last is sent.
- [ ] **`spec-0088-pages`**: pages, ETag, 304/412, redirects, the guard.
      Done when: pages, from a directory and a jar, serve as Jetty serves them.
- [ ] **`spec-0088-docs`**: `pelican-uring-docs`, `startWithDocs`.
      Done when: the docs pages serve as Jetty's do.
- [ ] **`spec-0088-parity`**: floor change, `OnUring` in `allBackends`, the rows above.
      Done when: every `example/backends` suite passes on three backends, one document.
- [ ] **`spec-0088-wire`**: `uring` in `WireServer.kt` and `WireDriver.kt`'s server list.
      Done when: `build/wire-result.md` has a column per backend.

## Acceptance

On Linux 6.1+:

```bash
PELICAN_REQUIRE_URING=1 ./gradlew build
./gradlew :benchmarks:wire              # pekko, jetty and uring in one table
```

## Open questions

1. **`/../x` and `//x`.** Jetty says 400, Pekko 404. Recommended: answer as Pekko,
   since `RouteIndex` already sees the raw path, and record it in `UringDifference`.
2. **Which threading does the benchmark run?** The prototype's numbers ran handlers
   inline, as `onDispatcher` would. Recommended: `default` stays on virtual threads,
   and `:benchmarks:wire` measures both, so the hop's cost is a number.
3. **Released or main-only?** Recommended: main-only, labelled so, like Jetty's family.
