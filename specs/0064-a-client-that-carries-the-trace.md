# 0064 — A client that carries the trace

## Problem

`pelican-metrics-otel` continues a trace on the way in: an inbound `traceparent` makes the server's span a child of
the caller's. On the way out, nothing does. A Pelican client sends the headers its endpoint declares and no others,
so a service that calls another through a generated client starts a new trace at every call, and the span it was in
has no child for the call (lark-bank spec 0024: the bank's screening call to bank-checks).

## Not doing

- **No retries, timeouts or circuit breaking.** A transport decorator is the shape for those too; this spec only
  makes the one that traces.
- **No baggage.** W3C's propagator writes it if the context has any; nothing here adds to it.
- **No change to the server side.** `incomingHeaders` stays as it is.

## Shape

`pelican-core`'s `ClientRequest.withHeader` is all a decorator outside core needs, and is there already.

`pelican-metrics-otel` gains a transport that wraps another, opens a client span (`http.client.request.duration`,
attributed as the conventions say), and writes the current context's `traceparent` and `tracestate` with the
propagator the `OpenTelemetry` it is given holds:

```kotlin
val transport = PekkoHttpTransport().traced(openTelemetry)
val client = ScreeningClient(url, JacksonCodecs, transport, timeout)
```

A failed call ends its span with an error status, as does a 4xx or a 5xx: on a client span the conventions count both,
since the call did not get what it asked for. The answer is handed back as it came either way.

## Why this shape

A transport decorator rather than a filter in each generated client: every client already takes a transport, so
tracing is chosen where the transport is built, once per service, and works for clients generated before this. The
alternative, a header parameter the codegen adds to every operation, makes each call site pass the trace by hand.

## Stack

- [x] **`spec-0064-traced`** — `traced`, its client span and metric. Done when: a call made inside a span arrives at
      a server with that span's client span as the parent it continues from, over Pekko's transport, and a call that
      gets no answer ends its span as an error.
      Done: `ClientTracingTest` (the span, its parent, the headers a server extracts, 4xx and 5xx, a refused call, the
      histogram) and the example's `TracedCallTest`, over a socket to the JDK's `HttpServer`; that one lives in the
      example because this module's tests prove no transport or server is reachable from it. Pekko is the only
      `ClientTransport` there is; pelican-test's JDK client is a test `Transport`, not one.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Span name**: `{method} {route}` needs the route, which the transport sees only as a URL. Recommend `{method}`
   alone, as the conventions say when the route is unknown, with `url.full` attributed.
2. ~~`withHeaders`~~: `withHeader` was already public. Settled.
