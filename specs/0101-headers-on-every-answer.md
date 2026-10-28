# 0101 — Headers on every answer

Answers roadmap item 3, "A filter that can change the response".

## Problem

A filter cannot transform a response with its type intact: the chain carries `CompletionStage<Any?>`, so a filter
that wraps a body sees `Any?` and casts. The roadmap asks for a decision. What services actually need from a filter
is narrower: headers. A filter can already call `p.setRawHeader(...)`, but only on a request that reached the chain.
A 404 for no route, a 405, a 400 for an input that did not decode, a 413, a CORS preflight, the docs pages, the
OpenAPI document and `/mcp` never run a filter, so they go out bare. And headers are kept one per name, so a response
carries one `Set-Cookie`. A service today wraps Pelican's Jetty handler in its own to put CSP, HSTS,
`X-Frame-Options`, `Cache-Control` and the rest on every answer, and its handlers choose which one cookie to send
when two are due.

## Not doing

- **A typed view of the output for filters.** See below; rewriting a body is the handler's.
- **Documenting security headers.** They are on every response and say nothing about any operation; the document
  stays as it is. A header an operation promises is still `emits(...)`.
- **Changing the status or body of a refusal** from a filter. `refusals(...)` already renders the envelope.

## Shape

```kotlin
api(routes, codecs) {
    responseHeaders { answer ->                      // every response this Api's backend writes
        add("X-Content-Type-Options", "nosniff")
        add("Content-Security-Policy", if (answer.isDocs) DOCS_CSP else API_CSP)
        if (answer.endpoint?.output is SseOutput<*>) add("Cache-Control", "no-store")
    }
}

before { p -> p.addRawHeader("Set-Cookie", device(p)) }   // appended, not replacing
```

- **`responseHeaders { }`** is a value on the `Api`, folded once like the CORS policy, and applied by Pekko, Jetty and
  Gannet to every response they write: endpoints, refusals before or after the chain, unmatched routes, the docs
  modules' pages and document, and the MCP handler. `Answer` carries the path, the matched endpoint or null, the
  status and whether the docs modules served it. A header the endpoint or handler set wins over one added here.
- **`Set-Cookie` repeats.** `setHeader` on a declared `Set-Cookie`, and `addRawHeader` for any name, append; every
  other `setHeader` and `setRawHeader` still replaces. The in-memory client, the test client and every backend keep
  the repeats in order.
- **Headers a filter set before it threw** go on the refusal, as a handler's already do. The parity suite says so.
- **The reference states** that rewriting a body belongs in the handler, or in a function that wraps a typed handler
  where it is bound, and why.

## Why this shape

Option A, a typed view: `Filter<I, O>` would be generic over one endpoint's types, so it applies to one endpoint,
which is what wrapping the handler at bind time already is; an API-wide filter would still see `Any?`. And a filter
that rewrites the body ships something the document does not describe. Option B: say rewriting is the handler's, and
give filters what they lack, headers on every answer. Recommended: B. A policy on the `Api`, not a filter, because
most of the answers that go out bare never reach a filter, and moving every refusal into the chain would change what
a filter sees (and run filters on a request that failed to decode).

## Stack

- [x] **`spec-0101-set-cookie`**: repeated `Set-Cookie` through `Params`, `addRawHeader`, the in-memory and test
      clients. Done when: a handler setting two cookies answers both, in order, on every backend.
- [ ] **`spec-0101-policy`**: `responseHeaders { }`, `Answer`, applied by all three backends to endpoints and refusals.
      Done when: the parity suite sees the policy's header on a 200, a declared failure, a filter's 401, a decode
      400, a 404 for no route and a 405.
- [ ] **`spec-0101-docs-mcp`**: the docs modules and both MCP handlers apply it.
      Done when: `/openapi.json`, the reference page and `/mcp` carry it on every backend.
- [ ] **`spec-0101-reference`**: roadmap item 3 removed; Filters states where body rewriting goes; cookbook entry
      for security headers; changelog.

## Acceptance

```bash
./gradlew build   # AllBackendsTest, the docs modules' tests, the MCP handlers' tests
```

## Decisions

Each recommendation was taken on 2026-10-28.

1. **`Set-Cookie` alone repeats by default**, since RFC 6265 forbids folding it. `addRawHeader` appends any other
   name a service wants twice.
2. **The handler wins a clash** with the policy, so an endpoint can loosen a `Cache-Control` the policy set; the
   policy can read `answer` to stay off it instead.
3. **The policy sees the status**, as `Answer.status`.
4. **`spec-0101-set-cookie` lands first in this stack.** 0094's `spec-0094-cookies` (#399) had already merged, with
   `setCookie` and a repeated raw `Set-Cookie`; this entry adds what it left out.
