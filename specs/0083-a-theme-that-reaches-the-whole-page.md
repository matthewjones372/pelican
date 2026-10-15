# 0083: A theme that reaches the whole page

## Problem

0082's acceptance was that the petshop's `/reference` "looks like the shop".
It does not. Under Scalar the theme sets seven variables, and most of what a
reader sees reads others: the Send button is black (`--scalar-button-1`),
secondary text and links are grey (`--scalar-color-2`, `-3`), panels, borders
and the active sidebar item are neutral grey, the highlight tint is blue, and
`GET` is Scalar's blue. The page is Scalar's, with a cream sidebar.

Three parts of the petshop's old Redoc look have no slot at all: per-method
colours (sage `GET`, terracotta `POST`), the dark code panel, and 800-weight
headings.

## Not doing

- **A logo under Scalar.** Still refused (0082).
- **A dark palette.** Themed pages stay light (0082).
- **Spacing, radii, shadows, sizes.** Raw `options` or `css`.
- **Changing what 0082's slots mean.** They reach more of the page; none moves.

## Shape

```kotlin
theme {
    accent = "#C2562B"; text = "#3B2A20"; sidebar = "#FBF6EF"
    success = "#5E8C61"; error = "#9A3A1A"
    font("Nunito, sans-serif"); codeFont("'JetBrains Mono', monospace")
    methods { get = "#5E8C61"; post = "#C2562B" }   // new: put, patch, delete too
    codePanel = "#3B2A20"                            // new
    headingWeight = 800                              // new
}
```

- **Derived under Scalar, no new API.** From `accent`: `--scalar-button-1`,
  its hover (mixed 15% toward black), `--scalar-link-color` and hover,
  `--scalar-background-accent` (12% accent), `--scalar-sidebar-color-active`,
  `--scalar-sidebar-item-active-background` (14% accent over the sidebar). From
  `text`: `--scalar-color-2` and `-3` (78% and 58% over white) and
  `--scalar-sidebar-color-2`. From `sidebar`: `--scalar-background-2`, and
  `--scalar-background-3` and `--scalar-border-color` (12% and 20% text over
  it). Mixed with CSS `color-mix`, so any colour form a slot takes still works.
  Redoc derives these itself and gets nothing new here.
- **`methods { }`:** Redoc `theme.colors.http.<method>`; Scalar
  `--scalar-color-blue` (get), `-green` (post), `-orange` (put), `-yellow`
  (patch), `-red` (delete).
- **`codePanel`:** Redoc `rightPanel.backgroundColor`; Scalar
  `.examples .scalar-card` and `.examples .scalar-code-block` backgrounds.
- **`headingWeight`:** Redoc `typography.headings.fontWeight`; Scalar
  `font-weight` on `h1` to `h3` under `.light-mode, .dark-mode`.
- Every derived or new value sits in the theme's block, before the service's
  `css`, so `css` still wins, as 0082 promised.

## Why this shape

Deriving from slots a service already set fixes 0082's broken promise without
asking anyone to name `--scalar-sidebar-item-active-background`: the brand has
an accent, a text colour and a surface, and the rest of a palette is mixes of
them. The alternative, a slot per Scalar variable, is Scalar's format again.
Recommended: derive.

The three new slots are the ones a brand visibly owns and no mix can produce.

## Stack

- [ ] **`spec-0083-palette`**: the derivations, under Scalar only.
      Done when: each derived variable is in the theme's CSS, and the petshop's
      page, opened by hand, has terracotta buttons and warm panels.
- [ ] **`spec-0083-slots`**: `methods { }`, `codePanel`, `headingWeight`, on
      both renderers.
      Done when: each lands where its renderer reads it, and a blank one is
      refused where it is written.
- [ ] **`spec-0083-docs`**: the reference guide shows the new slots and the
      derivations.
      Done when: `docs/reference.md` shows the block above.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

Then petshop #77 sets the three new slots, and its `/reference`, opened at
desktop width, has sage `GET`, terracotta `POST` and buttons, a bark code
panel and heavy headings. Checked by eye, with a screenshot in the PR.

## Open questions

- **Scalar shares method and status colours.** `POST` and success are both
  `--scalar-color-green`, `DELETE` and error both `-red`. Recommended: a method
  colour wins, documented; the alternative scopes method colours to Scalar's
  `.endpoint-method` and `.request-method` classes, which are internal and
  likelier to move.
- **`codePanel` reaches Scalar through its class names.** `scalar-card` and
  `scalar-code-block` are prefixed like Scalar's public hooks but not
  documented. Recommended: use them, with a test that fails if the selector
  text changes, so a Scalar bump that moves them is noticed by someone looking.
- **Button text on the accent.** White on the accent is assumed. Recommended:
  white; a `buttonText` slot when an accent too light for it turns up.
- **`headingWeight` as `Int` or `String`?** Recommended: `Int`, refused
  outside 100 to 900.
