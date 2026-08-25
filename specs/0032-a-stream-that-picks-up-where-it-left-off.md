# 0032 — A stream that picks up where it left off

*Post-1.0.*

## Problem

SSE outputs cannot declare `id:` or `retry:` fields — the frame writer emits
`event:`/`data:` only (`Outputs.kt:156-159`), a deliberate, documented
choice (`OpenApi.kt:287-295`). Without event ids there is no
`Last-Event-ID`, so every reconnecting browser or client replays from now
and misses what it lost. Teams building anything with delivery expectations
— order status, notifications — currently bolt ids into the payload and
reimplement resume by hand on both sides.

## Not doing

- **No broker.** Pelican frames and delivers; retention, replay storage, and
  fan-out stay the service's problem. The handler receives the resume point
  and decides what to do with it.
- **No WebSockets.** Different protocol, different spec, if ever.
- **No change to NDJSON/byte streams.** Resume is an SSE concept.

## Shape

```kotlin
val watch = endpoint(get("orders") / long("id") / "events")
    .out(sse<OrderEvent>(id = { it.sequence.toString() }, retry = 15.seconds))

watch streamedNow { (orderId) ->
    eventsSince(orderId, lastEventId())   // Params extension, null on fresh connect
}
```

The `sse` declaration optionally carries an id extractor and a retry hint;
the frame writer emits them; `lastEventId()` reads the `Last-Event-ID`
header. The document notes the resume contract in the event schema's
description. Client side: the generated client and transports surface the
last seen id on reconnect-capable calls.

## Why this shape

The id extractor keeps ids a projection of the event value — one source of
truth, no parallel sequence state in the interpreter. The alternative — a
`StreamEvent<T>` wrapper the handler returns — types the id explicitly but
makes every existing SSE handler change shape; an optional extractor is
additive and byte-identical when absent, which keeps the parity suite's
existing pins green.

## Stack

- [ ] **`spec-0032-frame-fields`** — id extractor and retry hint in the
      declaration, frame writer, and all three backends' write paths.
      Done when: frames are byte-identical across backends with and without ids, pinned in `AllBackendsTest`.
- [ ] **`spec-0032-resume-input`** — `lastEventId()` on `Params`, the
      document note, a parity test reconnecting with `Last-Event-ID`.
      Done when: a reconnect delivers the header value to the handler on all three.
- [ ] **`spec-0032-clients`** — the SSE-consuming paths surface event ids
      and pass `Last-Event-ID` on retry.
      Done when: a client suite resumes a stream against the example service.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Does `retry` belong per-endpoint or per-`Api`? Recommend per-endpoint —
   it is contract, not configuration.
2. Should `lastEventId()` be a typed input (`sseResume()` in the path DSL)
   instead of a `Params` extension? Recommend the extension — resume is
   optional by nature and should not change the endpoint's input arity.
