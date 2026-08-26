# 0027 — The same value, two wire shapes

*Post-1.0.*

## Problem

An endpoint cannot offer the same success under two media types selected by
`Accept`. Every output pins one media type (`Outputs.kt:100,111`) and two
responses cannot share a status (`Fallible.kt:257-265`), so
`json<Order>(200) or xml<Order>(200)` is unconstructible; the honest
workaround is two endpoints. What exists is refusal-mode negotiation — a full
RFC 9110 `Accept` parse answering 406 before the handler, identical on all
three backends (`Negotiation.kt:20-60`). Teams that need CSV export or XML
for a legacy partner hit this and leave, or fork the endpoint per format.

## Not doing

- **No XML/CSV codecs in this spec.** The model change lands against a
  second JSON-adjacent representation (`text/csv` via a `PlainCodec`-style
  encoder in the example); real XML support is its own leaf module later.
- **No `Accept` weighting the handler can observe.** Selection is the
  interpreter's job; handlers keep returning values.
- **No request-side changes.** `NegotiatedBody` already covers requests.

## Shape

```kotlin
val export = endpoint(get("reports") / int("year"))
    .out(negotiated(json<Report>(200), csv<Report>(200)))
```

A new sealed `Output` subclass, `NegotiatedOutput`, holding alternatives that
share a status and differ by media type. The no-`else` mandate makes every
interpreter's `when` refuse to compile until it handles selection — the
extension mechanism the sealed hierarchy exists for. Selection reuses
`qualityOf` (`Negotiation.kt:50-60`); no acceptable alternative stays a 406.
OpenAPI emits the multi-entry content map it already produces for negotiated
request bodies (`OpenApi.kt:194-203`). The status-clash rule keeps refusing
duplicates *outside* a negotiated group, in the wording spec 0025 pinned.

## Why this shape

One sealed subclass is the smallest change that makes negotiation a value in
the description, which is the only place this library puts truth. The
alternative — handler-selected representation via a declared `Accept` input —
was rejected: it moves a protocol concern into every handler and cannot be
documented truthfully in OpenAPI.

## Stack

- [x] **`spec-0027-negotiated-output`** — the type, the builder, the
      status-clash carve-out, core tests.
      Done when: the sealed `when` in all three interpreters fails to compile until handled.
- [x] **`spec-0027-interpreters`** — selection in Pekko, http4k, Ktor;
      `ContentNegotiationTest` grows the selection cases.
      Done when: the same request with two `Accept` values gets two wire shapes, byte-pinned, on all three.
- [x] **`spec-0027-document-and-clients`** — multi-entry response content
      map; generated client sends `Accept` and exposes the chosen type.
      Done when: `OpenApiSpecQualityTest` passes and the round trip re-imports the negotiated response.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. What does the generated client's return type look like for a negotiated
   response — caller picks the representation at call time (recommended:
   one method per media type), or a sealed result?
2. Does `negotiated(...)` admit different *schemas* per media type?
   Recommend no — same value, different encoding; different schemas stay
   different statuses or endpoints.
3. Server-side default when no `Accept` header: first alternative in
   declaration order? Recommend yes, pinned.
