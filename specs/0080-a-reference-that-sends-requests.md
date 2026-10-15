# 0080: A reference that sends requests

## Problem

Spec 0071 gave a service a themed reference beside the console, and that
reference is Redoc's. Redoc reads; it sends nothing. A reader on the page that
looks like the product has to leave it for the Swagger console to try a call,
and that console cannot be dressed (0071, Not doing).

The petshop hit this: its themed `/reference` cannot call the shop, and its
`/api-docs` can but looks like nobody's. Scalar is a three-panel reference with
a request client built in, MIT, themed by CSS variables and a config object.

## Not doing

- **Replacing Redoc.** `reference(path) { }` and `DocsUi.Redoc` stay as they are.
- **Scalar as the page at `docsPath`.** No `DocsUi.Scalar`; see Open questions.
- **OAuth.** `docsOAuth` stays Swagger's. Scalar's `authentication` key can go
  through `options` untouched; Pelican maps nothing onto it.
- **A typed theme.** Scalar's config and CSS variables pass through as they are,
  the same call 0071 made for Redoc.
- **Bundling.** CDN, as Swagger and Redoc are. The bundle is 4.6 MB.

## Shape

```kotlin
docs {
    docsPath = "/api-docs"                       // Swagger, as now
    scalarReference("/reference") {
        stylesheet("https://fonts.googleapis.com/css2?family=Nunito&display=swap")
        css(""":root { --scalar-color-accent: #C2562B; --scalar-font: Nunito, sans-serif; }""")
        options = jsonObj { "layout" to "modern"; "hideDarkModeToggle" to true }
    }
}
```

- `scalarHtml(title, specPath, spec, look)` in `pelican-openapi`, beside
  `redocHtml`. It calls `Scalar.createApiReference('#ui', config)`: `url` from
  `specPath` when there is one, `content` embedded when not.
- `ScalarLook` holds `options` (Scalar's config, a `JsonObj`), `css` (written
  into a `<style>` after Scalar's own) and `stylesheets` (`<link>`s).
- Pelican's config sits under the service's: `telemetry: false` and
  `proxyUrl: null`, so a request goes from the browser to the API and nowhere
  else. A service that wants either back sets it in `options`.
- `docsRoutes` (Pekko) and `docsHandler` (Jetty) serve it at its path, with the
  document's CORS setting, as they serve the Redoc reference.
- Refused in `docs { }`: both `reference` and `scalarReference` (one reference
  page, 0071), and a path equal to `docsPath` or `openApiPath`.

## Why this shape

A second function rather than a renderer argument on `reference`: the looks
differ in kind (Redoc has a theme object and `x-logo`, Scalar has CSS variables
and no logo option), so one builder would carry settings half its pages ignore,
which 0071 refused for `redoc { }`. The alternative, `reference(path,
ReferenceUi.Scalar) { }` with a sealed look, keeps one name and costs a type
per renderer. Recommended: `scalarReference`.

Telemetry and proxy off by default because the shop's requests are the shop's.
Scalar's own default proxy applies only to its hosted app today, but `null`
written down does not depend on that staying true.

## Stack

- [ ] **`spec-0080-scalar-page`**: `ScalarLook`, its builder, `scalarHtml`;
      tests in `pelican-openapi`.
      Done when: the page carries options, css and stylesheets, `telemetry` and
      `proxyUrl` default off and yield to `options`, and `</script>` in the
      document or css cannot end the script or style.
- [ ] **`spec-0080-scalar-route`**: `scalarReference` in `DocsBuilder`, the
      refusals, both backends serving it; `example` asserts it over a socket.
      Done when: one `docs { }` serves Swagger at `/api-docs` and Scalar at
      `/reference` on Pekko and Jetty, reading `/openapi.json`.
- [ ] **`spec-0080-docs`**: `docs/reference.md` and `docs/modules.md` say there
      are three renderers and which one sends requests.
      Done when: the block above is in the reference guide.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
curl -s http://127.0.0.1:8080/reference | grep api-reference@
```

Then the petshop serves `/reference` through `scalarReference` and a request
sent from the page reaches the shop. No test can assert that; someone clicks it.

## Open questions

- **Pin: `@1` or `@1.74.0`?** Scalar ships several times a week, so a major
  tag moves under the page often. Recommended: `@1`, matching Swagger's `@5`
  and Redoc's `@2`; the exact pin is still 0054's separate decision.
- **`DocsUi.Scalar` too?** It would let Scalar be the only page. Recommended:
  not now; a service can serve `scalarReference` and turn `docsPath` off.
- **A logo?** Scalar has no logo option; a service can put one in the
  document's description or in `css`. Recommended: none until someone asks.
- **Name: `scalarReference` or `console`?** Recommended: `scalarReference`,
  since `console` would describe Swagger's page as well.
