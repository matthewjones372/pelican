# 0009 — Bytes that satisfy the schema they published

## Problem

`CodecFactory` and `SchemaSource` are separate interfaces on purpose: a document
has to be generatable with no server on the classpath. Nothing checks that the
two agree.

What exists proves neighbouring things. `OpenApiSpecQualityTest` reads the
emitted document with swagger-parser, which says the document is well-formed.
`ThreeCodecsTest` asserts Jackson, kotlinx and jsoniter write byte-identical
payloads — for one record, one union and one partial body. Neither asks the
question the architecture rests on: **does a body this server produced satisfy
the schema this server published?**

The stakes are not documentation-only. `FormShape.of` reads the published schema
to decide whether a form field is a string, an integer or an array, so a schema
that disagrees with its codec is a wrong decode and a 400, not a wrong document.
Spec 0001 found one such disagreement by reading — a sealed branch whose schema
validates a payload its own codec then refuses — and its first entry has since
landed as `pelican-schema`, which found a second: every pointer a
Jackson-described hierarchy produced was dangling. Two defects in one area,
both found by reading, is the argument for checking the rest by running it.

## Not doing

- No property-based generation. A fixed matrix is reviewable; a generator's
  shrink output is not, and a flaky schema test would be worse than none.
- No production code. If the matrix finds a defect, the defect gets its own
  spec and the row lands `@Disabled` with that spec's number in the message.
- Not the request direction beyond what the matrix covers. Decoding is already
  exercised by `AllBackendsTest` through every backend.

## Shape

One matrix, three codecs, three claims per row:

```kotlin
@ParameterizedTest @MethodSource("shapes")
fun `each codec's own bytes satisfy its own schema`(shape: Shape) { … }

private val shapes = listOf(
    shape<Page<Item>>(Page(listOf(item), null, 1)),          // nested generic
    shape<Item>(Item(1, "a", listOf("x", null), null)),      // List<String?>, nullable $ref
    shape<Map<String, List<Long?>>>(mapOf("a" to listOf(1L, null))),
    shape<Node>(Node("root", listOf(Node("leaf", emptyList())))),   // recursive
    shape<Wrapped>(Wrapped(UserId(7L))),                     // value class
    shape<Envelope>(Envelope(item, Draft("ada"), mapOf("k" to "v"))),  // sealed
    …                                                        // ~15 rows
)
```

- each codec's encoded bytes validate against that codec's own schema
- the three schemas agree structurally, or the row records why they cannot
- each codec decodes the other two's bytes back to an equal value

## Why this shape

The alternative is removing the disagreement by construction — deriving the
schema from the codec's behaviour, or the codec from the schema. Both move the
disagreement rather than removing it, and both cost a rewrite of three modules.
Not recommended. The split is right; what it needs is evidence, and evidence is
one test.

Validation uses `com.networknt:json-schema-validator` against 2020-12, which is
the dialect `SchemaSource` already emits — a validator that did not write the
schema, on the same principle as swagger-parser reading the document.

## Stack

- [x] **`spec-0009-schema-agreement-harness`** — the validator dependency, the `Shape` fixture, the three claims, and the rows already covered by `ThreeCodecsTest`.
      Done when: a deliberately broken schema in a fake `SchemaSource` turns the suite red, and `pelican-schema`'s dependency test still says no server library. Landed in [#54](https://github.com/matthewjones372/pelican/pull/54).
- [x] **`spec-0009-schema-agreement-matrix`** — the hard rows: nested generics, recursion, value classes, `Map<String, List<T?>>`, a Java record, an enum with a renamed constant, a sealed type nested in a sealed type.
      Done when: every row is green or `@Disabled` naming the spec that will fix it, and `docs/reference.md` says which shapes the three libraries describe identically. Landed in [#55](https://github.com/matthewjones372/pelican/pull/55).

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Which module? `pelican-schema` — it is core-only, it already test-scopes
   all three codec modules for exactly this reason, and its build file says so.
   The remaining question is whether a JSON Schema validator belongs on that
   module's test classpath or on `example`'s. Recommend `pelican-schema`: the
   claim is about schemas, and `example` is where a *service* is asserted.
2. Structural schema agreement across three libraries — is it achievable, or
   should the claim be weaker? Recommend asserting agreement on `type`,
   `required` and property names, and recording spelling differences rather than
   failing on them.
3. Java types: worth a row, given `pelican-jsoniter` has `JavaTypes.kt`?
   Recommend one `record` and one `java.time` field, no more.
4. Which schemas does this validate against — the OpenAPI-shaped ones a
   `SchemaSource` emits, or `StandaloneSchemas`' rebased ones? Recommend the
   standalone ones: they resolve without a document, which is what a validator
   needs, and it puts 0001's second entry under test as a side effect.
