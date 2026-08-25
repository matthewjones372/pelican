# 0025 — Say exactly what is promised

## Problem

The reference over-claims once, exactly where the library's credibility
lives: `reference.md:3977-3978` says an undeclared response "does not
compile, exactly as an undeclared failure does not" — and the repo's own
negative test proves the failure half compiles
(`DoesNotCompileTest.kt:124-133`); with `T` widened to `Any`, so does the
success half. The real guarantee — a named runtime `UndeclaredResponse` — is
better than the false one, and unstated. Around it, smaller truths are
unwritten: only two of the four compiler messages the docs quote are pinned
as fixtures; failures are always JSON in code (`Fallible.kt:222-226`,
`OpenApi.kt:237-239`) and said nowhere; client-disconnect semantics differ
per backend (Ktor cancels the handler, Pekko and http4k run it out) and are
undocumented; pelican-mcp is descriptions-only with no server and the README
does not say so at the stability promise; the Kover floor reads 80 in
`AGENTS.md:222,236` and is 90 in the build; and the status-clash rule
(`Fallible.kt:257-265`) needs one sentence of wording so a future negotiated
output sharing a status arrives as an extension, not a re-reading.

## Not doing

- **No new machinery.** This spec changes documentation and adds test
  fixtures; the only source edits are comments and one KDoc.
- **No fixing the holes the docs will now admit.** `T`-widening, the lens
  escape hatch, and tuple swaps stay documented trade-offs.

## Shape

Every claim the reference stakes on a compiler message has a fixture in
`DoesNotCompileTest`; every hole it admits has a fixture pinning the hole
open, in the pattern the harness already established. The reference states,
in its refusal tables: failures encode as JSON; disconnect semantics per
backend; the runtime nature of the undeclared-response guarantee; the
status-clash wording. The README's module table marks pelican-mcp
"descriptions half; serving is roadmap".

## Why this shape

The harness KDoc itself records that four doc quotations were stale until
someone read them (`DoesNotCompileTest.kt:18-31`); pinning is the mechanism
the repo already chose for keeping prose honest, so the fix is more of it,
not a new gate. The alternative — softening the docs to promise less — was
rejected: the actual guarantees are strong and specific, and stating them
exactly is the better sales pitch.

## Stack

- [ ] **`spec-0025-doc-truth`** — the false sentence rewritten around
      `UndeclaredResponse`; failures-are-JSON, disconnect semantics,
      status-clash wording, MCP label, Kover numbers, the stale
      `RoutingTest` comment (`ktor Interpreter.kt:61`).
      Done when: no claim in `reference.md` contradicts a test, checked by reading the diff against the fixture list.
- [ ] **`spec-0025-fixtures`** — fixtures for the wrong-input-type,
      wrong-stream-element, and value-for-stream messages
      (`reference.md:3038-3049`); a pinned-open fixture for lens-style
      undeclared-key reads; the seventh-input refusal.
      Done when: every compiler message quoted in the reference appears verbatim in a fixture assertion.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Should the reference's quoted messages be generated from the fixtures
   rather than pinned against them? Recommend no for 1.0 — pinning is
   enough, generation is tooling this close to release.
2. Does the MCP label belong in `Compatibility`/BCV terms too (exclude
   pelican-mcp from the 1.0 promise), or docs only? Recommend docs only —
   the surface is small and stable enough to keep in the promise.
