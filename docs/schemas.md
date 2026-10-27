# A schema that resolves on its own

Linked from the [README](../README.md). A derived JSON Schema handed to
something that does not hold your OpenAPI document (a validator, a registry, a
tool description a model reads), with every pointer resolving inside the
object it came back in.

---

## What it is for

A `SchemaSource` (every `Codecs`, such as `JacksonCodecs`, is one) describes a
type as JSON Schema 2020-12, the dialect OpenAPI 3.1 embeds. What it publishes
is a *fragment* of a document: pointers are absolute to
`#/components/schemas/`, and a sealed hierarchy leaves the property that tells
its branches apart in OpenAPI's `discriminator`, a keyword JSON Schema does not
have.

Inside the document both are right. Anywhere else, the first is a dangling
reference, and the second is a branch schema a validator accepts and the codec
that described it then refuses.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:pelican-schema:1.2.0")
    implementation("io.github.matthewjones372:pelican-jackson:1.2.0")
}
```

`pelican-schema` depends on `pelican-core` only; the codec module is the
schema source. It sits beside `pelican-openapi` rather than inside it, so one
type described does not mean a document generator on the classpath.
[`pelican-mcp`](mcp.md) uses it for every tool's `inputSchema` and
`outputSchema`.

```kotlin
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.schema.StandaloneSchemas
import kotlin.reflect.typeOf

val schema: JsonObj = StandaloneSchemas(JacksonCodecs).schema(typeOf<PaymentMethod>())
println(schema.render())
```

`StandaloneSchemas(source: SchemaSource)` has one function,
`schema(type: KType): JsonObj`. It asks the source for the type's schema with a
component registry of its own, then:

1. **Collects** every schema the source registered under `$defs`, keyed by the
   name the source gave it (for `JacksonCodecs`, the class's simple name).
2. **Rewrites every pointer** to `#/$defs/<name>`, keeping only the last
   segment of the original pointer. That includes the values of a
   `discriminator` mapping, which are bare strings rather than `$ref` objects.
   A mapping value with no `/` in it is a schema name, not a pointer, and is
   left alone.
3. **Pins each union branch** to the value that selects it, and drops
   `discriminator` (below).
4. **Refuses** what no closed schema can describe (below).

The root is whatever the source returned for the type: a `$ref` for a class or
sealed hierarchy, `{"type": "array", "items": {"$ref": ...}}` for a `List`, and
an inline schema with no `$defs` at all for a type that registers no
components, such as `String`. The OpenAPI document is not touched: it is built
by `pelican-openapi` from the same source, with its own registry.

## One union, before and after

The example service's `PaymentMethod` (`example/src/main/kotlin/example/Model.kt`),
three shapes told apart by `method`:

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "method")
@JsonSubTypes(
    JsonSubTypes.Type(value = Card::class, name = "card"),
    JsonSubTypes.Type(value = BankTransfer::class, name = "bank_transfer"),
    JsonSubTypes.Type(value = Invoice::class, name = "invoice"),
)
sealed interface PaymentMethod

data class Card(val number: String, val expiry: String) : PaymentMethod
data class BankTransfer(val iban: String) : PaymentMethod
data class Invoice(val reference: String) : PaymentMethod
```

In the OpenAPI document
([golden](../example/src/test/resources/golden/openapi.json)), abbreviated:

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

As `StandaloneSchemas` hands it over, here as the `$defs` of the `payOrder`
tool's `inputSchema` in the
[MCP golden](../example/src/test/resources/golden/mcp-tools.json):

```json
{ "PaymentMethod": {
    "oneOf": [ {"$ref": "#/$defs/Card"}, {"$ref": "#/$defs/BankTransfer"}, {"$ref": "#/$defs/Invoice"} ] },
  "Card": {
    "type": "object",
    "properties": { "number": {"type": "string"},
                    "expiry": {"type": "string"},
                    "method": {"const": "card"} },
    "required": ["expiry", "number", "method"] },
  "BankTransfer": {
    "type": "object",
    "properties": { "iban": {"type": "string"}, "method": {"const": "bank_transfer"} },
    "required": ["iban", "method"] },
  "Invoice": {
    "type": "object",
    "properties": { "reference": {"type": "string"}, "method": {"const": "invoice"} },
    "required": ["reference", "method"] } }
```

Every pointer is rebased onto `$defs`. For each entry of a discriminator
mapping, the branch it points at gets the discriminator property as a
`const` of the mapped value, added to `required` (moved to the end if it was
already there). After that the `discriminator` says nothing the branches do
not, so it is removed from every schema in `$defs`. `{"const": "card"}` is
2020-12's own spelling, rather than `{"enum": ["card"]}`.

The property and the value are read from the source's `discriminator`, never
derived. With `JacksonCodecs` they are `@JsonTypeInfo(property = ...)` and
`@JsonSubTypes.Type(name = ...)`, as above. `pelican-kotlinx` writes the
same `oneOf` and `discriminator`, from
`@JsonClassDiscriminator` or its `Json`'s class discriminator and each
branch's serial name, so the same pass covers it.

The tests in `core/pelican-schema` check the result with a validator that did
not write it (`networknt` `json-schema-validator`, 2020-12): every pointer
resolves inside the returned object, a payload built from a branch schema alone
decodes through the codec that described it, and the codec's own output
validates against the schema for records, nullable references, lists, sealed
branches, a union inside a record, a recursive type, a value class, a map of
nullable lists, enums and a list of unions.

### Tagged failures

Two failures sharing a status with `.tagged(...)` become a `oneOf` with a
`discriminator` in the *response* of the OpenAPI document, built by
`pelican-openapi`. They are not a type, so `StandaloneSchemas` never sees
them; in a [tool](mcp.md), a declared failure is text and only the success has
an `outputSchema`.

## Nullability and optional properties

`StandaloneSchemas` does not change either; it passes on what the source
wrote. `JacksonCodecs` writes them the 3.1 way:

- a nullable property with a `type` gets `"null"` added to it:
  `{"type": ["string", "null"]}`;
- a nullable property that is only a `$ref` becomes
  `{"anyOf": [{"$ref": "#/$defs/Owner"}, {"type": "null"}]}`;
- a nullable element of a `List` or `Map` is widened the same way, read from
  the Kotlin type because the Java type has erased it;
- a property that is nullable or has a default is left out of `required`
  (which is why `quantity` is optional in `CreateOrder` in the
  [MCP example](mcp.md#one-endpoint-end-to-end)), and the default mapper
  omits a null property when it writes one.

## What is refused

Each refusal is an `IllegalArgumentException` thrown by `schema(...)`, naming
what to write instead.

| Refused | Why |
|---|---|
| a class that is a branch of two hierarchies which select it differently, in one schema (say a `Both` picked as `kind: both` by one and `kind: elsewhere` by another) | It would need both selectors, and a payload carrying both is one neither codec writes. Give the hierarchies the same property and value, or give each a type of its own. |
| an open hierarchy, one whose subclasses register at run time | `{"type": "object"}` is all a closed schema could say. Make the hierarchy `sealed`, or describe the property as one branch. |

The open-hierarchy check reads the schema, not the codec: it fires on a schema
whose `description` begins `polymorphic: `, which is the note `pelican-kotlinx`
writes for open polymorphism. `JacksonCodecs` reads a hierarchy's branches
from `@JsonSubTypes`, sealed or not, so it never writes that note.

The source can refuse too, before this pass runs. `JacksonCodecs` names a
component after the class's simple name, so two different classes with one
simple name in one schema (a `Catalogue.Item` and a `Basket.Item` in one
`Order`) are refused, naming both.

`JacksonCodecs` (the companion object) is one instance for the process, and
concurrent `schema()` calls through it share resolver state. Derive schemas
once at startup, as `mcpDispatch` and the `-docs` modules do, or construct a
`JacksonCodecs` per thread.
