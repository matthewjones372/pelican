# 0044 — What the surface does not need

## Problem

Spec 0010 ends with an instruction rather than a plan: its two `explicitApi`
entries were abandoned once the warning pass measured 423 mechanical `public`
keywords across core's top eight files, and its note says to rewrite them
against the `.api` dump the third entry landed.

At 1.0 every published declaration becomes a promise kept until a major
release. Nobody has read the dump to ask which of them should have been
`internal` — the freeze is coming, and the answer is currently "all of them,
by omission".

Read, the dump is smaller than the headline: 704 declarations once accessors
and `copy`/`component` are set aside, 320 of them in `pelican-core`. Cross-
referencing each name against every other module, `example/`, the tests, the
README and `docs/` leaves 71 that nothing outside their own module mentions —
and most of those are explainable:

| Reason | Example | Narrowable |
|---|---|---|
| Kotlin file facades | `EndpointKt`, `FilterKt` | no — an artifact of top-level functions |
| `@PublishedApi internal` | `EndpointBuilder.addError` | no — reached by `inline reified` callers |
| Enum machinery | `valueOf` | no |
| DSL builders used as receivers | `DocsBuilder`, `McpOptionsBuilder` | no — a name search cannot see them |
| The `spi` package | `Frames.readFrom` | no — deliberately public, `SpiPackageTest` |

What is left is six declarations, and they are three different problems.

## Not doing

- **No `explicitApi()`, warning or strict.** 0010's note stands: hundreds of
  mechanical keywords is not a reviewable change and buys nothing the dump
  does not already give.
- **No renames or deprecations**, and nothing removed that any doc, test or
  example names.
- **No `@RequiresOptIn` markers.** Still a later question.

## Shape

Each of the six gets a decision, and the decision is one of two things: made
`internal` because it is machinery, or documented and tested because it is a
feature. Nothing stays public and unmentioned.

**Machinery, to be `internal` — `pelican-codegen`:**
`asWritten`, `isWritable`, `isIdentifier`, `branchName`. Name-mangling helpers
in `Names.kt` that the generator calls; no test, no doc, and no reason for a
caller to reach them. Making them `internal` removes four promises.

**A feature nobody documents — `pelican-openapi`:** `ApiSpec.changesFrom`
returns the list of `ApiChange`s between a spec and a published document. That
is the breaking-change check a service wants in CI, and it is reachable, tested
nowhere outside its own module, and named in no page. It gets a section in
`docs/reference.md` and a test, not an `internal`.

**A gap in a family — `pelican-core`:** `badRequest` sits beside
`unauthorized`, `forbidden` and `notFound`, which the cookbook and the examples
all use. It alone is unmentioned. Narrowing it would leave the family
inconsistent; it gets the line in the refusals table it should always have had.

## Why this shape

The alternative is an audit that proposes narrowing everything unreferenced,
which the table above shows would be wrong five times out of six. Asking what
each unreferenced declaration *is* costs an afternoon and produces six honest
decisions rather than seventy mechanical ones.

## Stack

- [x] **`spec-0044-codegen-internal`** — the four `Names.kt` helpers to
      `internal`; `pelican-codegen`'s `.api` dump regenerated.
      Done when: `./gradlew build` is green and the dump is four lines shorter.
- [ ] **`spec-0044-document-the-rest`** — `changesFrom` gets a reference
      section and a test; `badRequest` joins the refusals table.
      Done when: no declaration named in this spec is both public and
      unmentioned by any page.


## Measured

Entry one, on `c03c13b`. The dump went 98 lines to 94, and the four removed are
the four named:

```
-	public static final fun asWritten (Ljava/lang/String;)Ljava/lang/String;
-	public static final fun branchName (Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;
-	public static final fun isIdentifier (Ljava/lang/String;)Z
-	public static final fun isWritable (Ljava/lang/String;)Z
```

All four are called inside the module, so they are machinery rather than dead
code: `asWritten` and `isWritable` from `KotlinTypes.kt`, `isIdentifier` from
both `KotlinTypes.kt` and `asWritten` itself, `branchName` from `Unions.kt`.

**Confirming that took more than a `grep`.** `KotlinTypes.kt` holds a literal
NUL byte in a string on line 217 — `"$context\u0000${obj.render()}"`, a
separator no type name can contain — so `grep` treats the file as binary and
prints `Binary file matches` instead of the lines. A first pass therefore
reported `asWritten` and `isWritable` as used nowhere, which would have made
them look like dead code rather than machinery. `grep -a` shows the truth.

Worth knowing beyond this entry: any search over these sources silently skips
that file unless it is forced to treat it as text. `Report.kt` and its test
contain ESC bytes for terminal colour and read as binary for the same reason.

## Acceptance

```bash
./gradlew build
./gradlew apiCheck
```

## Open questions

1. **Is `changesFrom` meant to be public?** It reads like the CI check
   `pelican-test-golden` is for, arrived at from the other direction.
   Recommend keeping it and documenting it: the golden files pin a document,
   this compares two, and a service wants both.
2. **`Cors.allowedRequestHeaders`** is public, unreferenced, and takes a list
   of endpoints — plausibly for a backend rather than a service. Recommend
   leaving it and asking again when a second backend returns, since
   `multi-backend`'s interpreters are the callers that would say.
3. **Does this spec supersede 0010's two entries, or reopen them?** Recommend
   superseding: the dump answered the question `explicitApi` was going to ask,
   and 0010's own note says to rewrite rather than resume.
