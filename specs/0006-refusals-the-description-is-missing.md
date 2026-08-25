# 0006 — Refusals the description is missing

## Problem

`Endpoint.validate` refuses a path parameter that is not in the path, a capture
nobody declares, a repeated capture name, a repeated multipart part, a second
`default` response and a repeated response header. Three neighbouring mistakes
in the same class are not refused anywhere, and each produces something broken
downstream rather than a message.

**Two parameters with one name.** OpenAPI requires parameters unique by `name`
and `in`. Nothing checks it, and the document says so out loud:

```kotlin
endpoint(queryParam<String>("q"), queryParam<Int>("q")) { get("dup"); json<String>() }
// "parameters":[{"name":"q","in":"query","schema":{"type":"string"}},
//               {"name":"q","in":"query","schema":{"type":"integer"}}]
```

The realistic way in is listing a reusable parameter on `endpoint(...)` and
calling `query(...)` for it inside the block: `EndpointBuilder.init` registers
it, and `query()` registers it again.

**Two endpoints with one `operationId`.** It must be unique across the API, and
it is also the generated client's method name, so two of them emit `fun same`
twice — a file that does not compile.

**Two endpoints on one route in an `ApiSpec`.** `Api` refuses this. `ApiSpec`
does not, and the emitter's `byMethod[method] = operation(...)` overwrites, so
a documentation-only build publishes one operation and never mentions the
other.

## Not doing

- No deduplication magic. The *same* value listed twice is a no-op; two
  different values under one name is an error.
- No change to `Api`'s existing route-clash message, which is about binding
  rather than about describing.
- Nothing about generated-client name collisions from other sources —
  `Names.unique` already handles those.

## Shape

Three refusals, each naming both offenders and what to do:

```
GET /dup declares two query parameters named 'q'. A request carries one value
under a name, so nothing could tell them apart, and the document would have two
entries for one parameter. Declare one, or give the second a name of its own.
```

```
Two endpoints declare the operationId 'same' — GET /a and GET /b. It is the
document's key for an operation and the generated client's method name, so the
second would replace the first. Give one of them an operationId of its own.
```

## Why this shape

The alternative is checking in the emitter, where the invalid document is
actually produced. That is one place instead of two, and it fails inside a
build task, far from the description that caused it. Construction time is where
the person is. Not recommended.

Route clash moves into `ApiSpec.init` so `Api.spec()` inherits it and the two
cannot drift.

## Stack

- [x] **`spec-0006-parameter-names`** — duplicate query, header and cookie names refused in `validate`, by identity first.
      Done when: two different parameters under one name and location is a refusal, the same value declared twice is not, and the message names both. Landed in [#37](https://github.com/matthewjones372/pelican/pull/37).
- [x] **`spec-0006-operation-and-route`** — `operationId` uniqueness and the route clash, both in `ApiSpec.init`; `Api` keeps its own wording.
      Done when: an `ApiSpec` holding two `GET /clash` endpoints is refused, and so is a shared explicit `operationId`. Landed in [#38](https://github.com/matthewjones372/pelican/pull/38).

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Case sensitivity. Header names are case-insensitive on the wire, query
   parameters are not, cookies are not. Recommend matching per location rather
   than one rule for all three.
2. Should a derived `operationId` collision be refused too? It can only happen
   when the routes already clash. Recommend refusing on the resolved name, so
   one check covers both.
3. Does `ApiSpec` refusing a route clash break the importer, which builds specs
   from documents that may carry one? Recommend checking `pelican-import`'s
   fixtures first, and refusing there with the document's own path if so.
