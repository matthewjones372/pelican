# 0045 — The second JSON reader

## Problem

Core hand-writes a JSON reader it never decided to ship. `JsonValue.kt` says
*"Not a general-purpose parser: the configured `Codecs` reads bodies"* and
`docs/reference.md:3713` says nothing else uses it. Five modules use it.
`Forms.kt` needed a codec's output as fields, MCP borrowed it, then the golden
files and the importer did.

One of those callers is on the network. `Protocol.kt:59` parses every inbound
JSON-RPC message with it, while the `Codecs` the `Api` carries sits two
references away, unused. That reader has no recursion bound and no unit test of
its own, and on this path nothing catches what it throws: `Mcp.kt:63-65` runs
`handle` inside the directive, so it never reaches `Interpreter.kt`'s guard;
Pekko's own handler covers `NonFatal`, which excludes `StackOverflowError`; and
the stdio loop at `Server.kt:31-43` has no guard at all. A nested-enough message
ends the session. Ordinary HTTP bodies are safe by accident —
`spi/Requests.kt:99-104` catches `Throwable` and answers 400.

## Not doing

- **The tree and the writer stay hand-written.** A schema is a `JsonObj` in five
  modules, so core has to name the vocabulary, and writing JSON needs no
  library. circe writes its own `Json` ADT for the same reason.
- **Nothing to the encode path.** 0039 measured it and closed it.
- **`parseJson` keeps its signature.** `pelican-core.api:813` pins it; a
  defaulted `maxDepth` parameter replaces the symbol.
- **No kotlinx or jsoniter override.** Main ships one codec. Those are
  `multi-backend` work, and the default is correct until they land.

## Shape

```kotlin
interface CodecFactory {
    fun <T> codec(type: KType): BodyCodec<T>

    /** Core's own reader, so a codec module that does not care keeps compiling. */
    fun readTree(text: String): JsonValue = parseJson(text)
}
```

`pelican-jackson` overrides it with `mapper.readTree(text).toJsonValue()` — that
bridge exists at `JacksonCodecs.kt:133`, private today for swagger schemas.
`Protocol.kt`, `Forms.kt` and `McpDispatch.kt` read through the codec they
already hold. `parseJson` stays for the readers that have none — generated
`schemas.kt`, `Report.kt`, `Golden.kt` — with jackson-core underneath instead of
130 hand-written lines.

Every implementation agrees on three things, asserted in `CodecAgreementTest`:
a number is `Long` where it fits, then `BigInteger`, then `BigDecimal`;
duplicate keys are refused; malformed input is `BodyDecodeFailure`.

## Why this shape

`CodecFactory.codec(type, mediaType)` is the same move three members up — core
supplies the obvious behaviour, a module overrides with its library's. A default
body rather than a second interface: `jvmDefault = NO_COMPATIBILITY` makes it a
real JVM default method, so no implementor breaks and the cost is one `.api`
dump. Returning `JsonValue` rather than the codec's own node type costs an
allocation — two trees where there was one — which on control traffic buys a
parser with `StreamReadConstraints` behind it. Making `codec<JsonValue>()` work
instead needs a custom deserializer per library for a sealed hierarchy: more
work per module, not less.

jackson-core for the fallback, rather than hardening what is there: it is the
streaming layer under databind, no transitive dependencies, already on the
classpath of everyone using `pelican-jackson`. It deletes a class of bugs —
numbers past `Long`, lone surrogates, raw control characters, non-JSON
whitespace, unbounded depth — instead of fixing each by hand. The alternative
considered was bounding the hand-written reader and keeping it, which leaves
core owning a parser nobody chose to own. The cost of taking it is core's
"depends on nothing", and that cost is now accepted — see **Decided**.

## Stack

- [x] **`spec-0045-read-tree`** ([#120](https://github.com/matthewjones372/pelican/pull/120)) — `readTree` defaulting to `parseJson`, the
      Jackson override over a widened `toJsonValue`, and the three callers
      switched.
      Done when: a 10,000-deep JSON-RPC message answers `-32700` over stdio and
      the Pekko mount, because Jackson refused it.
- [x] **`spec-0045-jackson-core`** ([#121](https://github.com/matthewjones372/pelican/pull/121)) — core declares `jackson-core`, `parseJson`
      becomes `JsonFactory`/`JsonParser`, `JsonReader` is deleted, and the four
      places claiming core depends on nothing say what it depends on.
      Done when: `parseJson("[".repeat(10_000))` throws rather than overflowing,
      and `NoThirdPartyDependenciesTest` names the one artifact it permits.
- [x] **`spec-0045-json-num-finite`** ([#122](https://github.com/matthewjones372/pelican/pull/122)) — `JsonNum` refuses non-finite values,
      `Forms.kt` and `OpenApi.kt` move with it, and core gets the JSON tests it
      has never had.
      Done when: a form field `price=NaN` is a 400 rather than a document the
      codec cannot read back.

## Acceptance

```bash
./gradlew build
```

## Decided

Answered by the maintainer in chat, 2026-09-21, and recorded rather than left to
be rediscovered:

- **`pelican-core` takes `jackson-core`.** Entry two happens as written; it does
  not move to a leaf module. The published claim changes with it: core depends on
  the Kotlin standard library *and `jackson-core`*, in `AGENTS.md`,
  `README.md`, `docs/modules.md` and `docs/reference.md`, and
  `NoThirdPartyDependenciesTest` permits exactly that one artifact and keeps
  asserting databind, kotlinx and Pekko are absent.
- **A kotlinx-only user carrying jackson-core is accepted.** It serves the
  fallback alone — their own reads go through `readTree` on their own codec — and
  for anyone on `pelican-jackson`, which is what 1.0 ships, it adds no jar at all.

`AGENTS.md`'s *"A dependency added to core is a build failure, not a judgement
call"* becomes one judgement call, made once, with this as the reason. That
sentence needs rewriting in entry two rather than quietly contradicting itself.

## Open questions

- **Does the extra tree per message matter enough to return the codec's own node
  type instead?** Recommend no: it doubles the seam's surface to save an
  allocation on control traffic.
- **What limits and version does core declare?** Jackson defaults to 1000 deep
  and 20MB strings. Recommend setting `StreamReadConstraints` explicitly and low
  — nothing this reads is deeper than 10 — and pinning 2.22.2 to match
  `pelican-jackson`, letting an app's BOM win.
- **Duplicate keys: refuse, or last-wins?** Jackson takes the last silently,
  kotlinx throws, and `Document.kt:53-60` already refuses them on the YAML side
  into the same importer. Recommend refuse.
- **Does `kotest-property` earn a place in core's test scope?** It is
  `example`-only today, under a comment saying "only here". Recommend yes for
  the round trip; a 20-row table if the dependency is refused.
