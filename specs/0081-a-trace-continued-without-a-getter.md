# 0081 — A trace continued without a getter

## Problem

`openTelemetry(telemetry, incomingHeaders = …)` continues a caller's trace only when the service supplies a
`TextMapGetter<Params>` that reads a header off its backend's own request. Without one, extraction is off and every
inbound `traceparent` starts a new trace, silently. Pelican ships no getter for either backend, so every service
writes one (petshop's `requestHeaders`, PR matthewjones372/petshop#74, reads Jetty's `Request` headers), and a
service that forgets loses its traces without an error.

## Not doing

- **New span attributes.** Only extraction changes.
- **Removing `incomingHeaders`.** It stays, for a service that reads headers from somewhere else.

## Shape

```kotlin
api(endpoints, codecs = KotlinxCodecs, filters = listOf(openTelemetry(sdk)))   // continues inbound traces, any backend
```

- `Params` gains a backend-neutral read of the request's headers: `params.requestHeader(name): String?` (first
  value, case-insensitive). Each interpreter supplies it from the request it already reads for the caller check
  (`Credentials` on Pekko and Jetty), so core names no backend type.
- `openTelemetry`'s `incomingHeaders` defaults to a getter built on it, and extraction is on by default.
- A hand-built `Params` with no request reads null for every header, as now.

## Why this shape

The interpreters already hold a header reader for the caller check (spec 0061). Exposing it on `Params` makes every
filter backend-neutral where it only needs headers, and `openTelemetry` becomes correct with no argument. The
alternative is one getter per backend in each backend module, which keeps users choosing the right one and ties
`pelican-metrics-otel` to both backends. Recommended: the neutral read.

## Stack

- [ ] **`spec-0081-headers`**: `Params.requestHeader(name)`, supplied by `pelican-pekko` and `pelican-jetty`.
      Done when: a filter reads the same header on both servers in the parity suite.
- [ ] **`spec-0081-otel`**: `incomingHeaders` defaults to it; docs and the petshop-shaped example drop their getter.
      Done when: a test sends a `traceparent` with no getter configured and the server span is its child, on both servers.

## Acceptance

```bash
./gradlew build
```

## Decisions

Matt asked for this on 2026-10-15: users should not write the bridge. Surefold spec 0140 is the companion.
