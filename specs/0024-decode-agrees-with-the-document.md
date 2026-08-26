# 0024 — Decode agrees with the document

## Problem

The schema-agreement harness checks only the encode direction
(`SchemaAgreementTest.kt:118-131`), and both decode bugs found in review hide
there. `defaultJson()` omits `explicitNulls = false`
(`KotlinxCodecs.kt:39-42`), so a kotlinx-backed server throws
`MissingFieldException` for an absent nullable-without-default field that the
published schema calls optional — and that Jackson and jsoniter accept. A
consumer following the document gets 400 from one codec and 200 from the
other two. Separately, `FormShape` cannot read OpenAPI 3.1 type arrays:
`type: ["integer","null"]` falls through `as? JsonStr` to `Kind.STRING`
(`Forms.kt:130,137-138`). Two silent schema lies ride along: simple-name
collisions alias schemas with no error (`DescriptorSchemas.kt:124-126`,
`ReflectionSchemas.kt:148-150`), and kotlinx schema derivation ignores every
`Json` setting except `classDiscriminator` (`KotlinxCodecs.kt:28-30`) — a
`SnakeCase` naming strategy writes snake_case while the schema publishes
camelCase. And `successNamedBy` skips the payload instance-check that
`failureNamedBy` performs (`Fallible.kt:82-102` vs `:124-129`).

## Not doing

- **No honouring of arbitrary `Json` settings in the schema.** Unsupported
  settings are refused loudly, not silently translated (question 2).
- **No format-assertion validation.** Turning on networknt format checks is
  worth a look but is its own argument; not here.
- **No discriminator change to the emitted document.** That carve-out stays
  with the standalone-schemas work that made it (see 0025 for the label).

## Shape

```kotlin
// kotlinx defaults gain the decode half of what the schema already says
internal fun defaultJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
```

The agreement harness gains a decode direction: for every cell of the matrix,
build the *schema-minimal* payload — required fields only — and every codec
must decode it. Name collisions become a refusal naming both classes.
Unsupported `Json` settings become a refusal at `KotlinxCodecs` construction.
`successNamedBy` instance-checks the Ok payload and fails as a named
`UndeclaredResponse` instead of an anonymous encode throw.

## Why this shape

The minimal-payload decode check is the exact inverse of the harness's
existing own-bytes-vs-own-schema check, so it slots into the same matrix and
catches this whole bug class, not just these two instances. Refusing loudly
on collisions and unsupported settings follows the repo's stated rule: a
constraint the schema omits is a lie in the contract, and refusing is the
only honest alternative to implementing translation.

## Stack

- [x] **`spec-0024-explicit-nulls`** — the `defaultJson()` change plus the
      decode direction in `SchemaAgreementTest` across all three codecs.
      Done when: the minimal-payload cell fails without the fix and passes with it.
- [x] **`spec-0024-form-type-arrays`** — `FormShape` reads 3.1 type arrays;
      a nullable scalar case lands in `FormBodyTest`.
      Done when: a nullable Int form field round-trips as an integer on all codecs.
- [x] **`spec-0024-loud-refusals`** — collision refusal in all three schema
      sources; unsupported-`Json`-settings refusal; `successNamedBy` payload
      check.
      Done when: each refusal has a test naming the message.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Is `explicitNulls = false` right for *encode* too (omit nulls rather than
   write them)? Recommend yes — it matches what Jackson and jsoniter already
   emit and shrinks payloads; pin wire parity in `ThreeCodecsTest`.
2. Which `Json` settings does the refusal allow? Recommend the allowlist the
   schema derivation actually understands today: `classDiscriminator`,
   `ignoreUnknownKeys`, `encodeDefaults`, `explicitNulls` — everything else
   refuses with the setting's name.
3. Collision refusal at schema-build time or first use? Recommend
   schema-build — it is the earliest moment both names are known.
