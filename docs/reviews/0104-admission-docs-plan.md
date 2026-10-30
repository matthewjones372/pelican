# Approved admission documentation implementation plan

Approved by independent root technical review under delegated programme authority, 30 October 2026,
as the existing spec 0104 documentation entry. Commit this plan before recipe/fixture/source edits.

Author on `codex/i01-admission-docs`, stacked on independently reviewed Jetty PR 439 head
`b89dceecc7c8e8b1b303f45b667e43867cd905da` while its CI runs. Root explicitly authorizes this isolated
authoring distinction; it does not waive integration gates. Jetty 439 must pass exact-head required checks,
merge through fresh coordinated integration, and pass required exact-main checks before this documentation
entry can integrate. Then normally refresh/retarget the documentation branch and run current-base checks.
Preserve the coordinated Doorlist main-recovery priority; hold no unrelated control while waiting.

## Exact bounded changes

- `docs/cookbook.md`: one small explicitly unreleased core/native-Jetty admission recipe, complete explicit
  imports and actual selected-endpoint identity, Continue/Refuse, all-values-header guidance and supported/
  unsupported construction limits. It does not invent an authority policy. Narrowly exempt this recipe from
  the cookbook's generic Pekko-binder parity sentence and released 1.2.0 availability; retain other recipes'
  parity/availability claims. Public APIs only, no new runtime or dependency.
- `example/src/test/kotlin/example/compiler/FrozenCallSites.kt`: compile that same recipe as a new explicit-
  import source fixture through existing `StillCompilesTest`, which batches fixtures in one compiler run.
  No new test framework, compiler dependency, duplicate backend fixture or generated frozen-consumer change.
- `CHANGELOG.md`: a concise Unreleased entry linking the recipe/reference and naming construction limits.
- `docs/reference.md`: cross-link the recipe; preserve the reviewed native/custom/docs/MCP and telemetry bounds.

Expected total is roughly 100-150 changed lines including this plan; stay near the approved 200-line guideline.
No runtime/API/spec/backend implementation changes, version/pin/installer, workflow or release work.

## Proof and closure

Compile the real recipe through existing `StillCompilesTest`; run unchanged frozen binary consumers and the
existing AllBackendsTest, StrictDecodingTest, StartParityTest, ServerShapeParityTest and
AdmissionConfigurationTest suites for absent-hook/native and unsupported-construction proof. Run separate
formatting then the full build on stable source, independent exact-source review, exact-head/current-base CI,
and guarded serialized merge followed by terminal required exact-main success. No check is weakened.

Label unreleased availability explicitly: published 1.2.0 does not provide admission; do not invent a released
version or assume local Maven installation. Preserve every old JVM/default-mask entry and frozen JAR hash.
Keep I01 incomplete: Doorlist custom startup/adoption/pins require separate reviewed work, explicit Refuse
still needs O02/I01 final-response telemetry, and the separately measured core streaming-input ownership gap
remains open. No stable release or complete refused-request accounting is claimed.
