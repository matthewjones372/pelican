# 0067: Stub files templated from the request body

## Problem

Spec 0062's `stubFile` exports a templated stub only when the answer depends on
path parameters. An endpoint with a request body is refused outright. The
petshop's chip registry has two endpoints, and the second is exactly this case:

```kotlin
val recordKeeper = endpoint(chipNumber, newKeeper) {
    post("chips" / chipNumber / "keeper")
    json<ChipRecord>() orFail noSuchChip
}
```

Its demo stub answers with the chip number from the path and the keeper from
the body. So the petshop generates `chips.json` for the lookups and keeps a
hand-written `keeper.json` beside it, using WireMock's
`{{jsonPath request.body '$.keeper'}}`. The hand-written file is the drift
0062 set out to remove: rename `keeper` in `NewKeeper` and the generated
stubs fail their check, but this one keeps answering the old shape.

## Not doing

- Bodies that are not JSON (form, multipart, text, streams). Refused, as now.
- Answers computed from the body (a total, an uppercased name). Only a value
  copied from the body into the answer can be a template; anything else is
  refused, as a query-dependent answer is today.
- Matching requests by body. The templated mapping matches method and path,
  as 0062's does.

## Shape

```kotlin
stubFile(JacksonCodecs) {
    stub(recordKeeper, example = In2("981000000000001", NewKeeper("Ada"))) { (number, keeper) ->
        ok(ChipRecord(number, keeper.keeper))
    }
}
```

- A templated stub for an endpoint with a body takes an `example` input. Pelican
  cannot build an arbitrary body type, but it can vary one it was given.
- The probe encodes the example's body with the codecs, replaces every string
  and number leaf with a sentinel, decodes it back and calls the answer, as
  0062 already does for path parameters. Two rounds, as now.
- A sentinel found in the answer becomes `{{jsonPath request.body '$.path'}}`
  for the leaf it came from, next to the `{{request.pathSegments.[n]}}` path
  parameters already get.
- An answer whose status, or anything not traced to a leaf, moves between
  rounds is refused with the endpoint named, as now.
- A leaf whose sentinel the codec rejects (a refinement, a pattern) keeps the
  example's value and is not templated. The refusal message for a moved answer
  lists those leaves, since one of them may be the cause.
- Without `example`, an endpoint with a body is refused as today, and the
  message now points at `example`.

## Why this shape

An example keeps the probe honest: every value the export varies came from a
real, decodable body, and leaves Pelican cannot vary (enums, booleans) keep the
example's value. The alternative is deriving a body from the schema, which
needs a value for every type a schema can describe and fails on the first
refinement it cannot satisfy. Recommended: the example.

## Stack

- [x] **`spec-0067-body-probe`** ([#223](https://github.com/matthewjones372/pelican/pull/223)): `example`, the leaf-by-leaf probe, and the
      `jsonPath` templates in the exported mapping.
      Done when: `recordKeeper` exports one mapping that WireMock answers with
      the path's number and the body's keeper, for a body the probe never saw.
- [x] **`spec-0067-refusals`** ([#224](https://github.com/matthewjones372/pelican/pull/224)): the refusal for computed answers, naming the
      leaf, and leaves the codec rejects kept at the example's value.
      Done when: an answer that uppercases the keeper is refused naming
      `$.keeper`, and a body with an enum or refined field exports with the
      example's value kept.

## Acceptance

```bash
./gradlew :pelican-test-wiremock:test
```

Then the petshop's `DemoStubsSpec` writes `recordKeeper` too, and
`demo/registry/mappings/keeper.json` is deleted.

## Open questions

- **Nested objects and arrays.** Leaves inside an array have no stable
  `jsonPath` once its length changes. Recommended: vary leaves in objects at
  any depth, keep array contents as the example has them, and refuse if an
  answer copies from inside an array.
- **A sentinel the handler rejects.** A number such as `700001` decodes but
  may fail a range check in the handler, which answers a different status.
  Recommended: treat it like a codec refusal, keep the example's value for
  that leaf, and retry once, rather than refusing the whole stub.
