# 0030 — The transport Android already runs

*Post-1.0.*

## Problem

On Android there is no `java.net.http`, and OkHttp is the native stack. A
generated Pelican client cannot run there today except through
`KtorHttpTransport(HttpClient(OkHttp))` — a real escape hatch
(`KtorHttpTransport.kt:52-59`) but one that drags the Ktor client machinery
into an app that already ships OkHttp. The roadmap already frames adapters
as "one transport per HTTP library the service already runs"
(`docs/roadmap.md:119-156`); Android teams are the audience this list is
missing.

## Not doing

- **No Android module, no AndroidX, no Compose anything.** `pelican-client-okhttp`
  is a plain JVM module depending on core and `okhttp` only, exactly like
  its three siblings; Android compatibility is a consequence, not a target.
- **No interceptor bridging.** OkHttp interceptors stay OkHttp's business;
  Pelican decoration stays `ClientTransport` decoration.
- **No changes to the seam.** `ClientTransport` is proven against three
  engines; a fourth adapter earns no new methods.

## Shape

```kotlin
val client = OrdersClient(OkHttpTransport(okHttpClient))
```

The fourth one-class adapter: `send(ClientRequest): CompletionStage<ClientResponse>`
over `Call.enqueue`, streaming request bodies via a re-openable
`RequestBody`, the response handed back as the unread source stream,
cancellation wired from the stage to `call.cancel()`, head-only timeout
semantics matching JDK and Pekko, ServiceLoader registration beside the
other three.

## Why this shape

The existing transport test suites (streaming timing, backpressure,
cancellation, retry interaction) are the specification; a fourth
implementation against them is a bounded, low-risk module with outsized
reach. The alternative — documenting the Ktor-engine escape hatch as the
Android answer — was the right pre-1.0 call and stays in the docs, but it
makes Pelican look like a guest on Android rather than a resident.

## Stack

- [ ] **`spec-0030-okhttp-transport`** — the module, the adapter, the
      ServiceLoader file, the transport test suite run against it.
      Done when: the shared transport tests (streaming, cancellation, timeout, retry) pass on OkHttp.
- [ ] **`spec-0030-docs`** — `docs/modules.md`, the generated-client docs'
      transport table, CHANGELOG.
      Done when: the dependency claims in `docs/modules.md` are tests, as for the other three.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Minimum OkHttp version — 4.x (Kotlin, current) or 3.x floor for old
   Android fleets? Recommend 4.x; teams pinned to 3.x keep the Ktor-engine
   route.
2. Should the shared transport tests be extracted into a reusable suite
   first (they are per-module today)? Recommend yes if the copy exceeds
   ~200 lines, decided in the first PR.
