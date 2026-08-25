# 0015 — Coverage, where its absence means something

## Problem

Line coverage is 89.6% against a floor of 80, and the floor's own comment says
what it is for: "a ratchet against regression, not a target to code towards — a
test written to move a percentage is worth less than no test at all." So the
number is not the problem. What the number is hiding is.

Three findings from `build/reports/kover/report.xml`, in order of what their
absence costs:

- **`Security.kt` is 37.8%** — 46 of 74 lines. That file is nothing but public
  builders: `oauth2Password`, `oauth2Implicit`, `openIdConnect`, `httpAuth`, the
  bare-name scope overloads. They are published API, they appear in the
  reference manual, and most of them are never called by anything in this
  repository. A caller is the first to run them.
- **`openapi/Comparison` is 78.8%** — 48 of 226. That class decides whether a
  change is `BREAKING`, `COMPATIBLE` or `COSMETIC`, which is the answer a
  reviewer acts on. An unexercised branch there does not fail loudly; it
  misfiles a breaking change as compatible.
- **`pelican-test-http4k` is 0%.** Sixteen lines, published, nothing runs them.

And one thing inflating the denominator rather than hiding anything: 119 missed
lines are `main` functions and demo runners — `example/MainKt`,
`example/backends/MainKt`, `ReadmeExampleKt`, `FilterExampleKt`. Excluding them
puts the same code at 90.8%.

## Not doing

- **No test written to move the number.** If a line is uncovered and covering it
  would assert nothing, it is excluded with a reason or left alone.
- Not the importer, at 78–93% across seven classes. It is the largest gap by
  line count and the least load-bearing per line; a spec of its own if ever.
- No raise of the floor beyond where the work below actually lands it. A floor
  above the number is a broken build, and a floor far below it measures nothing.
- No coverage gate per module. The aggregate is deliberate: a line in core is
  exercised by a backend's tests, and per-module numbers would report that as a
  gap.

## Shape

Three pieces of work and one bookkeeping change, in that order:

```kotlin
// build.gradle.kts — the denominator says what it means
excludes {
    classes("example.generated.*")
    // A `main` that starts a server and prints a URL. Running it asserts
    // nothing, and mocking it would assert less.
    classes("example.*.MainKt", "example.MainKt", "example.readme.*", "example.filters.*")
}
```

Then tests for the three findings, then the floor moved to whatever the result
supports, rounded down to a whole number.

## Why this shape

The exclusions go first and are the least interesting part, so they are worth
being explicit about: they raise the percentage by 1.2 points and cover nothing.
Doing them first means the tests that follow are measured against an honest
denominator rather than credited with someone else's arithmetic.

`Security.kt` is first among the tests because it is the widest gap between
"published and documented" and "ever executed". The alternative reading — that
builders returning data classes are too dull to test — is exactly the reasoning
that let `oauth2Implicit` ship with nothing calling it, and the scope-name
overloads take a `List<String>` and build a `Map`, which is a place to be wrong.

## Stack

- [x] **`spec-0015-honest-denominator`** — Kover excludes for demo runners and `main`s, each with the reason beside it.
      Done when: `./gradlew koverXmlReport` counts no `main`, and the excluded list names why rather than what. Landed in [#70](https://github.com/matthewjones372/pelican/pull/70).
- [x] **`spec-0015-security-builders`** — every builder in `Security.kt` constructed and asserted through `securitySchemesOf` and the emitted document.
      Done when: each of the four flows, both scope spellings, and all three `apiKey` locations appear in a document that `OpenApiSpecQualityTest`'s parser accepts. Landed in [#71](https://github.com/matthewjones372/pelican/pull/71).
- [x] **`spec-0015-comparison-branches`** — the `Comparison` branches nothing reaches: a removed response, a narrowed schema, a parameter that becomes required, a `default` appearing.
      Done when: each classifies as the spec's table says, and a `COSMETIC` change to a description never reads as `BREAKING`. Landed in [#74](https://github.com/matthewjones372/pelican/pull/74).
- [x] **`spec-0015-in-memory-http4k`** — `pelican-test-http4k`'s transport, exercised as `pelican-test-pekko`'s already is.
      Done when: the module has a test, and the two in-memory transports answer one suite alike. Landed in [#72](https://github.com/matthewjones372/pelican/pull/72).
- [x] **`spec-0015-raise-the-floor`** — the floor moved to the whole number below where the work above lands.
      Done when: `koverVerify` fails if the number drops, and the comment still says it is a ratchet. Landed in [#75](https://github.com/matthewjones372/pelican/pull/75).

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Is excluding `main` functions honest, or is it the arithmetic this spec
   claims to avoid? Recommend excluding: a `main` that binds a port and prints a
   URL has no assertable behaviour, and `example.generated.*` is already
   excluded on the same reasoning.
2. `example/codecs/ThreeCodecsKt` is 52.8% and is both a demo and the fixture
   `ThreeCodecsTest` drives. Split it, exclude it, or leave it? Recommend
   leaving it: the uncovered half is the `main`, and splitting a file to move a
   number is the thing this spec is against.
3. Where should the floor land? Recommend one whole number below the result, so
   it ratchets without breaking on a rounding change.
4. Should `pelican-test-http4k` be covered by `AllBackendsTest` instead of a
   test of its own? Recommend its own: spec 0012 is already moving that suite,
   and two specs editing one file is how a stack stops being reviewable.
