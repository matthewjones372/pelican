# 0084: A reference that sends requests, and asks for nothing

## Problem

0080 chose Scalar for a reference whose page sends requests. Scalar's page
also sells: "Ask AI", "Generate SDKs & MCP", Deploy, Share and Configure all
lead to scalar.com and an account. A service's readers should be able to call
its endpoints, not be asked to sign up somewhere. Turning each extra off is a
race against Scalar's next release, since the extras are its business.

0083 (#316 to #319, closed unmerged) tried to make Scalar look like the
petshop; its CSS was Scalar's, so it goes with Scalar.

## Not doing

- **Keeping Scalar beside it.** `scalarReference` is removed, not deprecated:
  no tagged release has carried it (the latest is `v1.0.0-RC3`).
- **Changing Redoc's `reference`, Swagger UI, or the meaning of `theme { }`.**
- **0083's extra slots** (method colours, code panel, heading weight). See
  Open questions.

## Shape

```kotlin
docs {
    docsPath = "/api-docs"
    rapiDocReference("/reference") {
        theme {
            accent = "#C2562B"; text = "#3B2A20"; sidebar = "#FBF6EF"
            success = "#5E8C61"; error = "#9A3A1A"
            font("Nunito, sans-serif", stylesheet = "https://fonts.googleapis.com/css2?family=Nunito&display=swap")
            codeFont("'JetBrains Mono', monospace")
            logo(PAW, altText = "Petshop")
        }
        attributes("render-style" to "focused")    // RapiDoc's own, as they are, over Pelican's
    }
}
```

- `rapiDocHtml(title, specPath, spec, look)` in `pelican-openapi`: one
  `<rapi-doc>` element from `rapidoc@9.3.8`, exact. Its attributes are the
  page's whole configuration, so `RapiDocLook` holds `attributes`,
  `stylesheets` and the theme.
- Pelican's attributes, under the service's: `allow-try="true"`,
  `render-style="read"`, `theme="light"`, `show-header="false"`, and
  `allow-spec-url-load` / `allow-spec-file-load` `"false"`, so the page reads
  this API's document and no other. `spec-url` from `specPath`, or the
  document passed in with `loadSpec` when there is none.
- `theme { }`: `accent` → `primary-color` and `nav-accent-color`; `text` →
  `text-color` and `nav-text-color`; `sidebar` → `nav-bg-color`; `font` →
  `regular-font`, with `load-fonts="false"` so RapiDoc fetches no fonts of its
  own; `codeFont` → `mono-font`; `logo` → `<img slot="nav-logo">`, so the logo
  works again; `success` / `error` → RapiDoc's `--green` / `--red` on the
  element's `style`.
- Refused where it is written: a theme colour under `rapiDocReference` that is
  not `#rgb` or `#rrggbb`, because RapiDoc computes its shades from the hex in
  JavaScript and drops anything else silently.
- `scalarReference`, `ScalarLook`, `scalarHtml` and their tests go.
  `Docs.referencePage` serves Redoc or RapiDoc; both together are refused, as
  Redoc and Scalar were.

## Why this shape

RapiDoc is one MIT web component with a console built in and nothing behind
it: its bundle names no host but Google Fonts, which `load-fonts="false"`
turns off. 9.3.8 rather than 11: 9.3.8 has been unchanged for two years and is
configured by documented attributes; 11.0.0 is a rewrite from two weeks ago,
themed by CSS variables, after five releases this month. Pinned exactly, as
0054 recommends, because a reader's console breaking is worse than a missed
fix. The alternative, Scalar with its extras switched off, is a page that
keeps asking us to keep up with it. Recommended: RapiDoc 9.3.8.

## Stack

- [ ] **`spec-0084-page`** ([#321](https://github.com/matthewjones372/pelican/pull/321)): `RapiDocLook`, its builder, `rapiDocHtml`, the
      theme mapping and the hex refusal; tests in `pelican-openapi`.
      Done when: the page carries Pelican's attributes under the service's,
      each slot lands on its attribute, and `</script>` cannot break out.
- [ ] **`spec-0084-route`** ([#322](https://github.com/matthewjones372/pelican/pull/322)): `rapiDocReference` in `DocsBuilder`, served on
      Pekko and Jetty; Scalar removed; `example` asserts it over a socket.
      Done when: one `docs { }` serves Swagger and RapiDoc, and no `scalar`
      name is left in the API dump.
- [ ] **`spec-0084-docs`**: the reference guide says RapiDoc where it said
      Scalar.
      Done when: `docs/reference.md` shows the block above.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

Then petshop #77 uses `rapiDocReference`, and at desktop width its
`/reference` has the shop's colours, fonts and paw, sends `GET /pets` to the
shop from Try, and shows nothing that leads to an account.

## Open questions

- **9.3.8 or 11.x?** Recommended: 9.3.8, for the reasons above; move when 11
  has gone a few months without a breaking release.
- **`render-style`: `read`, `view` or `focused`?** `read` is one long page like
  Redoc's; `focused` shows one operation at a time. Recommended: `read`.
- **Bring 0083's slots back for RapiDoc?** 9.3.8 has `--nav-get-color` and the
  like for methods. Recommended: not in this spec; after the petshop has
  looked at the page.
- **Name: `rapiDocReference`, or a renderer-free `consoleReference`?**
  Recommended: `rapiDocReference`; `attributes(...)` takes RapiDoc's own
  names, and a call that hid the renderer would hide whose names those are.
