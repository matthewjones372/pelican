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

- [x] **`spec-0055-redoc3-render`** ([#161](https://github.com/matthewjones372/pelican/pull/161)) — no production code. Serve a Redoc
      `3.0.0-rc.0` page from a scratch file against `example`'s document, both
      with `spec-url` and with an inlined document.
      Done when: **Measured** says in a paragraph whether the inlined form
      works, with the page that proved it. **Both work, and the gate found
      something else.**
- [ ] ~~**`spec-0055-redoc3-html`**~~ — not happening; see Measured. — as
      `spec-0054-redoc-html`, against the RC.
      Done when: the same claims hold, or the spec records which does not.
- [ ] ~~**`spec-0055-redoc3-route`**~~ — not happening; see Measured. — as
      `spec-0054-redoc-route`.

## Acceptance

```bash
./gradlew build
curl -s http://127.0.0.1:8080/api-docs | grep 'redoc@3.0.0-rc.0/bundle/'
```

## Measured

Headless Chromium, `example`'s own document, both bundles served locally so the
CDN was not the variable. The question this entry existed to answer is answered
— and it is not the one that matters.

**Both forms work.** The embedded form does have a programmatic entry point, so
`openApiPath = null` keeps its meaning. But neither form works the way 0054
writes it, because **the RC's bundle is an ES module**:

```
v3-url.html   rendered=NO  errors=SyntaxError: Unexpected token 'export'
```

A classic `<script src=…>` cannot load it. There is no `window.Redoc`, and the
two forms become:

```html
<redoc spec-url="/openapi.json"></redoc>
<script type="module" src="…/redoc.standalone.js"></script>
```

```html
<script type="module">
  import { init } from '…/redoc.standalone.js';
  init(spec, {}, document.getElementById('ui'));
</script>
```

Both then render: `h1` *"Orders (1.0.0)"*, the description, the servers and the
operations, plus a search box 2.x has no equivalent of.

### What the gate actually found

**The RC sends telemetry, and the form 0054 uses cannot switch it off.**
Rendering the fetching form makes an offsite request the 2.x page never makes:

| page | offsite hosts |
|---|---|
| `2.5.4`, fetching | `cdn.redoc.ly` |
| `3.0.0-rc.0`, fetching | `cdn.redoc.ly`, **`otel.cloud.redocly.com`** |
| `3.0.0-rc.0`, embedded, `disableTelemetry: true` | `cdn.redoc.ly` |

`@redocly/redoc-opentelemetry` is a dependency of the RC and not of 2.5.4, whose
bundle does not contain the string `otel` at all. The default is on —
`disableTelemetry:!1` in the bundle — and the option is reachable only through
`init`'s second argument. The `<redoc spec-url>` element's own auto-initialiser
reads exactly one attribute and calls `init` with `{}`:

```js
function Ure(){const e=Th("redoc");if(!e)return;const t=e.getAttribute("spec-url");t&&sC(t,{},e)}
```

So under the RC, a service serving the **default** shape — the fetching form, so
that a reader can curl the same URL — would have every reader's browser report
to a third party, with no attribute that turns it off. The embedded form can
disable it, which means the choice would no longer be the service's to make
freely: it would be *fetch the document and send telemetry* or *embed the
document and do not*.

### What this means for the two remaining entries

They were written as *"as `spec-0054-…`, against the RC"*, and 0054 has since
shipped, so they had already collapsed to changing one URL. They are now
something else again: a `type="module"` script, an `import`, and a decision
about telemetry that 2.5.4 does not force anybody to make.

**Cut by the maintainer, 2026-09-23.** Not for the reason this spec was written
on — the eleven months — but for one it could not have known: 0054 ships a page
that sends nothing to anybody, and the RC cannot do that in the form 0054
serves.

So this spec closes having bought exactly what its first entry was for. It cost
one page of rendering and no production code, and it answers *"why 2.x?"* with
a measurement rather than a preference — which is the thing a future reader,
looking at a stale-looking version number, would otherwise have to re-derive.

If 3.0 reaches GA the question reopens, and the one to ask first is whether the
element form can disable telemetry by then. Nothing else here has changed.

## Open questions

1. ~~**This one or 0054?**~~ **0054**, shipped in
   [#158](https://github.com/matthewjones372/pelican/pull/158) and
   [#159](https://github.com/matthewjones372/pelican/pull/159); this spec's
   remaining entries cut. See **Measured**.
2. **What happens at GA?** Still open, and reframed by what entry one found: the
   question to ask first is not whether the URL moved but whether the element
   form can disable telemetry by then. If it cannot, the answer stays no however
   settled 3.x is.
3. ~~**Is there a third option?**~~ **Moot.** Shipping neither was a floor to
   measure against while 0054 was a proposal. 0054 shipped, so the status quo
   this spec's **Problem** describes is gone either way.
