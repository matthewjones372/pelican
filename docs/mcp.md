# Tools a model can call

Linked from the [README](../README.md). The endpoints a service already
describes, derived into MCP tool descriptions and a dispatch that runs them,
from the same values the routes and the OpenAPI document come from.

---

## What it is for

An MCP server written by hand against an SDK states each tool twice: an
`inputSchema` as a JSON literal, then argument decoding in the handler. The two
are kept in step by whoever remembers, and neither is checked against the
service.

The endpoint already says all of it: its name, its arguments, their types, the
constraints it enforces and the shapes it answers with. Three modules turn that
into tools:

| Module | What it adds | Depends on | Released |
|---|---|---|---|
| `pelican-mcp` | the tool list and a dispatch, as values | `pelican-core`, [`pelican-schema`](schemas.md) | 1.0.0 |
| `pelican-mcp-server` | the protocol: stdio via `mcpServe`, and the HTTP half a backend mounts | `pelican-mcp` | 1.0.0 |
| `pelican-pekko-mcp` | `/mcp` on Pekko HTTP | `pelican-pekko`, `pelican-mcp-server` | 1.0.0 |
| `pelican-jetty-mcp` | `/mcp` on Jetty 12 | `pelican-jetty`, `pelican-mcp-server` | 1.1.0 |

None of them depends on an MCP SDK. On Pekko, Pekko itself is yours to
declare:

```kotlin
dependencies {
    implementation("io.github.matthewjones372:pelican-pekko:1.2.0")
    implementation("io.github.matthewjones372:pelican-jackson:1.2.0")
    implementation("io.github.matthewjones372:pelican-mcp:1.2.0")         // the tools as values
    implementation("io.github.matthewjones372:pelican-mcp-server:1.2.0")  // spoken over stdio
    implementation("io.github.matthewjones372:pelican-pekko-mcp:1.2.0")   // and on /mcp
    // Pelican compiles against Pekko and ships none of it.
    implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-stream_2.13")
    implementation("org.apache.pekko:pekko-http_2.13:1.4.1")
}
```

`pelican-mcp-server` and `pelican-mcp` arrive through `pelican-pekko-mcp` as
`api` dependencies; they are listed so the block reads the same for a service
that only wants stdio. On Jetty, declare `pelican-jetty` and
`pelican-jetty-mcp` in place of `pelican-pekko` and `pelican-pekko-mcp`, and
drop the Pekko lines: `pelican-jetty` brings Jetty.

```kotlin
val tools = ordersSpec().mcpTools(options)              // descriptions only
val dispatch = ordersApi().mcpDispatch(options)         // descriptions, and callable
dispatch.call("placeOrder", arguments)                  // CompletionStage<ToolResult>

mcpServe(ordersApi(), options)                          // the same, spoken to a client over stdio
```

The values come first because they are the half that has to be right: a tool
list is testable in memory, with no server and no port. [Serving
them](#serving-them) is at the end.

## What becomes what

| In the description | In the tool |
|---|---|
| `operationId`, or the name derived from method and path | `name`: the same string the document and the generated client use |
| `description`, else `summary` | `description` |
| `summary`, where it differs from the description | `title` |
| a path parameter | a required argument, typed by its own codec |
| a query parameter | an argument, in `required` only where the parameter is required; one left out gets the parameter's default |
| a multi-valued query parameter (`commaSeparated`, `repeated`, `pipeSeparated`) | an array argument |
| a refinement such as `between(1, 100)` | `minimum`/`maximum` on the argument: the constraint the server enforces, in the schema the model reads |
| a JSON, form or negotiated body | one required `body` argument, a `$ref` into the `$defs` beside it |
| exactly one JSON success | `outputSchema`, and `structuredContent` on the way back |
| two successes, or a success that is not JSON | no `outputSchema`; the answer is text only |
| a declared failure | a result carrying `isError`, its status and its declared description |
| a header parameter | nothing; see [Credentials](#credentials) |
| `hidden` | not a tool, exactly as it is not an operation |

The body is nested under `body` rather than flattened beside the parameters. A
payload field called `limit` would otherwise collide with the query parameter
of that name. Every schema comes from [`StandaloneSchemas`](schemas.md), so
each pointer resolves inside the tool's own `inputSchema` or `outputSchema`,
and a sealed body names the property that picks its branch.

A scalar argument is accepted as a JSON string, number or boolean and handed to
the parameter's codec as text, so `7` and `"7"` are both a `userId`; the codec
decides whether the value is valid.

## One endpoint, end to end

The description, from `example/src/main/kotlin/example/Endpoints.kt`:

```kotlin
val placeOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders")
    summary = "Place an order"
    operationId = "placeOrder"
    tag("orders")
    json<Order>(status = 201).orFail(badApiKey, noSuchUser, throttled)
}
```

The tool it derives, from the committed
[golden](../example/src/test/resources/golden/mcp-tools.json), abbreviated:

```json
{ "name": "placeOrder",
  "description": "Place an order",
  "inputSchema": {
    "type": "object",
    "properties": {
      "userId": { "type": "integer", "format": "int64", "description": "The user's id" },
      "body": { "$ref": "#/$defs/CreateOrder" } },
    "required": ["userId", "body"],
    "$defs": { "CreateOrder": {
      "type": "object",
      "properties": { "item": {"type": "string"},
                      "quantity": {"type": "integer", "format": "int32"} },
      "required": ["item"] } } },
  "outputSchema": { "$ref": "#/$defs/Order", "$defs": { "Order": { … } } } }
```

There is no `X-Api-Key`, though the endpoint requires one. `quantity` is not
required, because `CreateOrder` gives it a default. The call:

```kotlin
val dispatch = ordersApi().mcpDispatch(
    mcpOptions {
        include = { it.operationId in callable }
        headers = mapOf("X-Api-Key" to System.getenv("ORDERS_API_KEY"))
    },
)

dispatch.call("placeOrder", jsonObj {
    "userId" to 999
    put("body", jsonObj { "item" to "a-widget" })
})
```

The arguments are decoded through the same codecs and refinements an HTTP
request goes through, into the `Params` the endpoint declared, and the handler
that runs is the one the route runs, with the API's filters folded in. What
comes back is a `ToolResult`: the encoded JSON as `text`, the same JSON as
`structuredContent` where the tool published an `outputSchema` and the answer
is a JSON object, and `isError` where the handler returned one of the declared
failures. User 999 does not exist, so the result is the declared 404, as
`McpToolsFromOrdersTest` asserts:

```
404 No user with that id: {"status":404,"error":"No user 999"}
```

The text is the declared status, the declared description, and the error value
as its codec encodes it (the codec leaves a null property out). The status and
the description come from the declaration rather than from the payload, so two
failures sharing a type stay distinct. The `tag` field a tagged failure writes
into an HTTP response body is not added here.

The other things a model can get wrong also come back as a result carrying
`isError` rather than as an exception: an unknown tool name (the text lists the
tools that exist), a missing required argument, and a value a codec or
refinement rejects. Anything else a filter or handler throws propagates out of `call`; how the
server answers that is under [Serving them](#serving-them).

## What is refused

Refused where the tools are derived (by `mcpTools`, `mcpDispatch`, and so when
a server or route is built), not at the call that trips over it. Each refusal
is an `IllegalArgumentException` naming the endpoint and the way past.

| Refused | Why | What to do |
|---|---|---|
| a streamed answer: `ndjson`, `sse`, `jsonArray`, `bytes` | one tool call has one result, and a stream of rows or events has nowhere to go in it | leave it out with `include`, and let callers that can stream have it over HTTP |
| an answer with no JSON rendering: `media<T>("text/csv")`, or a `negotiated(...)` offering none | a tool result is JSON | offer a JSON rendering beside it, `negotiated(json<T>(200), media<T>("text/csv", 200))`, or leave it out with `include` |
| a multipart body, or a raw body | bytes rather than a payload a model could write | leave it out with `include` |
| a cookie parameter | a tool call has no browser behind it | leave it out, or read the value from somewhere a caller without cookies can supply |
| a required header parameter with no value in `mcpOptions { headers = ... }` | a model asked for a credential invents one | supply it with `mcpOptions { headers = ... }`, or leave the endpoint out |
| two included endpoints with one `operationName` (`mcpDispatch`) | a tool name is one name; the second would replace the first | give them `operationId`s of their own |

The default `include` is every endpoint that is not `hidden`, so a service with
streams or uploads has to narrow it. The Orders API in `example/` is that case
on purpose (three streamed answers, a multipart upload and a raw body), and
`McpToolsFromOrdersTest` records which six of its endpoints a model gets.

## Credentials

A header is not a tool argument. Asked for an `Authorization` or an
`X-Api-Key`, a model will produce something shaped like one.

So the value comes from whatever serves the tools, and is sent on every call:

```kotlin
mcpOptions { headers = mapOf("X-Api-Key" to System.getenv("ORDERS_API_KEY")) }
```

The same map supplies a header that is not a credential: a tenant, a
correlation id. A header parameter named in it is decoded by its codec as a
request header would be; an optional header not named in it is absent. An
endpoint that requires a header the map does not name is refused, as above.

## What MCP cannot carry

A tool result is text plus optional structured content. Several things a
Pelican description says do not survive the trip:

- **A response header.** The 429 in the example declares `Retry-After`, and the
  handler supplies it. A tool result has nowhere to put it, so a model is told
  it was throttled and not when to come back. The same goes for a `Location`
  on a 201.
- **A streamed answer**, which is refused rather than truncated.
- **The status code as a status code.** It is in the text of a declared
  failure, and nothing structured carries it.
- **Content negotiation.** A negotiated answer travels as its JSON rendering,
  and the `outputSchema` is that rendering's.

Those are reasons to keep the HTTP API as the interface for callers with those
requirements.

## Serving them

`pelican-mcp-server` speaks JSON-RPC 2.0 at MCP protocol revision
`2025-11-25` (`MCP_PROTOCOL_VERSION`). It answers `initialize`, `tools/list`,
`tools/call` and `ping`; notifications such as `notifications/initialized` are
accepted and get no answer.

- **`initialize`** answers `2025-11-25`, or `2025-06-18` where the client asked
  for that one; a client asking for any other revision is answered with
  `2025-11-25`. `capabilities` is `{"tools": {"listChanged": false}}`, and
  `serverInfo` is the API's `title` and `version`.
- **`tools/list`** answers every included tool as `McpTool.toJson()` writes
  it: `name`, `title` and `description` where present, `inputSchema`, and
  `outputSchema` where there is one. The golden above is that output.
- **`tools/call`** answers `{"content": [{"type": "text", "text": ...}],
  "structuredContent": ..., "isError": ...}`, with `structuredContent` left out
  where the `ToolResult` has none.
- **A failure the handler did not declare.** One the HTTP side would also
  answer with a described status (an `ApiException` thrown by a filter or a
  handler, a decode failure) is a result carrying `isError` and the text
  `<status> <error>`, followed by `: <detail>` where there is one. Anything
  else is a JSON-RPC error `-32603` with the message
  `Internal server error. Reference: <reference>`, and the same reference, the
  endpoint and the throwable go to `Api.onServerError`. The thrown message is
  sent only where the API sets `exposeInternalErrors`. The API's
  `refusals(...)` renderer does not apply: a tool result is the same whichever
  envelope the HTTP side uses.
- **Protocol errors** use the JSON-RPC codes: `-32700` for a message that is
  not JSON, `-32600` for a batch (one message is answered with one), a request
  with no `method`, or a GET over HTTP; `-32601` for any other method,
  including `resources/list` and `prompts/list`; `-32602` for a `tools/call`
  with no `params.name`.

Incoming messages are parsed by the API's own codec (`api.codecs.readTree`),
so its depth and size limits apply to them.

**Over stdio**, which is what a desktop client launches as a subprocess:

```kotlin
import io.github.matthewjones372.pelican.mcp.server.mcpServe

fun main() = mcpServe(ordersApi(), toolOptions)
```

One JSON message per line in, one per line out, until the input ends; blank
lines are skipped. The calling thread is the session: each message is answered
before the next is read. stdout is the transport, so a handler must not print
to it; a `println` is a line the client tries to parse as a message. `input`
and `output` are parameters (defaulting to `System.in` and `System.out`), so a
session over two in-memory streams is a test.

**Over HTTP**, on the port the endpoints are already served from. This is the
request/response half of Streamable HTTP: a POST carrying one JSON-RPC message
is answered `200` with one `application/json` message, or `202` with no body
for a notification. On Pekko:

```kotlin
import io.github.matthewjones372.pelican.pekko.mcp.routeWithMcp
import io.github.matthewjones372.pelican.pekko.start

val server = ordersApi().start(port = 8080) { system -> routeWithMcp(system, toolOptions) }
```

On Jetty, as `example/src/main/kotlin/example/mcp/Main.kt` does:

```kotlin
import io.github.matthewjones372.pelican.jetty.mcp.handlerWithMcp
import io.github.matthewjones372.pelican.jetty.start

val server = ordersApi().start(port = 8080) { handlerWithMcp(options = toolOptions) }
```

`routeWithMcp` and `handlerWithMcp` serve the endpoints and the tools
together, endpoints first. `mcpRoutes(options, path)` (Pekko) and
`mcpHandler(options, path)` (Jetty) are the tools alone. The path defaults to
`MCP_PATH`, `/mcp`, on both. The body is read within the API's
`strictBodyTimeoutMillis`. The server is built when the route or handler is,
so a [refusal](#what-is-refused) stops the service at startup.

The MCP mountings are modules of their own, as the `-docs` modules are: a
service that serves endpoints alone never compiles the protocol in.
`./gradlew :example:runMcp` runs the Orders service with both on one port (on
Jetty), then:

```
curl -s localhost:8080/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### No SDK, and what is not served

The official MCP Kotlin SDK's server half is built on Ktor: `kotlin-sdk-server`
compiles against `ktor-server-core` even for stdio, and its HTTP transport
mounts on a Ktor `Route`. Taking it would put a Ktor server, a Ktor client and
kotlinx.serialization behind `mcpServe` on a Pekko or Jetty service. Core
already has the JSON tree the protocol needs, and each module's
`NoOtherDependenciesTest` asserts its runtime classpath holds nothing else. The
revision is the SDK's `LATEST_PROTOCOL_VERSION` (as of `kotlin-sdk` 0.15.0), so
the number in the handshake is one a client library supports.

- **No resources, prompts, sampling or elicitation.** An endpoint description
  says what a tool is; it does not say what any of those are.
- **No server-initiated stream and no session.** A POST is never answered with
  an event stream, no `Mcp-Session-Id` is issued, and a GET to the endpoint is
  answered `405` with a JSON-RPC error saying what the endpoint does take.
  Other methods are not matched by the MCP route or handler.
- **No auth of its own.** The endpoint is mounted beside the API, and access to
  it is whatever the service puts in front of it. The credential the *tools*
  send is `mcpOptions { headers = ... }`, above.
