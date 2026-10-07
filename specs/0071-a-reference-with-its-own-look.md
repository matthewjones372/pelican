# 0071: A reference page beside the console, with its own look

## Problem

`docs { }` serves one page: `ui = DocsUi.SwaggerUi` or `DocsUi.Redoc`, at one
`docsPath`. Swagger's "Try it out" and Redoc's three-panel reference answer
different readers, and a service cannot offer both.

The Redoc page cannot be dressed either. `redocHtml` calls
`Redoc.init(document, {}, …)`: no theme, no logo, no fonts. Redoc reads its
colours, typography and panels from that options object, and its logo from the
document's `info.x-logo`, so a service that wants its reference to look like
its own product has to stop using Pelican's page and write one.

The petshop wanted exactly this: Swagger at `/api-docs` as it is, and a Redoc
reference at `/reference` in the shop's colours with a paw-print logo. The only
way today is a hand-written HTML resource and a Pekko route around
`routeWithDocs`.

## Not doing

- Theming Swagger UI. Its CSS is not an options object, and a console is
  rarely what a reader sees first.
- Bundling Redoc or fonts. Both stay on their CDNs, as Swagger's assets do.
- Adding `x-logo` to the published `/openapi.json`. A logo is the page's, and
  every client of the document would otherwise receive it.
- A second document. Both pages read the same one.

## Shape

```kotlin
docs {
    docsPath = "/api-docs"                 // Swagger, as now
    reference("/reference") {              // Redoc, beside it
        logo(url = "/paw.svg", altText = "Petshop", backgroundColor = "#FDF1E2")
        stylesheet("https://fonts.googleapis.com/css2?family=Fredoka:wght@600&family=Nunito&display=swap")
        options = jsonObj {
            put("theme", jsonObj {
                put("colors", jsonObj { put("primary", jsonObj { "main" to "#D9822B" }) })
                put("typography", jsonObj { "fontFamily" to "Nunito, sans-serif" })
            })
            "expandResponses" to "200"
        }
    }
}
```

- `reference(path) { }` adds a Redoc page at `path` beside whichever page
  `ui` serves at `docsPath`. It reads the same document, from `openApiPath`
  when there is one and embedded when there is not, as `redocHtml` already
  does.
- `options` is passed to `Redoc.init` as it is. Redoc's options are many and
  version-specific, so they are Redoc's own JSON rather than a Pelican type
  that would fall behind; a typed theme can wrap this later.
- `logo(…)` sets `info.x-logo` on the page's copy of the document only. The
  page then embeds that copy, so `/openapi.json` is unchanged.
- `stylesheet(url)` adds a `<link>` to the page's head, for the fonts a theme
  names.
- `docs { ui = DocsUi.Redoc }` keeps working, and takes the same `reference`
  settings for its one page through `redoc { }`.
- Refused where it is declared: a `reference` path equal to `docsPath` or
  `openApiPath`, and an `options` that is not a JSON object.

## Why this shape

Raw options rather than a typed theme: Redoc's theme has dozens of nested keys
and they change between majors, so a Pelican type would either cover a corner
of it or chase it. Passing Redoc's own JSON through is honest about whose
format it is, and costs one `jsonObj { }`. The alternative, a `RedocTheme`
data class with the handful of keys a service usually sets, is friendlier and
can sit on top of this later. Recommended: raw options now.

A second page rather than a second `Docs`: one document, one CORS policy, one
set of routes. Two `docs { }` would mean two documents to keep in step.

## Stack

- [ ] **`spec-0071-reference`**: `reference(path) { }` in `DocsBuilder`,
      `redocHtml` taking options, logo and stylesheets, and `docsRoutes`
      serving the extra page; the refusals.
      Done when: one `docs { }` serves Swagger at `/api-docs` and Redoc at
      `/reference`, the Redoc page carries the options, logo and stylesheet it
      was given, and `/openapi.json` has no `x-logo`.
- [ ] **`spec-0071-docs`**: the reference guide and the example service show a
      themed reference beside Swagger.
      Done when: the example serves both, and `docs/reference.md` shows the
      block above.

## Acceptance

```bash
./gradlew :pelican-openapi:test :pelican-pekko-docs:test :example:test
```

Then the petshop serves its reference through `docs { reference("/reference") { … } }`
and drops its hand-written page and route.

## Open questions

- **Name: `reference` or `redoc`?** `reference` says what the reader gets,
  `redoc` says which renderer. Recommended: `reference`, since the renderer
  could change and the page's purpose would not.
- **A logo as a URL, or the image itself?** A URL needs somewhere to serve the
  image from, which a service may not have. Recommended: a URL, which also
  takes a `data:` URI for a small SVG.
- **More than one reference page?** Recommended: one; a second is a second
  document's worth of decisions.
