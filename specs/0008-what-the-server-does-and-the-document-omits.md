# 0008 — What the server does and the document omits

## Problem

Three things the server does that the document does not say. Each is small; the
class they belong to is not, because the library's claim is that the two cannot
disagree.

**Headers set with `emits(...)` ride on failures.** `Params.responseHeaders()`
is applied to whatever response came back, and the KDoc says why: "a header set
before a failure was deliberate". `OpenApi.responses()` puts
`ep.responseHeaders + out.headers` on each success and only `err.headers` on
each failure. So an `X-Request-Id` is on every 404 the service sends and on none
of the 404s the document describes.

**A parameter's default is enforced and never published.**
`queryParam("size", IntCodec.between(1, 200)).default(50)` emits
`{"type":"integer","minimum":1,"maximum":200}`. The bounds arrive because facets
live on the codec; the default lives on the `QueryParam` and nothing reads it.
`AGENTS.md` states the rule in the other direction — "refining a codec must
narrow what is accepted *and* reach the document" — and a default is the same
claim.

**Several successes, one description.** `successDescription()` is a fixed table
with no way in, so `json<Order>(201, location) or json<Order>(200)` publishes
`"description": "Success."` twice. A declared failure takes its description as
the second positional argument. For a 201 beside a 200 carrying the same type,
the description is the only thing telling a reader which is which.

## Not doing

- No `example` on responses, and no `examples` map. Separate question.
- No `default` on a required parameter: it has none, and one would be a lie.
- `successDescription`'s table stays as the fallback. This adds a way in, not a
  replacement.
- The wire does not change. Nothing here alters what a server sends.

## Shape

```kotlin
val remember = endpoint(name, note) {
    put("greetings" / name)
    emits(requestId)                                   // now on the 429 too
    json<Greeting>(201, greetingAt, description = "Newly learned") or
        json<Greeting>(200, description = "Already known")
}

val size = queryParam("size", IntCodec.between(1, 200)).default(50)
// "schema":{"type":"integer","minimum":1,"maximum":200,"default":50}
```

`description` goes after the `vararg headers`, so it is always named at the call
site. The default is encoded through the parameter's own codec, so the
document's spelling is the wire's.

## Why this shape

For the headers, the fix could go either way: stop sending them on failures, or
document them there. The KDoc says sending them is deliberate and it is right —
a header set before a failure was set on purpose. So the document changes.

They are published on failures as `required: false` unless the header's own
declaration says otherwise, because a handler that fails early may not have
reached the line that sets it.

## Stack

- [x] **`spec-0008-headers-on-failures`** — `ep.responseHeaders` published on declared failures.
      Done when: an endpoint with `emits(requestId)` and a declared 429 documents `X-Request-Id` on both, and the golden moves. Landed in [#49](https://github.com/matthewjones372/pelican/pull/49).
- [x] **`spec-0008-parameter-defaults`** — `default` in the parameter schema for query, header and cookie.
      Done when: `.default(50)` publishes `"default":50`, `.optional()` publishes none, and a list-valued default publishes an array. Landed in [#50](https://github.com/matthewjones372/pelican/pull/50).
- [x] **`spec-0008-success-descriptions`** — `description` on `json`, `text`, `empty`, `ndjson`, `sse`, `jsonArray`, `bytes`, on both the member and the free function.
      Done when: two successes carry two descriptions, and one that says nothing still gets the table's text. Landed in [#51](https://github.com/matthewjones372/pelican/pull/51).

## Acceptance

```bash
./gradlew build
```

## Open questions

1. `required: false` on a failure's inherited headers, or `required` as
   declared? Recommend false — the endpoint promised it on a success, and a
   failure raised in a filter never reached the handler.
2. Should `setRawHeader` be documented anywhere? Recommend no. It exists for
   what no document should promise, and the KDoc says so.
3. Do the goldens for the example move in a way `Compatibility` calls
   `COMPATIBLE`? Recommend checking that in the first entry — adding a
   documented header and a default should both be compatible, and if the
   classifier disagrees that is a finding about the classifier.
