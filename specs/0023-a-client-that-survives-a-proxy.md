# 0023 — A client that survives a proxy

## Problem

Three gaps stand between the generated client and production. First: when a
*declared* failure status arrives carrying a body the codec cannot read — a
proxy's HTML 404, a load balancer's plain-text 502 — the generated code calls
`decodeFromString` unguarded (`OrdersClient.kt:634-638,691-695`) and a bare
Jackson/kotlinx exception escapes with no status, path, or body attached; the
typed `Outcome` contract collapses exactly where it matters. No test
exercises the generated `ApiCallFailed` path at all. Second: every
generated-client test in the repo binds a real socket, because the in-memory
machinery in pelican-test implements its own transport seam, not core's
`ClientTransport`. Third: the constructor-wide timeout is stamped on every
call including SSE (`OrdersClient.kt:508,570-576`), and Ktor's
`requestTimeoutMillis` bounds the whole exchange while JDK and Pekko bound to
the response head (`KtorHttpTransport.kt:129-137,182`) — so a stream dies at
30s on one transport and lives on the other two.

## Not doing

- **No retry changes.** `RetryingTransport` stays opt-in and as-is.
- **No OkHttp transport.** Post-1.0 (spec 0030).
- **No merging pelican-test's transport with `ClientTransport`.** The test
  stack is deliberately synchronous, string-bodied, and pin-friendly.

## Shape

```kotlin
// 1. every decode failure is a named refusal
throw ApiCallFailed(status, method, path, rawBody, cause = decodeFailure)

// 2. a generated client tested without a socket
val client = OrdersClient(InMemoryClientTransport(api))

// 3. streamed operations opt out of the blanket deadline
OrdersClient(transport, timeout = 30.seconds)  // applies to unary calls only;
                                               // streamed calls carry timeout = null
```

`InMemoryClientTransport` lives in core: `Api` and `ClientTransport` are both
core types and the handler chain is already `CompletionStage`-based, so the
bridge needs no third-party dependency.

## Why this shape

Wrapping decode failures in `ApiCallFailed` reuses the type users already
handle for undeclared statuses — one failure vocabulary, not two. The
in-memory bridge turns every generated-client test (and every user's) from a
socket test into a function call; putting it in core rather than pelican-test
means production code can use it for hermetic wiring too. For timeouts, the
alternative — unifying all three transports on whole-exchange semantics —
would break streaming on all of them; head-only cannot be retrofitted onto
Ktor's plugin, so the honest fix is at the call site that knows it streams.

## Stack

- [ ] **`spec-0023-decode-guard`** — codegen templates wrap success and
      failure decode in `ApiCallFailed`; golden clients regenerated; first
      tests for the `ApiCallFailed` paths, including HTML-for-JSON.
      Done when: a declared 404 with an HTML body surfaces status, path, and raw body.
- [ ] **`spec-0023-inmemory-transport`** — `InMemoryClientTransport(api)` in
      core, streaming supported, one generated-client suite migrated to it.
      Done when: `GeneratedKotlinClientTest` passes with no port bound.
- [ ] **`spec-0023-streaming-timeout`** — generated streamed calls stop
      inheriting the blanket timeout; per-transport timeout semantics get a
      table in `docs/generated-client.md`, plus the missing `Authorization`
      recipe.
      Done when: an SSE call outlives the constructor timeout on all three transports, pinned by test.

## Acceptance

```bash
./gradlew build checkOrdersClient
```

## Open questions

1. Should `ApiCallFailed` carry the raw body capped (say 8 KiB) to keep a
   hostile 502 from ballooning memory? Recommend yes, capped with a marker.
2. In-memory transport and filters: run the full filter chain or handlers
   only? Recommend the full chain — it is the server users think they call.
3. Per-call timeout override in generated signatures (`timeout = null`
   parameter) or constructor-level only? Recommend constructor-level plus
   automatic exemption for streamed operations; a per-call knob can come
   later without breaking.
