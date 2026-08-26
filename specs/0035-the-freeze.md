# 0035 — The freeze

## Problem

After spec 0034 the tree is the 1.0 surface, but nothing yet marks it
frozen: the dumps describe the pre-strip world, the reified-inline DSL is
invisible to BCV (a `json<Order>()` rename would pass every gate), and the
docs still tell users to expect breaking changes and pin a version. 1.0.0
needs the contract stated, guarded, and promised in that order.

## Not doing

- **No new features.** The freeze changes guardrails and words, not
  behaviour.
- **No tagging.** Cutting `v1.0.0` (or an RC first) is the maintainer's
  hand on the tag, not part of this spec.
- **No naming churn beyond what the maintainer decides** — the one open
  candidate is `FallibleOutput` (see Open questions).

## Shape

Every published module's `.api` dump regenerated on the slimmed tree and
committed as the 1.0 contract. A source-compatibility suite compiles pinned
DSL call sites — `json`, `jsonBody`, `pathParam`, `sse`, `ndjson`,
`errorJson`, `negotiated`, the builder entry points — against the published
surface, closing the synthetic-inline blind spot. A freeze audit re-runs the
review checklist on the kept surface: no public constructor over four
parameters, no `DefaultImpls`, no non-public supertypes in dumps, SPI
separation intact. The stability sections in README, reference.md and
CHANGELOG replace "expect breaking changes, pin a version" with the 1.0
promise: what BCV covers (root DSL and `spi`, per dump), what it does not
(internals), and what the golden tests add on top. CHANGELOG gains the
prepared `1.0.0` section.

## Why this shape

The dump is already the enforcement mechanism; the freeze is making it the
*stated* contract and closing the one hole it cannot see. The alternative —
promising stability in prose while the flagship names go unguarded — is the
gap the review called out, and it is cheap to close once and expensive to
discover after.

## Stack

- [ ] **`spec-0035-source-compat`** — the pinned-DSL compilation suite.
      Done when: renaming `json` locally fails the suite and nothing else does.
- [ ] **`spec-0035-freeze-audit`** — dumps regenerated and committed; the
      checklist run with findings fixed or recorded; `FallibleOutput`
      decision applied if the maintainer renames.
      Done when: `apiCheck` is green and the audit's findings list is in the PR body.
- [ ] **`spec-0035-the-promise`** — stability docs and the `1.0.0`
      CHANGELOG section.
      Done when: no doc says "expect breaking changes" about the shipped surface.

## Acceptance

```bash
./gradlew apiDump
./gradlew build
```

## Open questions

1. `FallibleOutput` — keep, or rename (`DeclaredResponses` was floated)
   while it is still free? Maintainer's call; the freeze-audit entry
   applies whatever is decided and this question must be answered before
   that entry starts.
2. RC first or straight to 1.0.0? Recommend `v1.0.0-RC1` in strangers'
   hands before the promise is signed; either way the tag is the
   maintainer's.
