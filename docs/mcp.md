# Tools a model can call

Linked from the [README](../README.md). The endpoints a service already
describes, derived into MCP tool descriptions and a dispatch that runs them —
the same values the routes and the OpenAPI document come from.

---

## What it is for

The MCP server that lets a model call a service is normally written by hand
against the SDK: `inputSchema` as a `buildJsonObject` literal, then
`request.arguments?.get("qty")?.jsonPrimitive` in the handler. Schema in one
place, decoding in another, kept in step by whoever remembers — about thirty
lines per tool, none of it checked against the service.

Everything those thirty lines say is already written down. The endpoint knows
its name, its arguments, their types, the constraints it enforces and the
shapes it answers with.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:pelican-mcp:$pelicanVersion")         // the tools as values
    implementation("io.github.matthewjones372:pelican-mcp-server:$pelicanVersion")  // and spoken
    implementation("io.github.matthewjones372:pelican-pekko-mcp:$pelicanVersion")   // over HTTP, on your backend
}
```

Core and [`pelican-schema`](schemas.md) for the first, and no MCP SDK for
either: a tool list is a value, and the protocol is JSON-RPC over lines of
text. `pelican-mcp` has been on Maven Central since 0.2.0; the two serving
modules are new since then and first release with 1.0 — `1.0.0-RC1` carries
all three.

```kotlin
val tools = ordersSpec().mcpTools(options)              // descriptions only
val dispatch = ordersApi().mcpDispatch(options)         // descriptions, and callable
dispatch.call("placeOrder", arguments)                  // CompletionStage<ToolResult>

mcpServe(ordersApi(), options)                          // and the same, spoken to a client
```

The values come first because they are the half that has to be right: a tool
list is testable in memory, with no server and no port. [Serving
them](#serving-them) is at the end.

## What becomes what

| In the description | In the tool |
|---|---|
| `operationId`, or the name derived from method and path | `name` — the same string the document and the generated client use |
| `description`, else `summary` | `description` |
| `summary`, where it says something the description does not | `title` |
| a path parameter | a required argument, typed by its own codec |
| a query parameter | an argument: required where the parameter is, and left out where it has a default |
| a multi-valued query parameter — `commaSeparated`, `repeated`, `pipeSeparated` | an array argument |
| a refinement — `between(1, 100)`, `nonEmpty()` | `minimum`/`maximum`, `minLength` — the constraint the server enforces, in the schema the model reads |
| a JSON, form or negotiated body | one `body` argument, a `$ref` into the `$defs` beside it |
| one JSON success | `outputSchema`, and `structuredContent` on the way back |
| two successes, or an answer that is not JSON | no `outputSchema`; the answer is text |
| a declared failure | a result carrying `isError`, its status and its declared description |
| a header parameter | nothing — see [Credentials](#credentials) |
| `hidden` | not a tool, exactly as it is not an operation |

The body is nested under `body` rather than flattened beside the parameters. A
payload field called `limit` would otherwise collide with the query parameter
of that name, and one argument that is plainly the payload is easier to fill
than a flat bag that is sometimes one thing and sometimes the other.

## One endpoint, end to end

The description, which is the one in `example/` and is not written for this:

```kotlin
val placeOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders")
    summary = "Place an order"
    operationId = "placeOrder"
    json<Order>(status = 201).orFail(badApiKey, noSuchUser, throttled)
}
```

The tool it derives — the committed
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

No `X-Api-Key`, though the endpoint requires one. `quantity` is not required,
because `CreateOrder` gives it a default. The call:

```kotlin
val dispatch = ordersApi().mcpDispatch(
    mcpOptions {
        include = { it.operationId in callable }
        headers = mapOf("X-Api-Key" to System.getenv("ORDERS_API_KEY"))
    },
)

dispatch.call("placeOrder", jsonObj {
    "userId" to 7
    put("body", jsonObj { "item" to "a-widget"; "quantity" to 3 })
})
```

The arguments are decoded through the same codecs and refinements an HTTP
request goes through, into the `Params` the endpoint declared, and the handler
that runs is the one the route runs, with the API's filters folded in. What
comes back is a `ToolResult`: the encoded JSON as text, the same JSON as
`structuredContent` because this tool published an `outputSchema`, and
`isError` where the handler returned one of the three declared failures.

```
404 No user with that id: {"status":404,"error":"No user 999","detail":null}
```

The status and the description come from the declaration rather than from the
payload, so two failures sharing a type stay distinct — and the description is
the sentence somebody wrote for exactly this moment.

A model can read that and try again, which is why a declared failure is a
result rather than an exception. What nobody declared still throws: a bug in a
handler is not an answer to give a model.

## What is refused

Refused where the tools are derived, not at the call that trips over it. Each
refusal names the endpoint and the way past.

| Refused | Why | What to do |
|---|---|---|
| a streamed answer — `ndjson`, `sse`, `jsonArray`, `bytes` | one tool call has one result, and a stream of rows or events has nowhere to go in it | leave it out with `include`, and let callers that can stream have it over HTTP |
| an answer with no JSON rendering — `media<T>("text/csv")`, or a `negotiated(...)` offering none | a tool result is JSON, and there is nothing for another rendering to travel in | offer a JSON rendering beside it — `negotiated(json<T>(200), media<T>("text/csv", 200))` — or leave it out with `include` |
| a multipart body, or a raw body | bytes rather than a payload a model could write | leave it out with `include` |
| a cookie parameter | a tool call has no browser behind it | leave it out, or read the value from somewhere a caller without cookies can supply |
| a required header parameter with no value behind it | a model asked for a credential invents one | supply it with `mcpOptions { headers = ... }`, or leave the endpoint out |
| two endpoints with one `operationName` | a tool name is one name; the second would replace the first | give them `operationId`s of their own |

Refused rather than quietly dropped, so that an endpoint a model does not get
is a decision somebody made. The Orders API in `example/` is the awkward case
on purpose — three streamed answers, a multipart upload and a raw body — and
its test records which six of its endpoints a model gets and which it does not.

## Credentials

A header is not a tool argument. Asked for an `Authorization` or an
`X-Api-Key`, a model will produce something shaped like one, and a service that
accepts it has a hole rather than a tool.

So the credential comes from whatever serves the tools:

```kotlin
mcpOptions { headers = mapOf("X-Api-Key" to System.getenv("ORDERS_API_KEY")) }
```

which is the same place a header that is not a credential comes from — a
tenant, a correlation id, a feature flag. An endpoint that requires a header
nobody supplied is refused rather than served with a value invented for it.

## What MCP cannot carry

A tool result is text plus optional structured content. Several things a
Pelican description says faithfully do not survive the trip, and it is worth
knowing which before pointing a model at a service.

- **A response header.** The 429 in the example declares `Retry-After`, and the
  handler supplies it: a caller knows how many seconds to wait. A tool result
  has nowhere to put it, so a model is told it was throttled and not when to
  come back. The same goes for the `Location` on the 201 — the order was
  placed, and where it lives is in the payload or nowhere.
- **A streamed answer**, which is refused rather than truncated.
- **The status code as a status code.** It is in the text of a declared
  failure, because that is where a model reads it, and nothing structured
  carries it.
- **Content negotiation.** One tool, one shape; an endpoint that answers
  several media types answers a tool call with the payload its codec writes.

None of these is a reason not to publish tools. They are the reasons to keep
the HTTP API the thing callers with real requirements use, which it is.

## Serving them

`pelican-mcp-server` is the half that speaks: JSON-RPC 2.0, protocol revision
`2025-11-25` — `initialize`, `notifications/initialized`, `tools/list`,
`tools/call` and `ping`, and nothing else.

**Over stdio**, which is what a desktop client launches as a subprocess:

```kotlin
fun main() = mcpServe(ordersApi(), toolOptions)
```

One JSON message per line in, one per line out, until the input ends. stdout is
the transport, so anything a handler prints goes to stderr — a `println` is a
line the client tries to parse as a message.

**Over HTTP**, on the port the endpoints are already served from:

```kotlin
// Pekko
api.routeWithMcp(system, toolOptions)
```

`pelican-pekko-mcp` and the mountings on the [`multi-backend`](https://github.com/matthewjones372/pelican/tree/multi-backend) branch are the same
split the `-docs` modules make, for the same reason: a service that serves
endpoints alone never compiles the protocol in. `example.mcp` is the Orders
service with both on one port — `./gradlew :example:runMcp`, then:

```
curl -s localhost:8080/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### No SDK, and what is not served

The official MCP Kotlin SDK resolves and is current, and its server half is
Ktor's: `Route.mcp`, `mcpStreamableHttp`, an SSE transport over
`ApplicationCall`, and `kotlin-sdk-server` compiling against `ktor-server-core`
even for stdio. Taking it would put a Ktor server, a Ktor client,
kotlinx.serialization and a logging facade behind `mcpServe` on a service that
runs Pekko, and would leave the HTTP half mountable on Ktor alone.
The protocol here is JSON-RPC over lines of text, and core already has the JSON
tree to speak it with. The revision is pinned to that SDK's
`LATEST_PROTOCOL_VERSION`, so the number in the handshake is one a client
library on the other end supports.

- **No resources, prompts, sampling or elicitation.** An endpoint description
  says what a tool is; it does not say what any of those are.
- **No server-initiated event stream and no session.** A GET to the endpoint is
  answered `405`, which is what the specification names for a server with no
  stream to open. A tools-only server has nothing to push — a tool call has one
  result, which is the same reason a streamed endpoint is not a tool.
- **No auth of its own.** The endpoint is mounted alongside the API and
  inherits whatever the service already does; the credential the *tools* send
  is `mcpOptions { headers = ... }`, above.
