# 0081: One theme for either reference

## Problem

A reference page's look is its renderer's own format: Redoc's `options` JSON
(0071), or Scalar's config and CSS variables (0080). The petshop's Redoc look
is about forty lines of nested `jsonObj { put(...) }` for six colours, two
fonts and a logo. None of it carries to Scalar, so moving `/reference` from
Redoc to Scalar means writing the same look again in a second format.

Scalar's format also has a trap: its variables live on `.light-mode` and
`.dark-mode`, so `:root { --scalar-color-accent: … }` is silently ignored.

## Not doing

- **Removing raw options or css.** They stay, for anything the theme does not
  name, and they win over it.
- **Theming Swagger UI.** Same reason as 0071: it has no options object.
- **Per-method colours, spacing, radii.** Raw options, until a second service asks.
- **A dark palette.** See Open questions.

## Shape

```kotlin
docs {
    docsPath = "/api-docs"
    scalarReference("/reference") {          // or reference("/reference") { … }: the same block
        theme {
            accent = "#C2562B"
            text = "#3B2A20"
            sidebar = "#FBF6EF"
            success = "#5E8C61"
            error = "#9A3A1A"
            font("Nunito, sans-serif", stylesheet = "https://fonts.googleapis.com/css2?family=Nunito&display=swap")
            codeFont("'JetBrains Mono', monospace")
            logo(PAW, altText = "Petshop")
        }
    }
}
```

- `ReferenceTheme` in `pelican-openapi`, built by `theme { }` inside
  `RedocLookBuilder` and `ScalarLookBuilder`. Colours are CSS colour strings,
  unchecked beyond not blank.
- **Redoc:** `accent` → `theme.colors.primary.main`, `text` →
  `colors.text.primary` and `sidebar.textColor`, `sidebar` →
  `sidebar.backgroundColor`, `success`/`error` → `colors.success.main` /
  `colors.responses.error.color`, fonts → `typography.fontFamily`,
  `headings.fontFamily`, `code.fontFamily`, `logo` → `x-logo` as 0071 does.
  Merged under `options` key by key, so a raw `theme.colors.primary` still wins.
- **Scalar:** a `.light-mode, .dark-mode { --scalar-color-accent; --scalar-color-1;
  --scalar-sidebar-background-1; --scalar-color-green; --scalar-color-red;
  --scalar-font; --scalar-font-code }` block placed before `css`, and
  `forceDarkModeState: "light"` with `hideDarkModeToggle: true` under `options`.
- `font(..., stylesheet = url)` adds the stylesheet as `stylesheet(url)` does.
- Refused where it is declared: `logo` in a theme under `scalarReference`, since
  Scalar has no logo option.

## Why this shape

A theme that belongs to neither renderer is what lets a service change renderer
without rewriting its look, which is the move 0080 made possible. It is a
handful of named slots rather than either renderer's full surface: the slots
are what a brand has (a colour, a text colour, a surface, two fonts, a mark),
and everything past them stays the renderer's own format. The alternative, a
typed `RedocTheme` and a typed `ScalarTheme`, covers more of each and still
makes the move a rewrite. Recommended: one neutral theme.

Light only under Scalar because Redoc is light only (0071), and a palette
picked for cream does not survive Scalar's dark background.

## Stack

- [ ] **`spec-0081-theme`**: `ReferenceTheme`, its builder, `theme { }` on
      both look builders, and the Redoc mapping with its key-by-key merge.
      Done when: a themed Redoc page carries each slot where Redoc reads it, and
      a raw `options` key beside it wins.
- [ ] **`spec-0081-scalar`**: the Scalar mapping, light forced, the logo refusal.
      Done when: the variables land on `.light-mode, .dark-mode` before `css`,
      and a browser shows the accent (checked by hand, as 0054 did).
- [ ] **`spec-0081-docs`**: the reference guide and the example use `theme { }`.
      Done when: `docs/reference.md` shows the block above.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

Then the petshop's `/reference` is `scalarReference { theme { … } }` with no
`jsonObj`, and looks like the shop.

## Open questions

- **Light only, or a `dark { }` palette too?** Recommended: light only now;
  `dark { }` can come when someone asks for it.
- **Logo under Scalar: refuse, or put it in the document's description?**
  Recommended: refuse. An image in the description is the document's, which
  0071 kept `x-logo` out of.
- **Should `theme { }` sit on `docs { }` once, shared by both pages?**
  Recommended: no; one reference page per service (0080), so per page is enough.
- **Typed colours (`Color("#C2562B")`)?** Recommended: plain strings; CSS
  accepts more forms than a type would.
