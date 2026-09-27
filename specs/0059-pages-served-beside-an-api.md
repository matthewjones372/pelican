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
val api = api(endpoints = …, codecs = JacksonCodecs) { pages = pages("ui") }  // classpath directory ui/

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

- [x] **`spec-0059-pages`**: `pages(...)` on `Api`, served by `pelican-pekko`, and the endpoint-first rule.
      Done when: a test serves `index.html`, a nested `x/index.html` and a script with their content types, an
      endpoint at a page's path answers as the endpoint, and a missing file is a 404.
- [x] **`spec-0059-filters`**: CORS and filters over pages, and a reference section.
      Done when: the metrics filter counts a page, and `docs/reference.md` says where pages come from.

## Acceptance

```bash
./gradlew build
./gradlew :pelican-pekko:test --tests '*PagesTest*'
```

## Open questions

Answered 2026-09-27, taking each recommendation:

1. **A value on `Api`, or a start parameter?** A value on `Api`, set in `api`'s block beside `cors` and `filters`,
   as every other `Api` setting is, rather than as a parameter of `api` itself.
2. **Should `/x` try `x.html` too?** Yes: `x`, then `x.html`, then `x/index.html`.
3. **Should pages be listed in the document?** No.

Settled while building:

- **A directory reached without its slash is redirected to it** (301 to `/x/`), so the page's relative links
  resolve inside the directory rather than beside it.
- **Filters do not run for pages.** A filter wraps a handler and reads its endpoint's `Params`; a file has neither,
  and giving it a made-up endpoint would put a path in the metrics filter's `path` tag that no endpoint declares.
  Pages carry the API's CORS headers, which need no endpoint. So `spec-0059-filters` shipped CORS and the
  reference section, and not the "metrics filter counts a page" in its "Done when".
- **Types and caching are Pekko's resource directive's**, by extension, with `Last-Modified` and conditional
  requests.
