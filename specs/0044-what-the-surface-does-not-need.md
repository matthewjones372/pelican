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
- [x] **`spec-0044-document-the-rest`** — `changesFrom` gets a reference
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

### Entry two, as built

The spec said `changesFrom` was "tested nowhere outside its own module". It was
tested nowhere at all: `grep -a` over every `.kt`, `.md` and `.api` in the tree
finds it in exactly two places, its own declaration and the dump. A public
function with no caller, no test and no page.

**The test that matters is the one about argument order.** `apiChanges` takes
two `JsonObj`s, and `changesFrom` exists to name which is which at the call
site. Get that backwards and nothing errors — every verdict silently inverts. A
response field you deleted reads as a new one, `BREAKING` becomes `COMPATIBLE`,
and a compatibility check passes while the callers do not.

So two tests: one asserting `changesFrom` reports exactly what the two-document
form does, and one asserting the receiver is the *proposed* side, by deleting a
response field and requiring that to be breaking. Both were checked against the
mistake rather than assumed to cover it — flipping the implementation to
`apiChanges(openApi(), published)` turns them red, the second with the sentence
that says why:

```
the receiver is the proposed side, so a field it dropped reads as the loss it is() FAILED
    org.opentest4j.AssertionFailedError: expected the dropped field to be breaking, got []
```

Empty, not wrong — read backwards, a lost field is a gained one and a gained one
breaks nobody. That is the failure mode the function's existence is an argument
against, and now something catches it.

**`badRequest` needed a table that did not exist.** The spec said it should
"join the refusals table"; there is none. `docs/reference.md` had a
throwable-to-response table whose first row read ``` `ApiException` (`notFound`,
`forbidden`, …) ``` and left the rest to the ellipsis. Counting mentions across
`docs/`, `README.md`, `example/` and every test:

| helper | docs | README | example | tests |
|---|---|---|---|---|
| `badRequest` | 0 | 0 | 0 | 0 |
| `conflict` | 2 | 0 | 0 | 5 |
| `notFound` | 4 | 0 | 2 | 17 |
| `unauthorized` | 6 | 2 | 12 | 10 |
| `forbidden` | 3 | 1 | 6 | 11 |
| `tooManyRequests` | 1 | 1 | 2 | 0 |

`badRequest` is the only zero across the board, which is what the spec found —
but `conflict` and `tooManyRequests` are thin for the same reason, so the fix is
the table, not a sentence about one function. Six rows, each with its status and
the header it carries where it carries one.

It also documents the distinction a reader needs and could not have got
anywhere: **there are two roads to a 400.** A parameter failing its declared
constraint is a `DecodeFailure`, raised before a handler runs and naming what it
had to satisfy. `badRequest` is for what only the handler knows — a date range
that ends before it starts, an id that parses but belongs to somebody else.
Nothing in a description can catch either, which is why one is a throw.

## Acceptance

```bash
./gradlew build
./gradlew apiCheck
```

## Open questions

1. ~~**Is `changesFrom` meant to be public?**~~ **Answered: yes, kept and
   documented.** The reference now says which of the three routes to the same
   classification to take by where you want the failure — the Gradle task for
   the build, the goldens for a suite, these functions for anything narrower.
2. **`Cors.allowedRequestHeaders`** is public, unreferenced, and takes a list
   of endpoints — plausibly for a backend rather than a service. Recommend
   leaving it and asking again when a second backend returns, since
   `multi-backend`'s interpreters are the callers that would say.
3. ~~**Does this spec supersede 0010's two entries, or reopen them?**~~
   **Superseded**, as recommended, and
   [0010](0010-checking-the-published-surface.md)'s two entries are struck
   pointing here. The dump answered what `explicitApi` was going to ask, so
   resuming a 2026-07 plan against a surface it no longer describes would have
   been the more expensive way to learn the same thing.
