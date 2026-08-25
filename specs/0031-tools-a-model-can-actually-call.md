# 0031 — Tools a model can actually call

*Post-1.0.*

## Problem

pelican-mcp derives `McpTool` values from descriptions and dispatches calls
through the real codecs, filters, and handlers (`McpTools.kt:84-94`,
`McpDispatch.kt:55-80`) — and stops there. No SDK, no stdio, no Streamable
HTTP transport; the docs say so plainly (`reference.md:746-749`,
`roadmap.md:307-311`). A team that wants its API callable by a model must
write the server loop themselves against the value surface. The descriptions
half shipped in 1.0; this is the serving half.

## Not doing

- **No MCP SDK dependency in pelican-mcp.** The value surface stays
  dependency-clean (`NoOtherDependenciesTest.kt:19`); serving is a new leaf
  module, `pelican-mcp-server`.
- **No resources, prompts, sampling, or elicitation.** Tools only — the part
  the endpoint model actually describes.
- **No auth invention.** The HTTP transport mounts alongside the API and
  inherits whatever the service already does; MCP-level auth waits for a
  real user.

## Shape

```kotlin
// stdio, for local use
mcpServe(api, options = mcpOptions { })

// or mounted on the service the API already runs, per backend
routes + mcpRoutes(api)   // Streamable HTTP on /mcp, like docsRoutes on /docs
```

`pelican-mcp-server` speaks the protocol over stdio; `mcpRoutes` follows the
`pelican-*-docs` pattern per backend for Streamable HTTP. Both drive the
existing `McpDispatch`, so a tool call is the same execution path as an HTTP
request — filters, refusals, metrics included.

## Why this shape

The docs-serving modules already solved "mount a sidecar on the endpoint the
service runs"; reusing that pattern means the server halves are thin and the
protocol lives in one module. The alternative — depending on the official
MCP Kotlin SDK — trades control for maintenance; recommend evaluating it in
the first PR and hand-rolling only if the SDK forces a transport shape the
docs-pattern cannot mount (open question 1).

## Stack

- [ ] **`spec-0031-protocol-stdio`** — `pelican-mcp-server`: initialize,
      tools/list, tools/call over stdio against `McpDispatch`.
      Done when: an MCP inspector session lists and calls a tool end-to-end.
- [ ] **`spec-0031-streamable-http`** — `mcpRoutes(api)` per backend, the
      docs-module pattern.
      Done when: the example service serves `/mcp` on all three backends with one suite green.
- [ ] **`spec-0031-docs`** — reference section, README label flipped from
      "descriptions half" to served, CHANGELOG.
      Done when: the 0025 label is updated rather than contradicted.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Official MCP Kotlin SDK or hand-rolled protocol? Recommend: spike the SDK
   first; adopt it in `pelican-mcp-server` only (never the value module),
   hand-roll if it cannot mount as plain routes.
2. Which protocol revision to pin? Recommend the newest the inspector
   supports at build time, named in the module KDoc.
3. Do streamed endpoints stay refused as tools? Recommend yes — the refusal
   list in `McpTools.kt:158-165` is correct until MCP has a streaming story
   worth modelling.
