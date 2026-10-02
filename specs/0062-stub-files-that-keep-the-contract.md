# 0062 — Stub files that keep the contract

## Problem

[0056](0056-a-stub-written-in-endpoints.md) made a test's stubs a matter of
endpoints: `stub(lookupChip, 3L) answers noSuchChip(Problem(…))`, so an answer
is a value the contract declares, and a contract change moves the stub or stops
it compiling. It left out stub files on disk.

But a demo, a local run, or another team's docker-compose stands WireMock up
from mapping files, written by hand as JSON. Nothing checks those against the
contract. The petshop's demo registry answered pet 3 with a bare `404`, where
the registry's contract says a `404` carries a `Problem`. The generated client
could not decode it, the shop treated the throw as the registry being down, and
the demo showed `registry_down` where it meant `not_chipped`. The typed tests
were right the whole time; only the hand-written file was wrong, and only
running the demo found it.

## Not doing

- Serving the files. WireMock still reads them.
- Record and replay, or proxying a real service.
- Checking WireMock files Pelican did not write. A hand-written file that
  matches a request no endpoint declares is beyond what an endpoint can say.

## Shape

The stubs a demo needs are written in endpoints, as a test's are, and exported:

```kotlin
val demo = stubFile {
    stub(lookupChip) { petId -> ok(ChipRecord("98100000000000$petId", keeper = "Petshop")) }
    stub(lookupChip, 3L) answers noSuchChip(Problem("never chipped"))
    stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }
}

demo.writeTo(Path.of("demo/registry/mappings"))   // WireMock mapping JSON
```

- A stub with a fixed input exports as a mapping with that request and that
  answer, rendered by the same codecs a server would use. So the `404` carries
  its `Problem`, with the content type the endpoint declares.
- A stub that answers from its input (`stub(lookupChip) { petId -> … }`)
  exports as a mapping on the endpoint's path pattern, with a response
  template that fills the path parameters it uses. One it cannot express as a
  template is refused when it is written, naming the endpoint.
- The written files are golden: a test that calls `writeTo` fails when the
  contract has changed them, and rewrites them on request, the way
  `pelican-test-golden` treats documents.

## Why this shape

The mistake was writing the answer twice, once typed and once as JSON. Exporting
removes the second copy. The alternative is checking hand-written files against
the contract: read each mapping, route its request through the endpoints, and
decode its body as the declared response. That keeps the files hand-written but
can only report, and a template body cannot be decoded until it is rendered.
Recommended: export.

## Stack

- [x] **`spec-0062-stub-file`** ([#203](https://github.com/matthewjones372/pelican/pull/203)) — `stubFile { }` and `writeTo` for stubs with
      fixed inputs, with golden comparison.
      Done when: the petshop's three demo stubs export as WireMock mappings
      that WireMock serves, and the shop's generated client reads pet 3's
      answer as `NotFound(Problem)`.
- [x] **`spec-0062-templated`** ([#204](https://github.com/matthewjones372/pelican/pull/204)) — stubs that answer from their input, as
      response templates.
      Done when: `stub(lookupChip) { petId -> … }` exports as one mapping that
      answers pet 7 with pet 7's chip, and a stub whose answer cannot be a
      template is refused with the endpoint's name.

## Acceptance

```bash
./gradlew :pelican-test-wiremock:test
```

Then the petshop's `demo/registry/mappings/chips.json` is written by a test,
not by hand.

## Open questions

- **Which module?** `pelican-test-wiremock`, where the stubs already are, keeps
  WireMock's JSON beside the code that knows it. Recommended: there.
- **Golden by default?** Recommended: yes, so a contract change that alters a
  demo shows up in review rather than at demo time.
- **Templates at all?** Fixed inputs alone would cover the petshop if every pet
  were listed. Recommended: both entries, in that order; the first is useful
  without the second.
