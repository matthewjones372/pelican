# 0063 — Two failures under one status

## Problem

An output's responses are named by their status, so two failures under one
status are refused when the endpoint is built: "a second one under the same
status could never be picked". For a server that is too strict. Two different
reasons for the same status are ordinary HTTP. The petshop's adoption can fail
with a `503` because the shop could not record the sale, or because the chip
registry could not be reached. Today the endpoint declares one `503`, an
`Unavailable` with a message, and the two causes are told apart only by reading
English. A client cannot branch on which happened without parsing the message,
and the OpenAPI document says there is one kind of `503` when there are two.

## Not doing

- Content negotiation. Several renderings of one response stay a
  `NegotiatedOutput`, as now.
- Two successes under one status.
- Telling responses apart by header. The body says which.

## Shape

```kotlin
val notRecorded = errorJson<NotRecorded>(503, "The sale could not be recorded").tagged("not_recorded")
val registryDown = errorJson<RegistryDown>(503, "The chip registry could not be reached").tagged("registry_down")

val adoptPet = endpoint(petId) {
    post("pets" / petId / "adoption")
    json<Pet>().orFail(petMissing, petTaken, petNotChipped, notRecorded, registryDown)
}
```

- Two failures may share a status when every one under it is `tagged`. The tag
  is written into the body under a discriminator field, `"kind"` by default.
- The document declares that status's response as a `oneOf` of the tagged
  schemas, with a discriminator mapping each tag to its schema. A generated
  client, Pelican's or anyone's, can branch on it.
- A handler names the declaration (`notRecorded(…)`), as it does now, so the
  server always knows which it is answering.
- Pelican's client reads the status, then the tag, and answers the failure that
  carries it. A body under that status with no tag, or one it does not know, is
  `ApiCallFailed`, as an undeclared body is today.
- Untagged failures under one status are still refused, with the message saying
  that tagging them is the way to declare both.

## Why this shape

Tagging keeps the rule that a handler names what it answers and adds one only
where it is needed: a body that says which response it is. The alternative is
one failure type with a sealed payload, which Pelican can do today. But the
document then shows one schema, and every client has to know the payload's own
convention. Recommended: tags, written into the document as OpenAPI's
discriminator so clients outside Pelican get it too.

## Stack

- [x] **`spec-0063-tagged-failures`** ([#205](https://github.com/matthewjones372/pelican/pull/205)) — `tagged`, the relaxed check, the
      server's body and the document's `oneOf` with discriminator.
      Done when: an endpoint with two tagged `503`s builds, answers each with
      its tag in the body, and its document validates with both schemas under
      one `503`; two untagged ones are still refused.
- [x] **`spec-0063-client`** ([#206](https://github.com/matthewjones372/pelican/pull/206)) — Pelican's client and the generator read the tag.
      Done when: a generated client answers `NotRecorded` and `RegistryDown`
      from two `503`s told apart only by their tag, and an untagged `503` body
      is `ApiCallFailed`.

## Acceptance

```bash
./gradlew :pelican-core:test :pelican-openapi:test :pelican-codegen:test :pelican-test:test
```

Then the petshop declares `notRecorded` and `registryDown` separately.

## Open questions

- **Field name.** `"kind"`, `"type"` or the endpoint's choice? Recommended:
  `"kind"` by default, settable per declaration, since `"type"` collides with
  common payload fields.
- **A tag on a body that is not an object** (a `text` failure)? Recommended:
  refused; only JSON objects can carry the field.
- **Tag required everywhere, or only under shared statuses?** Recommended: only
  where two share a status, so nothing changes for anyone else.
