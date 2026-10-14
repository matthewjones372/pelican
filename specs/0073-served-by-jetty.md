# 0073 — Served by Jetty

## Problem

Pelican has one server, Pekko HTTP. A team that does not run Pekko cannot use
the library, and a team that does pays for stream materialisation on every
request body: the JSON POST in `docs/what-it-costs.md` costs 12.0µs against
742ns for a GET, because `toStrict` materialises. The http4k interpreter at
`a6c20b2` was a second server, but its stock servers do not flush a streamed
response (`StreamingSunHttp` exists only to fix that).

Specs 0073 to 0077 together give a service everything `pelican-pekko` and its
companions do, on Jetty, with no Pekko or Scala on its classpath. This one is
the interpreter.

## Not doing

- **http4k and Ktor.** They return later as restores from `a6c20b2`.
- **Servlet.** Jetty 12's core `Handler`, not `jakarta.servlet`.
- **`-docs`, `-mcp`, `pelican-test-jetty`, `pelican-streams` on Jetty, the
  importer's `Backend.JETTY`.** Spec 0074.
- **A client without Pekko and the classpath proof.** Spec 0075.
- **Benchmarks.** Spec 0077.
- **HTTP/2 and TLS.** The caller builds the `Server` and its connectors.

## Shape

```kotlin
import io.github.matthewjones372.pelican.jetty.start
import io.github.matthewjones372.pelican.jetty.toHandler

val server = api.start(port = 8080, host = "0.0.0.0")  // handlers = Handlers.onVirtualThreads
server.stop()

// Mounted in a Jetty the service already runs.
Server(8080).apply { handler = api.toHandler() }.start()

ingest handledBy { rows: StreamIn<Row> -> completedFuture(Tally(rows.toSequence().count())) }
```

- Every binder `pelican-pekko` has, under the same name, with `Sequence` where
  Pekko takes a `Source`: `handledBy`, `handledNow`, `handledWith`,
  `handledOrFail`, `handledByOrFail`, `handledOneOf`, `handledByOneOf`,
  `streamedBy`, `streamedNow`, `streamedOrFail`, `streamedByOrFail`,
  `bytesNow`, `bytesOrFail`; and `Params.request` as Jetty's `Request`.
- `start` returns a `PelicanServer` with Pekko's `stop()` / `stopAsync()` contract.
- Bodies are read into bytes and decoded with `decodeFrom(bytes)`.
- A streamed response writes one frame per `Response.write` callback. SSE
  keep-alive runs on the server's `Scheduler`.

## Why this shape

Jetty's core API is asynchronous and demand-driven, so backpressure and
flushing come from the server rather than a wrapper, and a synchronous handler
starts on a virtual thread instead of hopping to one. Restoring http4k first is
less new code but keeps the flush workaround. Recommended: Jetty first.

## Stack

- [ ] **`spec-0073-serve`**: module, `toHandler`, `start`/`stop`, routing on
      `RouteIndex`, 404/405, `handledNow`/`handledBy`/`handledWith`, strict bodies out.
      Done when: a socket test answers GET, 404 and 405 as Pekko does.
- [ ] **`spec-0073-params`**: path, query, header and cookie params, lists, defaults.
      Done when: a bad path param is a 400 counted against its own route.
- [ ] **`spec-0073-bodies`**: bytes decoding, `maxBodyBytes` declared and chunked,
      raw bodies, `bytesNow`/`bytesOrFail`.
      Done when: an over-limit chunked body is a 413.
- [ ] **`spec-0073-forms`**: form bodies and multipart uploads.
      Done when: an upload reaches the handler as an `UploadedFile`.
- [ ] **`spec-0073-failures`**: `DeclaredResponses`, `handledOrFail`,
      `handledOneOf`, tagged failures, `errorMedia`.
      Done when: Pekko's `ErrorMediaTest` cases pass on Jetty.
- [ ] **`spec-0073-edges`**: filters, refusal renderer and observer, CORS,
      HEAD, content negotiation.
      Done when: a preflight answers as Pekko's does.
- [ ] **`spec-0073-callers`**: `Credentials`, the caller check, `Handlers`, pages
      and the page guard.
      Done when: Pekko's `BlockingHandlerTest` and `PagesServedTest` cases pass, by gates.
- [ ] **`spec-0073-stream-out`**: NDJSON, array and SSE out, keep-alive,
      disconnect stops the producer.
      Done when: a slow-consumer test shows the producer held, by a gate.
- [ ] **`spec-0073-stream-in`**: streamed uploads as a `Sequence`, frame limits.
      Done when: an undecodable frame is a 400 naming its line.
- [ ] **`spec-0073-parity`**: `OnJetty` in `allBackends`, its `Dialects`.
      Done when: every `example/backends` suite passes on both, with one document.

## Acceptance

```bash
./gradlew build
```

## Decisions

Matt delegated the open questions on 2026-10-14; each took its recommendation.

1. **Jetty 12.1**, the newest release at least two weeks old, as `api`: Jetty
   has no Scala cross-build to leave to the caller.
2. **A streamed upload is a blocking `Sequence`**, cheap on a virtual thread.
