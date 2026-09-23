# 0055 — The renderer that has not shipped

## Problem

The problem is [spec 0054](0054-the-document-read-a-second-way.md)'s: one UI,
Swagger UI 5 from a hardcoded unpkg URL, and a team publishing a reference for
readers writes the page itself.

This is the same change against **Redoc `3.0.0-rc.0`**, written to be chosen
against 0054 rather than after it. It is a separate page because 3.0 is not
released and the differences are not cosmetic:

| | 2.5.4 | 3.0.0-rc.0 |
|---|---|---|
| npm dist-tag | `latest`, published 2026-09-10 | `next`, published **2025-10-24** |
| newer 3.x | — | none in eleven months; no rc.1, no GA |
| standalone bundle | `bundles/redoc.standalone.js` | `bundle/…` — singular |
| bundle size | 1.05 MB | 1.44 MB |
| WebJar on Maven Central | `org.webjars.npm:redoc:2.5.0` | **none for any 3.x** |
| attribution file beside it | `…js.LICENSE.txt` | not published |
| browser global | present | not found in the bundle |

Both MIT. Read from the npm registry, jsDelivr's file listing and the bundles
themselves on 2026-09-23.

## Not doing

Everything 0054 does not do, and one more: **no 3.0 API beyond the element.**
The RC's `theme` handling is where its appeal is and where it is likeliest to
move before GA. Nothing here reaches for it.

## Shape

0054's, down to the `DocsUi` knob and the `redocHtml` signature. Only the URL
differs, and it cannot be pinned loosely:

```kotlin
// there is no `redoc@3` to follow: a major tag does not resolve to a
// pre-release, so the exact version is the only thing that can be written.
"https://cdn.jsdelivr.net/npm/redoc@3.0.0-rc.0/bundle/redoc.standalone.js"
```

## Why this shape

**For.** 3.0 is where Redoc's theming went, so adopting 2.x now means adopting
3.x again later — a second page, a second round of docs, and a URL that moves
under a version this project has promised is stable.

**Against, which is the recommendation.** A release candidate that has sat
eleven months without an rc.1 or a GA, while the 2.x line kept shipping — 2.5.4
is a fortnight old — is not one that is about to land. This repo is one release
from 1.0, whose stability promise covers the published surface, and `docsPath`
serving a page is part of that surface even though the bytes come from a CDN.

**One concrete unknown, not a preference.** `openApiPath = null` embeds the
document rather than fetching it, which needs a programmatic entry point.
Grepping 2.5.4's bundle finds a browser global; grepping 3.0.0-rc.0's finds
`spec-url` and `theme` and no global assignment and no `customElements` literal.
A minified bundle is weak evidence for an absence, so that is a thing to test,
not a finding — but if it holds, taking the RC means dropping the inlined form
or supporting it under one renderer only, which is a behaviour difference
between two renderers that are otherwise interchangeable.

## Stack

0054's two entries, with a gate in front of them.

- [ ] **`spec-0055-redoc3-render`** — no production code. Serve a Redoc
      `3.0.0-rc.0` page from a scratch file against `example`'s document, both
      with `spec-url` and with an inlined document.
      Done when: **Measured** says in a paragraph whether the inlined form
      works, with the page that proved it.
- [ ] **`spec-0055-redoc3-html`** — as `spec-0054-redoc-html`, against the RC.
      Done when: the same claims hold, or the spec records which does not.
- [ ] **`spec-0055-redoc3-route`** — as `spec-0054-redoc-route`.

## Acceptance

```bash
./gradlew build
curl -s http://127.0.0.1:8080/api-docs | grep 'redoc@3.0.0-rc.0/bundle/'
```

## Open questions

1. **This one or 0054?** Recommend 0054. This page is worth having either way,
   so the next person to ask *"why 2.x?"* finds the eleven months in a file
   rather than re-deriving them.
2. **What happens at GA?** The URL changes and the page changes with it,
   silently, for everyone who upgrades Pelican. Recommend that taking this spec
   also means pinning Swagger UI exactly — one policy for both pages rather than
   a loose `@5` beside an exact pre-release.
3. **Is there a third option?** Ship neither, and document how to serve your own
   page from the generated document. Recommend against — that is the status quo
   and the status quo is what **Problem** is about — but it is the honest floor
   to measure both specs against.
