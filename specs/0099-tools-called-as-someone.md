# 0099 — Tools called as someone

Builds on [0002](0002-mcp-tools-from-descriptions.md) and 0093 (who may call).

## Problem

`/mcp` has "no auth of its own" (`docs/mcp.md`). The credential a tool call sends is `mcpOptions { headers }`, one map
fixed when the server is built, and a dispatched call's `Params` has no request behind it, so an endpoint declaring
`authenticatedBy(...)` is never given a caller and every model acts as the one service key. Doorlist needs each call
to run as the person its bearer token names, through the same `authenticatedBy` and `requires` as HTTP, and plans a
Jetty `Handler` in front of `/mcp` plus a per-call header hook to get there (dependencies.md item 13, Doorlist spec
0028). It also wants `Idempotency-Key` derived per call, since a model must not invent one. And a client deciding
whether to ask a person before a call reads MCP's tool annotations, which Pelican does not emit (item 14).

## Not doing

- **An authorization server, or the protected resource metadata document** (RFC 9728). The service serves both; this
  spec takes the metadata URL to put in `WWW-Authenticate`.
- **Cookies on `/mcp`.** A cookie parameter is still refused as a tool; a session-cookie caller can be presented, but
  no MCP client sends one.
- **Filtering `tools/list` by caller.** The list is a description; see the open questions.
- **`openWorldHint` derived from anything.** Declared only.

## Shape

```kotlin
handlerWithMcp(options = mcpOptions {
    include = { it.operationId in registry }
    callers(resourceMetadata = URI("https://app.example/.well-known/oauth-protected-resource/mcp"))
    headers { call -> mapOf("Idempotency-Key" to "mcp:" + call.digest()) }      // per call; a map still works
    hints { endpoint -> derived(endpoint).copy(destructive = endpoint.operationId in destructive) }
})

dispatch.call("refundBooking", arguments, credentials)       // in memory, as the transports call it
```

- **Callers.** `McpDispatch.call` takes `Credentials`; the HTTP transports pass the POST's own (its `Authorization`).
  An endpoint with `authenticatedBy` decodes its caller through the API's `Authenticator` exactly as a route does,
  0093's `requires` included, so `this[caller]` in the handler is the person behind the token.
- **Refused callers are HTTP answers**, as the MCP authorization spec asks: no credential or one that fails is a 401
  with `WWW-Authenticate: Bearer resource_metadata="..."`, a missing permission a 403 with
  `error="insufficient_scope"`. Over stdio, where there is no HTTP, the same refusals are `isError` results.
- **`McpCall`** is what a per-call `headers { }` reads: the tool name, the JSON-RPC id, the arguments and the
  credentials. `digest()` is SHA-256 over all four with the arguments canonicalised, so a client retrying one request
  id gets one key, and 0092 replays the first answer.
- **Annotations,** in `tools/list` as MCP's `annotations` object. Derived: `readOnlyHint` for GET and HEAD;
  `destructiveHint: true` for DELETE; `idempotentHint` for GET, HEAD, PUT, DELETE and any `idempotencyKey()`
  endpoint. Anything not derived is left out, so a client reads MCP's own defaults (not read-only, destructive).
  `hints { }` overrides per endpoint.

## Why this shape

The credential travels with the call, not around it, so the dispatch reuses core's caller decoding and a tool cannot
be reached as someone a route would refuse; a handler in front of `/mcp` can verify a token but cannot hand the
caller to the endpoint. For annotations, option A derives them in `pelican-mcp` with an override in `McpOptions`;
option B declares them on the endpoint in core, which puts an MCP word into every description and the document.
Recommended: A.

## Stack

- [ ] **`spec-0099-dispatch-callers`**: `call(name, arguments, credentials)`, the caller and `requires` decoded,
      refusals as results. Done when: in memory, a tool reads `this[caller]` as the token's subject, and a caller
      missing a permission gets the 403 as `isError`.
- [ ] **`spec-0099-transport`**: Jetty and Pekko pass the POST's credentials; `callers(resourceMetadata)`; the 401
      and 403 as HTTP. Done when: on both, a tool call with no token is a 401 naming the metadata URL.
- [ ] **`spec-0099-per-call-headers`**: `headers { call -> }`, `McpCall`, `digest()`. Done when: two calls with one
      request id reach a keyed endpoint's handler once, and the build-time check accepts a header the function names.
- [ ] **`spec-0099-annotations`**: derived hints, `hints { }`, the golden tool list. Done when: the golden moves by
      exactly the `annotations` objects, and a DELETE reads destructive.
- [ ] **`spec-0099-docs`**: `docs/mcp.md` (Credentials, What becomes what, "no auth of its own" rewritten), changelog.

## Acceptance

```bash
./gradlew build   # McpToolsFromOrdersTest, the MCP handlers' tests, the golden tool list
```

## Open questions

1. **Anonymous `tools/list`?** Recommended: yes, every included tool, as now: it says what exists, not who may run
   it, and Doorlist answers it anonymously. A per-caller list is a later option.
2. **A required header the function might not supply:** the build-time refusal cannot see inside a function.
   Recommended: the function declares the names it supplies (`headers(names = setOf("Idempotency-Key")) { ... }`),
   and a call where it then returns none is a 500, a bug in the service.
3. **Which messages can be a 401?** Recommended: only a `tools/call` whose endpoint needs a caller; `initialize`,
   `ping` and `tools/list` never do, so a client can connect before it signs in.
4. **Should `destructiveHint: false` be derived for POST?** Recommended: no; a POST may destroy, and the safe default
   is MCP's own. A service says `false` through `hints { }`.
