# 0066: Tagged failures, described in full

## Problem

Spec 0063 writes a tag into a failure's body and describes the status as a
`oneOf` with a discriminator. Two things in that document are not right yet.
The petshop's adoption shows both.

The tag field is not in any schema. The body says `"kind": "registry_down"`,
and the discriminator names `kind`, but `RegistryDown` and `NotRecorded`
declare only `id` and `message`. OpenAPI expects the discriminator's property
to be in every schema it maps to. Generators that check this refuse the
document, and ones that don't produce a type with no `kind` field to read.

The status's description is every description joined with `; `:
"The chip registry could not be reached; try again; The adoption could not be
recorded; try again". Each failure's own description is lost as a description
of that failure, and the joined text reads as one garbled sentence in Swagger.

## Not doing

- Changing what the body carries. The server already writes the tag; only the
  document changes.
- Tagged successes, or tags on anything but a JSON object (0063 refuses those).
- Untagged failures. A status with one failure renders as it does today.

## Shape

The petshop's `503` becomes:

```json
"503": {
  "description": "One of:\n\n- `registry_down`: The chip registry could not be reached; try again\n- `not_recorded`: The adoption could not be recorded; try again",
  "content": { "application/json": { "schema": {
    "oneOf": [
      { "$ref": "#/components/schemas/RegistryDown" },
      { "$ref": "#/components/schemas/NotRecorded" }
    ],
    "discriminator": { "propertyName": "kind", "mapping": { "...": "..." } }
  } } }
}
```

and each tagged component declares its tag:

```json
"RegistryDown": {
  "type": "object",
  "description": "The chip registry could not be reached; try again",
  "properties": {
    "kind": { "type": "string", "const": "registry_down" },
    "id": { "type": "integer", "format": "int64" },
    "message": { "type": "string" }
  },
  "required": ["kind", "id", "message"]
}
```

- The tag field is added to the component as a required `const` property,
  first. Pelican emits 3.1 and 3.2 only, so there is no 3.0 spelling to choose.
- The component carries its failure's description.
- The status's description lists each tag with its description, as a Markdown
  list, which OpenAPI allows in a description.
- A type used both tagged and untagged, or under two tags, cannot have one
  component that is right for both, so building the document refuses it. The
  message names the type and both uses, and says to declare a second type.

## Why this shape

Adding the field to the component is what OpenAPI asks for, and it makes the
schema describe the body the server actually sends. The alternative is an
inline `allOf` per branch (`$ref` plus a schema holding `kind`), which leaves
components alone. But the discriminator mapping must then point at the inline
branch, which it cannot, and generators read `allOf` as inheritance.
Recommended: the field in the component.

## Stack

- [x] **`spec-0066-tag-in-schema`** ([#220](https://github.com/matthewjones372/pelican/pull/220)): the tag as a required `const` in each
      tagged component, and a type used more than one way refused.
      Done when: the petshop-shaped document validates with a validator that
      checks discriminator properties, and a type used tagged and untagged is
      refused with a message naming both uses.
- [x] **`spec-0066-descriptions`** ([#221](https://github.com/matthewjones372/pelican/pull/221)): each component carries its failure's
      description, and the status lists tag and description per line.
      Done when: the `503` above renders as shown, and a single failure's
      status description is unchanged.

## Acceptance

```bash
./gradlew :pelican-openapi:test :pelican-codegen:test :pelican-test-golden:test
```

Then the petshop's adoption golden shows `kind` in both schemas, and a client
generated from it with openapi-generator's Kotlin target has a `kind` field.

## Open questions

- **Does adding `kind` to a component break callers?** For a response it is a
  new required field, which callers ignore, so no. Spec 0065's checker should
  agree; if it lands first, this spec's golden updates prove it.
- **Description format.** A Markdown list, or the descriptions joined by
  " or "? Recommended: the list. Swagger renders it, and it names the tag a
  reader needs to branch on.
- **A type used two ways: refuse, or generate a second component?** A
  generated name such as `ProblemRegistryDown` would let it build. Recommended:
  refuse. It is rare, the message can say exactly what to do, and a name
  nobody chose is harder to live with than a type somebody did.
