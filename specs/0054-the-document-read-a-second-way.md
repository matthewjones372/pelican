# 0054 — The document, read a second way

## Problem

`pelican-pekko-docs` serves exactly one UI. The page is Swagger UI 5, written
into a Kotlin string template in `pelican-openapi/.../SwaggerUi.kt:54-86`, with
no way to choose another renderer and no theming surface at all: the `<title>`
is `"$title — API reference"` and the whole stylesheet is `body { margin: 0; }`.

Swagger UI is a console, built around *Try it out* — one operation expanded at a
time, the request form as the centrepiece. A team publishing a reference for
people who are reading rather than poking has the wrong tool, and what they do
today is write the page themselves from the generated document. That works, and
it means the claim this library rests on — page and document from one
description — stops holding for the page.

## Not doing

- **No vendor extensions.** `x-logo` and `x-tagGroups` are Redoc's signature
  features and the emitter has no `x-*` surface at all. Its own spec.
- **No theming**, no CSS hook, no custom head, no webfont. The Redoc page gets
  the `title` the Swagger UI page gets and nothing more.
- **Swagger UI stays the default.** This adds a choice; it does not move one.
- **No second path.** One `docsPath`, one page, chosen — not `/docs` and
  `/redoc` side by side.
- **No vendored assets and no WebJar.** See **Why this shape**.

## Shape

```kotlin
val docs = docs {
    ui = DocsUi.Redoc                 // unset: DocsUi.SwaggerUi, as today
    openApiPath = "/openapi.json"
    docsPath = "/api-docs"
}
```

and beside `swaggerUiHtml`, in `pelican-openapi`:

```kotlin
fun redocHtml(title: String, specPath: String, spec: String): String
```

Nothing else about the route changes: same path, same content type, same CORS
headers, same choice between `url:` and an inlined document when `openApiPath`
is null.

## Why this shape

**A CDN link, like the page beside it.** Swagger UI already arrives from
`unpkg.com/swagger-ui-dist@5`, so Redoc from a CDN adds no new kind of thing —
and tapir, the closest peer library, does the same: its `tapir-redoc_3` jar is
21 KB and writes a `cdn.jsdelivr.net/npm/redoc@…/bundles/redoc.standalone.js`
tag into a template. The alternative is a WebJar, which sits at 2.5.0 against
npm's 2.5.4 and would be the first browser-asset payload in a published Pelican
jar, at 1.05 MB.

**`ui` on the existing builder, not a second function.** `docsRoutes` already
derives three routes from one `Docs`; a `redocRoutes()` beside it would
duplicate the path, CORS and inlining logic for a choice of renderer.

**A refusal, not a silent no-op, for OAuth.** Redoc has no *Try it out*, so
`docsOAuth` configures nothing and `oauth2-redirect.html` has no caller.
Pairing the two should fail at build time naming both, the way
`handlers = "ktor"` is refused rather than ignored.

## Stack

- [x] **`spec-0054-redoc-html`** — `redocHtml`, `DocsUi` and the `ui` knob, the
      OAuth refusal, the `.api` dump; tests in `pelican-openapi`.
      Done when: `redocHtml` passes the `</script>` escape test `swaggerUiHtml`
      passes, and `Redoc` with `docsOAuth` fails naming both. **Both hold.**
- [x] **`spec-0054-redoc-route`** — `docsRoutes` serves the chosen renderer;
      `example` asserts it end to end; the `modules.md` and `reference.md` rows
      say there are two.
      Done when: `ui = Redoc` serves a Redoc page at `docsPath` and the same
      document at `openApiPath`, with the Swagger UI tests untouched. **Both
      hold, over a socket.**

## Acceptance

```bash
./gradlew build
curl -s http://127.0.0.1:8080/api-docs | grep redoc.standalone.js
```

No test can assert that a page renders, so the second entry ends with someone
opening it.

## Measured

**Open question 2 is answered by rendering, not by grepping.** Both forms were
served to headless Chromium against `example`'s own document, with the 2.5.4
bundle served locally so the CDN was not the variable:

| page | rendered | `h1` | sections | page errors |
|---|---|---|---|---|
| `<redoc spec-url="/openapi.json">` | yes | `Orders (1.0.0)` | 31 | none |
| `Redoc.init(spec, {}, el)` | yes | `Orders (1.0.0)` | 31 | none |

So the embedded form works and `openApiPath = null` keeps its meaning under
either renderer. The draft's recommendation — render it before writing the form
down — was worth taking: the first attempt rendered *neither* page, because the
browser in that environment cannot reach a CDN, which is a failure mode a
grep of the bundle would never have shown and which a service behind a proxy
will hit for real. It is the same exposure the Swagger UI page already has, and
open question 1 is where it is recorded.

**The refusal paid for itself in the route.** `docsRoutes` picks the renderer
in a `when` and needs no second check for OAuth: because `docs { }` refuses
`Redoc` an `oauth` at all, `redirectPath` is already null on that branch. A
refusal at construction turned into one less branch at the point of use.

**Two escapes rather than one.** `swaggerUiHtml` writes everything into a
script, so `</` → `<\/` covers it. Redoc's fetching form writes the path into
an *attribute*, which that escape does not protect, so the path is escaped as
HTML and the embedded document as JavaScript. Both are tested, and the script
escape was checked by removing it and watching the test fail.

## Open questions

1. **How tightly pinned?** Today's URL is `swagger-ui-dist@5` — a major only, so
   the page silently follows upstream. Recommend `redoc@2` for consistency,
   while recording that consistency is the whole argument and an exact pin is
   the safer one. Pinning both exactly is a change to the Swagger UI page and so
   not this spec.
2. ~~**Does the inlined document work?**~~ **Answered: yes**, by rendering both
   forms in a browser. See **Measured**.
3. **Enum or sealed type for `DocsUi`?** Recommend an enum. It becomes a sealed
   type the day a renderer needs options of its own, source-compatibly.
4. **Is 2.x the right line?** [Spec 0055](0055-the-renderer-that-has-not-shipped.md)
   is this same change against `3.0.0-rc.0`, written to be read beside this one.
   They are alternatives, not a sequence. Recommend this one.
