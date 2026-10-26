# 0091 — A test client that reads bytes

## Problem

`ResponseSpec.body` is a `String`, so every transport decodes the response to
UTF-16 for a codec that could read the bytes: `JacksonCodecs` and
`KotlinxCodecs` both override `decodeFrom(ByteArray)` (spec 0046). Petshop
load-tests through `ApiClient(JettyTransport, ShopCodecs)` at 50k req/s, and in
its JFR profile `getContentAsString` is 13% of the 11 KB allocated per request.
`ApiClient` also calls `codecs.codec(type)` per response, which for Jackson
builds a reader and a writer each time.

JMH `-prof gc`, JDK 25, `JacksonCodecs`, a `List<Pet>` of 10 (~1 KB).
`gc.alloc.rate.norm` counts every thread, the in-process server included:

| Row | B/op |
|---|---|
| Jetty 12 client, body not read (floor) | 8,221 |
| Petshop's shape: `getContentAsString` → `ResponseSpec` → `call` | 21,602 |
| Same exchange, `getContent()` → `decodeFrom`, held codec | 20,122 |
| `pelican-client-jetty` → `readAllBytes` → `decodeFrom` | 36,805 |
| In process: new `String` per call → `call` | 13,100 |
| In process: `request` + `decodeFrom(bytes)`, held codec | 11,580 |
| In process: the same, codec looked up per call | 12,156 |

The `String` is ~1.1 KB here (~0.12 KB for one pet), the codec lookup ~0.55 KB
at any size, `request()` 0.48 KB. `pelican-client-jetty` costs ~15 KB *more*
than `ContentResponse`, so adopting it would make this worse.

## Not doing

- **A `ByteBuffer` body.** Skipping Jetty's `toArray` (12% in the profile) needs
  `decodeFrom(ByteBuffer)` on `BodyCodec`, a core change of its own.
- **An `InputStream` body.** A `ResponseSpec` is read many times.
- **Lighter headers, `RequestSpec`, `pelican-client-jetty`'s cost.** Small, not
  in the profile, or a spec of its own respectively.
- **The GC pauses** (Proofload spec 0146).

## Shape

```kotlin
class ResponseSpec /* holds whichever form it was given */ {
    constructor(status: Int, headers: List<Pair<String, String>>, body: String)     // today's
    constructor(status: Int, headers: List<Pair<String, String>>, bytes: ByteArray) // new, not copied
    val body: String      // decoded once, on first read, if built from bytes
    val bytes: ByteArray  // encoded once, on first read, if built from a String
}
// Petshop's migration: res.content in place of res.contentAsString.
```

`ApiClient` decodes JSON and media bodies from whichever form the response
holds, never converting one to the other just to decode it. Text, the tag
lookup, ndjson and SSE read `body`. The JDK and in-memory transports move to
the bytes constructor.

## Why this shape

**A.** Hold either form and keep both accessors (recommended). It is source-
and binary-compatible, and a transport opts in with one argument.
**B.** Make `body` a `ByteArray` or a `Body` type. That breaks every caller and
every `body shouldBe "..."`, to save the same bytes.
**C.** Have `ApiClient` take a `ClientTransport`. Ruled out by the table until
`pelican-client-jetty` is cheaper.
Apart from A: resolve each codec once in `ApiClient`. No caller sees it.

Expected for petshop: the String's 13%, plus up to ~0.55 KB from the codec
cache if `ShopCodecs` does not already cache.

## Stack

- [x] **`spec-0091-benchmark`** ([#355](https://github.com/matthewjones372/pelican/pull/355)):
      `ClientAllocationBenchmark` in `:benchmarks`.
      Done when: `./gradlew :benchmarks:jmh -PbenchmarkArgs=ClientAllocation` gives the table.
- [x] **`spec-0091-codec-once`** ([#356](https://github.com/matthewjones372/pelican/pull/356)):
      `ApiClient` resolves a codec once per type and media type.
      Done when: the `inMemoryCall` rows fall by the per-call codec row's excess over the held-codec row.
- [x] **`spec-0091-bytes`** ([#357](https://github.com/matthewjones372/pelican/pull/357)):
      `ResponseSpec(bytes)`, decoding from it, the transports, `.api`, docs, changelog.
      Done when: a bytes-built response decodes without reading `body`.

## Acceptance

```bash
./gradlew build
./gradlew :benchmarks:jmh -PbenchmarkArgs=ClientAllocation
```

## Decisions

- The codec cache lives in `ApiClient`, in a `ConcurrentHashMap`. That is not
  an accumulator `FunctionalStyleTest` matches, so it needs no entry.
- The array is not copied on construction, said in one line of KDoc, as
  `encoded` does (spec 0086).
- A `decodeFrom(ByteBuffer)` spec waits until petshop re-profiles with this in.
