# 0010 — Checking the published surface

## Problem

Three claims about what this library publishes are unchecked.

**Visibility is inferred.** There is no `explicitApi()`. The discipline is there
by hand — `@PublishedApi internal constructor` throughout, `internal` on
`chosenSuccess`, `encodeDeclaredHeaders`, `DerivedPlainCodec` — but it is habit,
not a gate, and a new public declaration is a commitment made by omission. Nor
are public return types explicit, so an inferred type can become an accidental
part of the surface.

**The ABI is not pinned.** A change that alters the binary surface — a
parameter's name, a default's presence, a widened return type — shows up in
somebody's `NoSuchMethodError` rather than in a diff. This is the argument the
golden files already won for the HTTP surface, applied to the Kotlin one.

**The refusals list names shipped modules.** `docs/reference.md` § "What isn't
here" still carries "A Ktor client transport" and "A Pekko client transport",
each explaining that the other two exist. Both are in `settings.gradle.kts`,
both are rows in `docs/modules.md`, and both have dependency tests. For a
library whose pitch is that documentation cannot drift, this is the worst place
in the repository for stale text.

## Not doing

- No 1.0, and no promise of one.
- No renames, no deprecations, and no narrowing of anything currently public.
  The `.api` dump is a photograph first; arguing with what it shows is a later
  spec.
- No `@RequiresOptIn` markers for the experimental parts. Also later, and
  better decided with the dump in hand.

## Shape

```kotlin
// build.gradle.kts, subprojects block
extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(21)
    explicitApi()
}
```

```
pelican-core/api/pelican-core.api        # checked in, diffed in review
```

and a test in the same family as `NoThirdPartyDependenciesTest`:

```kotlin
@Test fun `what isn't here names no published module`() { … }
```

reading the section's list items against `settings.gradle.kts`.

## Why this shape

`binary-compatibility-validator` is the golden-file idea for the ABI: the check
is a diff of a file a human reads, not a rule a tool invents. This project has
already accepted that argument once.

`explicitApi()` strict is the destination, and it may be a large first diff
across 24 modules. The alternative is `explicitApiWarning()` everywhere, then
strict module by module. Recommended, because the warning pass is where the
real edits happen and it can be split by module without a red build in between.

## Stack

- [ ] **`spec-0010-explicit-api-warning`** — `explicitApiWarning()` in the subprojects block; the visibility and return-type edits in `pelican-core` only.
      Done when: core builds warning-free under the flag, and every declaration made `internal` is one no example and no test used.
- [ ] **`spec-0010-explicit-api-strict`** — the remaining modules, then `explicitApi()` strict.
      Done when: `./gradlew build` is green with strict mode on, and each module's newly `internal` declarations are listed in the PR body.
- [x] **`spec-0010-abi-dump`** — `binary-compatibility-validator`, checked-in `.api` files, wired into `check`; the two stale refusals deleted and the test that stops them coming back.
      Done when: adding a public function to core fails `apiCheck` until the dump is regenerated, and the refusals test fails if a "What isn't here" bullet names a module in `settings.gradle.kts`. Landed in [#69](https://github.com/matthewjones372/pelican/pull/69).

> **The two `explicitApi` entries are deliberately not done, and the order
> changed.** Turning `explicitApiWarning()` on measured 99 warnings in
> `Inputs.kt`, 87 in `Endpoint.kt` and 423 across core's top eight files, every
> one a mechanical `public` keyword — not a reviewable change even split per
> module. The dump landed first instead ([#69](https://github.com/matthewjones372/pelican/pull/69)),
> which gets what this spec wanted — know what you publish, notice when it
> changes — with no source churn, and makes `explicitApi` an informed decision:
> the 1,429 declarations it pins are the list to read before deciding what
> should have been `internal`. Rewrite these two against that dump.

## Acceptance

```bash
./gradlew build
./gradlew apiCheck
```

## Open questions

1. Does the second entry run past 200 lines? Recommend splitting it per module
   group — codecs, backends, clients, tooling — and saying so in review rather
   than discovering it mid-branch.
2. Are the generated sources in `example` and the Gradle plugin exempt?
   Recommend exempting both: `example` is not published, and the plugin is an
   included build with its own `check`.
3. Should the refusals test parse the markdown, or is that too clever?
   Recommend a plain read of list-item text between the two headings, and a
   failure message quoting the offending line.
4. Which declarations should the dump make us reconsider first? Recommend
   `Params.asMap`, `Webhook.operation` and `Retry`'s policy knobs — noted here
   so the reading of the dump has somewhere to start, not as work in this spec.
