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

`pelican-core`'s `ClientRequest` gains the one thing a decorator outside it needs, a copy with headers added:

```kotlin
fun ClientRequest.withHeaders(vararg added: Pair<String, String>): ClientRequest
```

`pelican-metrics-otel` gains a transport that wraps another, opens a client span (`http.client.request.duration`,
attributed as the conventions say), and writes the current context's `traceparent` and `tracestate` with the
propagator the `OpenTelemetry` it is given holds:

```kotlin
val transport = PekkoHttpTransport().traced(openTelemetry)
val client = ScreeningClient(url, JacksonCodecs, transport, timeout)
```

A failed call ends its span with an error status; a response is an error only when it is 5xx, as the conventions say
for a client.

## Why this shape

A transport decorator rather than a filter in each generated client: every client already takes a transport, so
tracing is chosen where the transport is built, once per service, and works for clients generated before this. The
alternative, a header parameter the codegen adds to every operation, makes each call site pass the trace by hand.

## Stack

- [ ] **`spec-0064-traced`** — `withHeaders`, `traced`, its client span and metric. Done when: a call made inside a
      span arrives at a Pelican server with that span as the server span's parent, over both the JDK and Pekko
      transports, and a call to a closed port ends its span as an error.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Span name**: `{method} {route}` needs the route, which the transport sees only as a URL. Recommend `{method}`
   alone, as the conventions say when the route is unknown, with `url.full` attributed.
2. **Is `withHeaders` public API?** Recommend yes: it is what any decorator needs, and adding to a request is safe
   where replacing one is not.
