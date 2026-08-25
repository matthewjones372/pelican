# A schema that resolves on its own

Linked from the [README](../README.md). A derived JSON Schema handed to
something that does not hold your OpenAPI document — a validator, a registry, a
tool description a model reads — with every pointer resolving inside the object
it came back in.

---

## What it is for

`SchemaSource` describes a type as JSON Schema 2020-12, which is what OpenAPI
3.1 embeds. What it publishes is a *fragment* of a document: pointers are
absolute to `#/components/schemas/`, and a sealed hierarchy leaves the property
that tells its branches apart in OpenAPI's `discriminator`, which JSON Schema
has no such keyword for.

Inside the document both are right. Anywhere else, the first is a dangling
reference and the second is worse: a branch schema a validator accepts and the
codec that described it then refuses.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:pelican-schema:0.1.0")
}
```

Core only. It sits beside `pelican-openapi` rather than inside it: wanting one
type described should not mean acquiring a document generator.

```kotlin
val schemas = StandaloneSchemas(JacksonCodecs)

schemas.schema(typeOf<PaymentMethod>())
```

## One union, before and after

The example service's `PaymentMethod` — three shapes told apart by `method` —
as the document spells it:

```json
{ "PaymentMethod": {
    "oneOf": [ {"$ref": "#/components/schemas/Card"}, … ],
    "discriminator": {
      "propertyName": "method",
      "mapping": { "card": "#/components/schemas/Card", … } } },
  "Card": {
    "type": "object",
    "properties": { "number": {"type": "string"}, "expiry": {"type": "string"} },
    "required": ["expiry", "number"] } }
```

and as `StandaloneSchemas` hands it over:

```json
{ "$ref": "#/$defs/PaymentMethod",
  "$defs": {
    "PaymentMethod": { "oneOf": [ {"$ref": "#/$defs/Card"}, … ] },
    "Card": {
      "type": "object",
      "properties": { "number": {"type": "string"},
                      "expiry": {"type": "string"},
                      "method": {"const": "card"} },
      "required": ["expiry", "number", "method"] },
    "BankTransfer": { …, "method": {"const": "bank_transfer"} },
    "Invoice": { …, "method": {"const": "invoice"} } } }
```

Two changes. Every pointer is rebased onto `$defs`, including the ones inside a
`discriminator` mapping, which are bare strings rather than `$ref` objects and
which a naive walk carries straight past. And the property each branch is
picked by is written onto that branch as a `const`, required — after which the
`discriminator` says nothing the branches do not, and is dropped.

`{"const": "card"}` rather than `{"enum": ["card"]}`: 2020-12's own spelling,
and the one a model reads better.

## Why the document keeps the old spelling

`#/components/schemas/` is where the schemas in an OpenAPI document actually
are, and `discriminator` is how 3.1 says which branch is which — tooling reads
it, and the generated client and the importer both depend on it. Rewriting the
document would break all of that to fix a problem the document does not have.

So this is a pass over what a codec source publishes, run when somebody asks
for a standalone schema, and the emitted document is untouched — the committed
golden, the round-trip import and the independent parser check would each
notice if it were not.

## What each codec spells it as

The property and value are *read*, never derived — which is what lets one pass
cover three sources that agree on almost nothing else:

| Codecs | Property | Value per branch |
|---|---|---|
| `JacksonCodecs` | `@JsonTypeInfo(property = …)` | `@JsonSubTypes.Type(name = …)` |
| `KotlinxCodecs` | `@JsonClassDiscriminator`, else the configured `Json`'s, which defaults to `type` | `@SerialName`, else the qualified class name |
| `JsoniterCodecs` | always `type` | the class's simple name |

A payload written to satisfy a branch schema decodes through the codec that
described it, and the test asserts that for all three by building the payload
from the schema alone: a property the schema forgets is a property the payload
lacks.

## What is refused

Both refusals throw at the point the schema is asked for, naming what to write
instead.

| Refused | Why |
|---|---|
| kotlinx.serialization's open polymorphism — an `abstract class` or interface that is `@Serializable` but not `sealed` | Its subclasses register at run time, so `{"type": "object"}` is all a closed schema could honestly say. A document a human reads beside the code can live with that; a validator or a model acting on one cannot. Make the hierarchy `sealed`, or describe the property as one branch. |
| a class that is a branch of two hierarchies which pick it differently | It would need both properties, and a payload carrying both is one neither codec writes. Give the hierarchies the same property and value, or give each a type of its own. |

The open-polymorphism refusal reads the note the descriptor source writes into
the schema: this module holds no codec, and what it is handed is the schema.
