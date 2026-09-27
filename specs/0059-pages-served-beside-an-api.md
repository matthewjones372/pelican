# 0059 — Pages served beside an API

## Problem

A service built on Pelican often has a few pages of its own next to its endpoints: a small UI, a status page, a
demo. Pelican serves endpoints and its own documentation page, and nothing else. lark-bank's customer and ops pages
(its specs 0006 and 0007) are HTML, JavaScript and CSS from the classpath. To serve them, the bank drops to
`Api.start(system, port, host) { route }` and concatenates Pekko's `getFromResourceDirectory` with
`routeWithDocs`. That works, but the files then sit outside everything Pelican does for a route: CORS, the metrics
filter, and the document, which cannot say they exist.

## Not doing

- **No templating.** The files are served as they are.
- **No single-page-app fallback** that answers `index.html` for any unknown path. A service that wants one writes
  it as a route.
- **No caching policy beyond content type and `ETag`.** No `Cache-Control` tuning.
- **Nothing in the OpenAPI document.** Pages are not endpoints.

## Shape

```kotlin
val api = api(endpoints = …, codecs = JacksonCodecs, pages = pages("ui"))   // classpath directory ui/

api.startWithDocs(system, port = 8080, docs = docs { docsPath = "/api-docs" })
// GET /                 → ui/index.html
// GET /ops              → ui/ops.html, or ui/ops/index.html
// GET /bank.js          → ui/bank.js, as text/javascript
// GET /accounts/42      → the endpoint, as today: an endpoint's path always wins over a page
```

- **`pages(resourceDirectory, at = "/")`** is a value on `Api`, next to `cors` and `filters`. Each backend
  interprets it; `pelican-pekko` with Pekko's own resource directives.
- **An endpoint wins over a page** at the same path, so adding a page never hides an API route.
- **`/x` answers `x.html` or `x/index.html`**, so a page's links need no extension.
- **The content type comes from the extension:** html, js, css, svg, json, png, ico and woff2.
- **The API's CORS and filters apply to pages too,** so the metrics filter counts them under their path.

## Why this shape

A value on `Api` keeps what a service serves in one description, and lets every backend serve it. The
alternative is a Pekko-only helper, `pagesRoute(dir)`, which the service concatenates itself. That is half of
today's workaround with a name on it, and it leaves pages outside the filters. Recommended: the value on `Api`.

## Stack

- [ ] **`spec-0059-pages`**: `pages(...)` on `Api`, served by `pelican-pekko`, and the endpoint-first rule.
      Done when: a test serves `index.html`, a nested `x/index.html` and a script with their content types, an
      endpoint at a page's path answers as the endpoint, and a missing file is a 404.
- [ ] **`spec-0059-filters`**: CORS and filters over pages, and a reference section.
      Done when: the metrics filter counts a page, and `docs/reference.md` says where pages come from.

## Acceptance

```bash
./gradlew build
./gradlew :pelican-pekko:test --tests '*PagesTest*'
```

## Open questions

1. **A value on `Api`, or a start parameter like `handlers`?** Recommended: on `Api`. What a service serves
   belongs with its endpoints; how it runs them is a start setting.
2. **Should `/x` try `x.html` too, or only exact files and `index.html`?** Recommended: both, so a page can be one
   file.
3. **Should pages be listed in the document as a tag, so a reader knows they exist?** Recommended: no, as Not
   doing says. The document describes the API.
