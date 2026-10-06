# 0065: Golden checks that read through oneOf

## Problem

The compatibility check (`Compatibility.kt`, which `Golden` runs) compares a
payload's type, enum, constraints, properties and array elements. It does not
look at `oneOf` or `anyOf`. Spec 0063 made a status that holds two tagged
failures render as a `oneOf`, so splitting one failure into two reads as every
field being removed. The petshop hit this when its adoption's `503` became
`registryDown` and `notRecorded`:

```
✖ `id` in the 503 response (application/json) is gone
✖ `message` in the 503 response (application/json) is gone
```

Both new branches carry `id` and `message`. The check called a safe change a
break, and the only way through was to overwrite the golden by hand, which is
what the check is there to stop. A real break inside a `oneOf`, such as a field
dropped from one branch, is not seen at all today.

## Not doing

- `allOf`. Pelican does not emit it for payloads, and `pelican-import` reads it
  as inheritance, which is another comparison.
- `not`, `if`/`then`, or any other composition keyword.
- Changing what counts as breaking for plain schemas. Only compositions are new.

## Shape

No new API. `apiChanges` gains rules for compositions:

- **One schema becomes a `oneOf`.** The old schema is compared with every
  branch. A break in any branch is a break, named with the branch:
  `` `id` in the 503 response (RegistryDown) is gone ``. A field every branch
  still has is not reported.
- **A `oneOf` becomes one schema.** The reverse, with the direction flipped:
  every old branch is compared with the new schema.
- **`oneOf` to `oneOf`.** Branches are paired by discriminator tag where there
  is a mapping, and by `$ref` otherwise, then compared pairwise.
  - A branch added to a response is a break: a client switching on the tag meets
    one it does not know. Added to a request, it is safe.
  - A branch removed from a request is a break. Removed from a response, it is
    safe.
  - A changed `discriminator.propertyName` is a break in both directions.
- `anyOf` follows the same rules, paired by `$ref`.

The petshop's split then reports one non-breaking change: "the 503 response
now has two shapes, `registry_down` and `not_recorded`, each with every field
it had".

## Why this shape

Comparing the old schema with every branch is the reader's view: a caller that
read `id` from any `503` still finds it, whichever branch arrives. The
alternative is to call any move into or out of a `oneOf` a single "shape
changed" break and leave the rest to a reviewer. That is simpler and still
wrong for the petshop's case, which is the case 0063 exists for. Recommended:
branch by branch.

## Stack

- [x] **`spec-0065-oneof-pairs`** ([#218](https://github.com/matthewjones372/pelican/pull/218)): one schema to `oneOf` and back, compared
      branch by branch, with the branch named in each change.
      Done when: the petshop's `Unavailable` to `RegistryDown | NotRecorded`
      split reports no break, and the same split with `id` dropped from one
      branch reports exactly that field in that branch.
- [ ] **`spec-0065-oneof-branches`**: `oneOf` to `oneOf`, paired by tag or
      `$ref`, with added and removed branches judged by direction, and `anyOf`.
      Done when: a tag added to a response and a branch removed from a request
      are each one break, their mirror images are safe, and a renamed
      discriminator field is a break.

## Acceptance

```bash
./gradlew :pelican-openapi:test :pelican-test-golden:test
```

Then the petshop's golden for `POST /pets/{petId}/adoption` passes against
its old file without being overwritten.

## Open questions

- **A tag added to a response: break or warning?** A Pelican client answers an
  unknown tag with `ApiCallFailed`, and generated clients elsewhere often fail
  the same way. But an API adding a failure reason is ordinary. Recommended:
  breaking. Rewriting the golden is how a team accepts it on purpose, and a
  silent pass is the worse mistake.
- **Pairing without a discriminator or `$ref`.** Inline branches have nothing
  to pair by. Recommended: pair by position, and say in the change that it
  did.
