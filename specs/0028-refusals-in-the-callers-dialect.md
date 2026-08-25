# 0028 — Refusals in the caller's dialect

*Post-1.0.*

## Problem

The built-in refusals — 400 undecodable, 406, 413, 500 undeclared — render a
fixed envelope, `ApiError(status, error, detail)` (`Api.kt:5-12`,
`ServerErrors.kt:49-56`), deliberately owned by core so three backends cannot
drift. Teams standardised on RFC 9457 `application/problem+json` cannot
comply for exactly the responses they do not control; declared failures
already carry any type `E`, so the gap is only the refusal path. Today the
workaround is a gateway rewriting bodies, which un-documents the contract.

## Not doing

- **No per-backend hooks.** One renderer in core or nothing; the whole point
  of the fixed envelope was agreement, and the replacement keeps it.
- **No filter access to rendered responses.** The boundary from the review
  stands; this is configuration, not middleware.
- **No change to declared failures.** `errorJson<E>` already answers this.

## Shape

```kotlin
val api = api(routes, codecs = JacksonCodecs) {
    refusals(ProblemDetails)        // or the default, ApiErrorEnvelope
}
```

A `RefusalRenderer` value in core: given the refusal (status, reason,
reference), produce body bytes and a content type. Core ships two: the
current envelope (default, unchanged bytes) and RFC 9457. All three
interpreters render refusals through it; the OpenAPI document describes
refusal responses with the configured envelope's schema.

## Why this shape

A value handed to `api { }` keeps the agreement property — one renderer,
three backends — and makes the document honest about what the wire says,
which a gateway rewrite never is. The alternative, exposing the raw
throwable to user code, was rejected: the renderer sees the classified
refusal, not the exception, so the 500 path cannot leak internals by
construction.

## Stack

- [ ] **`spec-0028-renderer-value`** — `RefusalRenderer`, the two shipped
      renderers, `renderError` routed through it.
      Done when: default output is byte-identical to today across the refusal suite.
- [ ] **`spec-0028-document`** — refusal schemas in the emitted document
      follow the configured renderer.
      Done when: `OpenApiSpecQualityTest` and the golden files agree with the selected envelope.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Does the renderer see the request (for `instance` in problem+json)?
   Recommend the path template only — no raw request, no header echo.
2. Should `RefusalsAcrossBackendsTest` run the whole suite twice, once per
   shipped renderer? Recommend yes; it is the agreement property.
