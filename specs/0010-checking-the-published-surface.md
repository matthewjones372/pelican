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

- [ ] ~~**`spec-0010-explicit-api-warning`**~~ — superseded by 0044; see Closing. — `explicitApiWarning()` in the subprojects block; the visibility and return-type edits in `pelican-core` only.
      Done when: core builds warning-free under the flag, and every declaration made `internal` is one no example and no test used.
- [ ] ~~**`spec-0010-explicit-api-strict`**~~ — superseded by 0044; see Closing. — the remaining modules, then `explicitApi()` strict.
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
> should have been `internal`. Rewrite these two against that dump —
> done in [spec 0044](0044-what-the-surface-does-not-need.md), which reads
> it and supersedes both entries.

## Closing

The note above already said what to do: rewrite the two `explicitApi` entries
against the dump. [Spec 0044](0044-what-the-surface-does-not-need.md) is that
rewrite, and it is now complete — both its entries landed, and it declined
`explicitApi()` in either strength for the reason measured here. So this spec
closes on its third entry, with the other two superseded rather than done.

What 0044 bought instead of 423 mechanical `public` keywords: 704 declarations
read, 71 unreferenced outside their own module, and six given a decision each —
four made `internal`, one documented, one added to a table.

### Open question 4, checked

That question named three declarations as where a reading of the dump should
start. Two are settled by the dump itself; the third is not, and is the one
thing this spec leaves behind.

| named | mentions on a page | verdict |
|---|---|---|
| `Webhook.operation` | docs 10, README 2, example 12 | documented and used; nothing to reconsider |
| `Retry`'s policy knobs | `retryPolicy` docs 3, example 7; each knob in docs and example | same |
| **`Params.asMap`** | **none anywhere** | **still open** |

`asMap` is public, named on no page, and has exactly one caller in the tree —
`lensInputs` in `Tuples.kt`, inside its own module. **A first count said six
callers in tests, and all six were OpenTelemetry's unrelated
`Attributes.asMap()` in `TelemetryTest.kt`**; the name collides, and a search
for it has to read the receiver.

That is the `Names.kt` shape 0044 narrowed — machinery, public by omission. What
makes it different is its own KDoc:

> The whole bag, for interpreters that need to walk it rather than read known
> keys — a client turning inputs back into a request, for one.

It claims an audience. No interpreter on main represents that audience, and
none on the `multi-backend` branch calls it either — checked across
`pelican-ktor`, `pelican-http4k` and all three shelved client transports. So the
declaration is an extension point with no extant user, which is either the point
of it or the argument against it.

**Not decided here.** This spec's **Not doing** forbids narrowing anything
public, and open question 4 says these three are noted *"not as work in this
spec"*. It is the same shape as 0044's own open question about
`Cors.allowedRequestHeaders`: recommend leaving it public and asking again when
a second backend returns, since the interpreter that would call it is exactly
what is missing.

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
4. ~~Which declarations should the dump make us reconsider first?~~ **Checked:
   two of the three were fine, one is still open.** `Webhook.operation` and the
   `Retry` knobs are documented and used; `Params.asMap` is named on no page and
   has one in-module caller. See **Closing** for why it is left public anyway.
