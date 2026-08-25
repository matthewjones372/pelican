# 0033 — Typed frames in the other direction

*Post-1.0.*

## Problem

Streamed responses are typed — `StreamOf<T>` with core-owned NDJSON/SSE
framing — but a streamed *request* is raw bytes only: `RawBody` handed over
unconsumed (`pekko Interpreter.kt:291-294` and siblings). A bulk-ingest
endpoint that wants NDJSON upload decodes frames by hand in the handler,
per service, with none of the codec/schema/document agreement the response
side gets. The document cannot say "the request body is a stream of `Item`"
because the description cannot.

## Not doing

- **No streamed multipart parts.** Multipart already streams files; typed
  frame decoding inside parts is a different, rarer ask.
- **No SSE requests.** SSE is server-to-client; NDJSON is the upload framing.
- **No backpressure abstraction.** Same boundary as responses: the backend's
  native type carries flow control.

## Shape

```kotlin
val ingest = endpoint(post("items") / "bulk")
    .inStream(ndjsonIn<Item>())          // Endpoint<StreamIn<Item>, Summary>
    .out(json<Summary>(202))

// binder per backend, mirroring streamedNow's types
ingest handledNow { items: Sequence<Item> -> ingestAll(items) }   // http4k
// Source<Item, NotUsed> on Pekko, Flow<Item> on Ktor
```

Core owns the frame *splitting* and per-frame decode (mirror of
`NdjsonOutput.frame`); each backend feeds its native stream through it. The
document emits the request body with `itemSchema`, exactly as responses do.
Oversize applies per frame plus a declared total cap; a malformed frame is a
400 refusal naming the frame index.

## Why this shape

It is the response streaming design reflected, which is why it is credible:
phantom marker in core, native carrier per backend, framing owned once,
parity assertable to the byte. The alternative — leaving uploads raw and
documenting the recipe — is where 1.0 correctly stopped; this spec exists
for the moment a real ingest user shows up, and should not be built before
one does.

## Stack

- [ ] **`spec-0033-stream-in-core`** — `StreamIn<T>`, `ndjsonIn`, frame
      splitter/decoder, refusal semantics, document emission.
      Done when: core tests pin split, decode, refusal-with-index, and the emitted request body.
- [ ] **`spec-0033-binders`** — the three backends' binders and interpreter
      paths, unbuffered.
      Done when: a first-frame-processed-before-last-sent timing test passes per backend.
- [ ] **`spec-0033-clients`** — generated client accepts a streamed body
      (`Sequence<T>`/`Flow<T>` per call style) over the existing re-openable
      body seam.
      Done when: the round trip streams both directions in one call against the example service.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Does `inStream` compose with other inputs (path params yes, a second
   body obviously no)? Recommend path/query/header params compose; the
   stream is the body slot.
2. Per-frame size cap: reuse `maxBodyBytes` or a separate
   `maxFrameBytes`? Recommend separate with a default derived from
   `maxBodyBytes`, since one giant frame is the actual attack.
3. What does the *test* client do — it buffers bodies today
   (`ApiClient.kt:118-125`)? Recommend it sends buffered frames (test
   ergonomics) while the generated client streams; timing tests stay with
   raw transports.
