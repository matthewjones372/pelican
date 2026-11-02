# 0106 - HTTP QUERY (RFC 10008)

Approved by the owner 2026-11-02. Provenance, baseline and independent-review notes are in the pull request.

## Problem
`Method` is `GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS`. A read whose criteria do not fit a URL is declared as a
`POST`, so caches, retries and `idempotencyKey()` must treat a safe read as a mutation. RFC 10008 defines `QUERY`:
safe, idempotent, request content required.

## Not doing
No Doorlist routes, no cache-key or CDN behaviour, no `GET`-to-`QUERY` rewriting, no server-side response caching, no `Accept-Query` response header (RFC 10008 section 3) or advertising of
supported query media types,
no stable release. MCP tool annotations: Pelican derives none today (no read-only or idempotent hints exist), so
there is nothing to mark; a `QUERY` endpoint derives a tool exactly as a `POST` does.

## Shape
```kotlin
val search = endpoint(jsonBody<Filter>()) {
    query("orders" / "search")        // route(Method.QUERY, ...)
    emits(contentLocation)            // optional RFC 10008 section 2.4 header
    json<List<Order>>()
}
```
- `Method.QUERY`, appended last (expand-only; no ordinal moves).
- `QUERY` is idempotent: in `IDEMPOTENT_METHODS`, so `RetryPolicy` retries it and `idempotencyKey()` is refused.
  It is not state changing, so the cross-site cookie check does not apply.
- Declaration needs a decoded body (JSON, form, negotiated or NDJSON). No body, a raw body or multipart is refused
  when the endpoint is built.
- Request content: `Content-Type` absent is 400; a media type the endpoint does not read is 415, even where the
  endpoint reads only one type (other methods ignore the header in that case). One rule in `RequestBodyCodecs`, so
  every backend answers alike. Pekko's entity reports `application/octet-stream` for an absent header, so it
  answers 415 where the others answer 400 (recorded in the cross-backend test).
- OpenAPI: 3.2.0 writes the operation under `query`. 3.1.0 has no such field, so the operation is written under
  the `x-query` extension rather than an invalid `query` key or a refusal.
- Importer reads a 3.2 `query` operation back as `Method.QUERY`.
- Clients and generated code: `Method.QUERY` flows through `RequestSpec` and the Jetty client unchanged.
- Backends: Jetty serves `QUERY`; Pekko and Gannet map it (Pekko as a custom method, server-side custom-method
  allowance set where Pekko parses requests).
- `contentLocation`: a `ResponseHeader<String>` named `Content-Location`, declared with `emits`; no automatic value.

## Why this shape
Reusing the route/endpoint machinery means every interpreter, the in-memory client and the typed client work
without a second path. The alternative of a Doorlist-side adapter is rejected by the Doorlist packet. `x-query` in
3.1 keeps the default document valid; the alternative (throw unless 3.2) would break docs for any API on defaults.

## Stack
- [ ] `claude/http-query-20261102`: whole slice; Method, core rules, Jetty/Pekko/Gannet, OpenAPI, importer,
      docs. Over the ~200 line soft cap because a new enum constant must compile in every exhaustive `when`; the
      tests dominate the count.
      Done when: tests below pass in `./gradlew build`.

## Acceptance
Core: DSL builds QUERY, body required, idempotent set, key refused. OpenAPI 3.1 and 3.2 golden. Jetty in memory
and bound: accept with Content-Type, 400 without, 415 wrong, safe-repeat, `Content-Location`, typed-client round
trip, 405 listing QUERY. Existing methods unchanged.

## Open questions
- Resolved, owner decision 2026-11-02: adding the `QUERY` constant is a minor-version source-compatibility change
  (CHANGELOG note and a line in the reference Stability section).
- Resolved, owner decision 2026-11-02: ships as one stack entry, not split.
- Name `query(path)` reads like the existing `query(vararg QueryParam)`; overloads are unambiguous. Recommend keep.
- Source break: an exhaustive `when (method)` in caller code needs a `QUERY` branch. Recommend note in CHANGELOG.
- Refuse a `QUERY` that declares query-string parameters? RFC allows them. Recommend allow.
