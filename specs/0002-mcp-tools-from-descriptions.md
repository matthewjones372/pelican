# 0002 — MCP tools from endpoint descriptions

Depends on [0001](0001-self-contained-schemas.md): a tool schema that is only
valid inside an OpenAPI document is no use to a model.

## Problem

A model cannot call a Pelican service. The MCP server that would let it is
written by hand against the Kotlin SDK: `inputSchema` as a `buildJsonObject`
literal, then `request.arguments?.get("qty")?.jsonPrimitive` in the handler.
Schema in one place, decoding in another, kept in step by whoever remembers —
about thirty lines per tool, none of it checked against the service.

Everything needed is already derived: `StandaloneSchemas` for 2020-12 that
resolves and decodes, `PlainCodec` for the facets a refinement adds,
`operationName` for the name the document and the generated client already
agree on, `Api.handlerFor` to invoke a bound handler with filters folded in.

## Not doing

- **No transport and no MCP SDK on the classpath.** This spec stops at a tool
  list and a callable dispatch, both testable in memory. Serving them over
  stdio and Streamable HTTP is spec 0003, and needs this one first.
- No native `tool(...)` description beside `endpoint(...)`. Later spec, not
  worth writing until derived tools have been read in a real client.
- Resources, prompts, elicitation, MRTR, `x-mcp-header`, subscriptions.
- Streaming outputs, multipart and raw bodies, cookie params: refused at build
  time rather than half-served.
- No change to `pelican-core`.

## Shape

```kotlin
val tools = api.spec().mcpTools()                                // descriptions only
api.mcpDispatch().call("placeOrder", jsonObj { … })              // ToolResult
```

```json
{ "name": "placeOrder",
  "description": "Place an order",
  "inputSchema": {
    "type": "object",
    "properties": { "userId": {"type": "integer", "format": "int64", "description": "The user's id"},
                    "body": {"$ref": "#/$defs/CreateOrder"} },
    "required": ["userId", "body"],
    "$defs": { "CreateOrder": { … } } },
  "outputSchema": { … } }
```

Name from `operationName`, `description` from `description ?: summary`,
`title` only where it would differ; `X-Api-Key` absent by design;
`outputSchema` only where a single JSON success declares its type, since
`structuredContent` becomes binding once one is published.

Dispatch decodes arguments through the `PlainCodec` and refinements HTTP uses,
into a `Params`, then runs the bound handler. `Outcome.Ok` carries
`structuredContent` and the same JSON as text; `Outcome.Err`, `DecodeFailure`
and `ApiException` carry `isError` with a message a model can retry against.

## Why this shape

A core-only module, so a golden tool list needs no server on its classpath —
the layering `pelican-metrics` already asserts. `ToolResult` is this module's
own value, not the SDK's `CallToolResult`: mapping to the SDK type is four
lines in 0003, while core-only code depending on an SDK type is impossible.

The alternative — one module carrying the SDK and transports too — puts Ktor
in front of everyone who wanted only the schemas. Not recommended.

## Stack

- [x] **`spec-0002-mcp-tool-descriptions`** — `pelican-mcp`; `ApiSpec.mcpTools()`, names, input and output schemas over `StandaloneSchemas`.
      Done when: the example Orders API yields one tool per endpoint, and `limit`'s `between(1, 100)` appears as `minimum`/`maximum`. Landed in [#30](https://github.com/matthewjones372/pelican/pull/30).
- [x] **`spec-0002-mcp-dispatch`** — arguments to `Params` to `handlerFor`; result to `ToolResult`; refusals; golden tool list.
      Done when: valid arguments reach the handler, a declared failure returns an error result, a refinement rejects bad input before the handler, and changing a schema moves the golden. Landed in [#32](https://github.com/matthewjones372/pelican/pull/32).
- [x] **`spec-0002-mcp-docs`** — `docs/mcp.md`, README appendix line, `docs/modules.md` row and count, `docs/reference.md` section, roadmap entry.
      Done when: the page carries the endpoint-to-tool mapping table, one worked example end to end, every refusal with its reason, why header params are excluded and how a credential reaches the service instead, and what MCP cannot carry — `Retry-After` on the 429, streamed responses. Landed in [#33](https://github.com/matthewjones372/pelican/pull/33).

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Body nested under one `body` property, or flattened beside path and query?
   Flat reads better to a model but must refuse name collisions. Recommend
   nested first.
2. Header params as tool arguments? A model inventing an `Authorization` value
   is a hazard. Recommend excluded, opt-in per `McpOptions`.
3. Which endpoints by default? Recommend every non-hidden one with an
   `include` filter — what `hidden` already means for the document.
4. Is a tool list nobody can serve yet worth landing alone? Recommend yes: it
   is the half that must be right, and 0003 is small once it exists.
5. Where does this sit now roadmap items 1–3 have landed? Recommend the next
   entry, not a jump ahead of what is queued.
      Landed in [#30](https://github.com/matthewjones372/pelican/pull/30).
      Landed in [#32](https://github.com/matthewjones372/pelican/pull/32).
      Landed in [#33](https://github.com/matthewjones372/pelican/pull/33).
