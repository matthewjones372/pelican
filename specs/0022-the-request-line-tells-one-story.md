# 0022 — The request line tells one story

## Problem

Path decoding is wrong, and wrong differently per backend. `RouteIndex.decode`
uses `URLDecoder` — form semantics — so `+` in a capture becomes a space
(`RouteIndex.kt:126`): `/tags/c++` delivers `"c  "`. Literal segments match
raw while captures match decoded (`RouteIndex.kt:83` vs `:45`), so `%61`
matches a capture as `a` but fails a literal `a`. The backends then disagree
on what they feed the router: Pekko passes an already-decoded path
(`pekko Interpreter.kt:94` — `%2F` changes the segment count), http4k passes
raw, and Ktor bypasses `RouteIndex` entirely with its own tree
(`ktor Interpreter.kt:55-88`), which also makes trailing-slash handling
diverge: the index collapses slashes (`RouteIndex.kt:107-111`), Ktor's router
decides for itself. Malformed `%zz` makes `URLDecoder` throw inside `match`,
plausibly a 500. Not one test sends `+`, `%2B`, or `%2F` in a segment. This
is the parity promise, broken at the first thing every request does.

## Not doing

- **No routing DSL changes.** `PathSpec`, templates, and precedence rules
  stay; only decoding and the input contract change.
- **No matrix-parameter or fragment support.** Out of scope, refused loudly.
- **No forcing Ktor through `RouteIndex`** unless question 2 says otherwise —
  equivalence can be pinned by tests instead.

## Shape

The router's input contract, stated and tested: every backend hands
`RouteIndex` the **raw** request path; the index splits on `/` first, then
percent-decodes each segment once with a percent-only decoder (`+` is a
literal), and matches literals and captures against the same decoded form.
Malformed percent-escapes are a 400 refusal, never an exception.

```kotlin
// core, replacing URLDecoder
internal fun decodeSegment(raw: String): DecodedSegment  // Ok(text) | Malformed
```

On top: a property-based parity test in `example/backends` — for arbitrary
`s`, a typed-client call to `/items/{s}?q=s` delivers exactly `s` on all
three backends, and any request line the client could not have produced
answers 4xx, never 5xx.

## Why this shape

Split-then-decode is what RFC 3986 implies and keeps `%2F` inside its
segment. The alternative — decode-then-split, with `%2F` documented as
unsupported — is simpler but silently changes routing on hostile input,
which is the bug class this spec exists to close. Percent-only decoding in
core rather than trusting each engine keeps the answer identical by
construction for the two index backends and testable for Ktor.

## Stack

- [x] **`spec-0022-percent-decoder`** — the decoder in core, literal/capture
      matching unified on decoded segments, `%zz` → refusal, `RouteIndexTest`
      pins `+`, `%2B`, `%2F`, `%20`, `%61`.
      Done when: the pinned cases pass and the decoder rejects malformed escapes as values, not throws.
- [x] **`spec-0022-backend-inputs`** — Pekko hands the raw path to the index;
      Ktor either adopts the index or pins equivalence (question 2); the
      trailing-slash answer is one answer.
      Done when: an encoded-path suite in `AllBackendsTest` agrees on all three.
- [x] **`spec-0022-property-parity`** — the property test over `allBackends`,
      plus query edges: `?q=` vs absent vs bare `?q`, plus-as-space in query
      values.
      Done when: 1000 generated cases pass per backend in CI. (Shipped at 200/backend for build time; PR #84.)

## Acceptance

```bash
./gradlew build
```

## Open questions

1. `%2F` inside a capture: deliver the decoded `/` to the handler, or refuse
   the request? Recommend deliver — split happened first, so it is
   unambiguous — and pin it.
2. Ktor: adopt `RouteIndex` for dispatch, or keep its tree and pin
   equivalence? Recommend adopting — spec 0018 built the index for exactly
   this, and one router is cheaper to prove than two equal ones.
3. Trailing slash: collapse everywhere (index behaviour) or 404 everywhere?
   Recommend collapse everywhere; it is what two backends already do.
4. Property test dependency — kotest-property is already on the classpath
   via kotest; any objection to using it in `example` only? Recommend no
   new module, `example` test scope only.
