# 0001 — Self-contained JSON Schema from descriptions

## Problem

`SchemaSource` derives JSON Schema 2020-12, but what it emits is only valid
*inside* an OpenAPI document. Two things leak.

Refs are absolute to `#/components/schemas/`, and `pelican-jackson` hardcodes
that prefix when rewriting a hierarchy
([Unions.kt:287](../pelican-jackson/src/main/kotlin/io/github/matthewjones372/pelican/jackson/Unions.kt:287))
instead of asking the `SchemaComponents` it was handed, so a consumer with its
own registry gets dangling pointers on the very types that need them.

And a sealed hierarchy loses the property telling its branches apart. That
property belongs to no Kotlin type — all three codecs synthesise it when
encoding — so derivation has nothing to emit, and
[Unions.kt:133](../pelican-jackson/src/main/kotlin/io/github/matthewjones372/pelican/jackson/Unions.kt:133)
subtracts it from each branch and moves it to OpenAPI's `discriminator`, which
JSON Schema does not know. Standalone, `Card` validates `{"number": "…"}` and
the codec then refuses it for want of a `method`: a schema that passes a
validator and fails the decoder.

## Not doing

- No change to the emitted OpenAPI document. It is right as it stands, and
  three tests say so.
- No new derivation. This rewrites what the codec sources publish; nothing
  reflects over a type.
- Nothing MCP-shaped. Spec 0002, which depends on this.
- kotlinx's `PolymorphicKind.OPEN`: refused, not described — its subclasses
  register at runtime, so no closed schema is honest.

## Shape

```kotlin
val schemas = StandaloneSchemas(JacksonCodecs)     // pelican-schema, core-only

schemas.schema(typeOf<PaymentMethod>())
// { "$ref": "#/$defs/PaymentMethod",
//   "$defs": { "PaymentMethod": { "oneOf": [ {"$ref": "#/$defs/Card"}, … ] },
//              "Card": { "type": "object",
//                        "properties": { "method": {"const": "card"}, "number": … },
//                        "required": ["expiry", "method", "number"] } } }
```

Two passes. **Rebase**: every `$ref`, and every `discriminator.mapping` value,
from `#/components/schemas/` to `#/$defs/`; prefix-agnostic, so a correct ref
is untouched. **Stamp**: for each schema carrying `oneOf` and `discriminator`,
read `propertyName` and `mapping`, add `property: {"const": value}` to each
mapped branch's `properties` and `required`, drop the `discriminator`.

Read, never derived — the rule
[codegen/Unions.kt:38](../pelican-codegen/src/main/kotlin/io/github/matthewjones372/pelican/codegen/Unions.kt:38)
already follows, and what lets one pass cover three codecs that disagree:
jackson takes both from `@JsonTypeInfo`/`@JsonSubTypes`, kotlinx from
`@JsonClassDiscriminator`/`@SerialName`, jsoniter always `type` and the class's
simple name.

## Why this shape

A core-only module beside `pelican-openapi`, not inside it: wanting a schema
should not mean acquiring a document generator, and the cross-codec tests need
all three codecs test-scoped in one place. The alternative is forty lines
inside `pelican-mcp` — no new module, and the defect stays invisible to
anything else wanting a standalone schema. Recommended: the module, because
the bug is in the derivation rather than in MCP.

## Stack

- [x] **`spec-0001-schema-module`** — `pelican-schema`; `StandaloneSchemas`, its `SchemaComponents`, the rebase pass.
      Done when: every `$ref` in a derived schema resolves inside the returned document, for all three codecs. Landed in [#26](https://github.com/matthewjones372/pelican/pull/26).
- [x] **`spec-0001-schema-unions`** — the stamp pass; refusals for `OPEN` hierarchies and for a branch two hierarchies select differently.
      Done when: a payload written to satisfy a branch schema decodes through that module's codec, asserted for all three, and each refusal names what to do instead. Landed in [#27](https://github.com/matthewjones372/pelican/pull/27).
- [x] **`spec-0001-schema-docs`** — `docs/schemas.md`, a `docs/modules.md` row and count, a `docs/reference.md` section.
      Done when: the page shows one union before and after, says why the document keeps the old spelling, tabulates the three codecs' spellings, and lists what is refused. Landed in [#29](https://github.com/matthewjones372/pelican/pull/29).

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Module, or forty lines inside `pelican-mcp`? Recommend the module — the
   defect is in derivation, and one consumer today says nothing about tomorrow.
2. Root as `$ref` plus `$defs`, or the root inlined with `$defs` beside it?
   Recommend `$ref`: one code path for named and anonymous types.
3. `{"const": "card"}` or `{"enum": ["card"]}`? Recommend `const` — 2020-12's
   own spelling, and it reads better to a model.
      Landed in [#26](https://github.com/matthewjones372/pelican/pull/26).
      Landed in [#27](https://github.com/matthewjones372/pelican/pull/27).
      Landed in [#29](https://github.com/matthewjones372/pelican/pull/29).
