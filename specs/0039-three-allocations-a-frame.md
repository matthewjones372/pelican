# 0039 — Three allocations a frame

## Problem

Every element of a streamed response is allocated three times on its way to the
socket. `NdjsonOutput.frame` is

```kotlin
fun frame(codec: BodyCodec<T>, value: T): String = codec.encodeToString(value) + "\n"
```

— one String from the codec, a second from the concatenation — and
`Responses.kt` then calls `ByteString.fromString`, which encodes UTF-8 into a
third buffer. `SseOutput.frame` is the same with a `StringBuilder` in front.

The root is `BodyCodec`, which is two String-shaped methods and nothing else.
Jackson can write UTF-8 bytes directly and is never asked to.

Whether this costs anything is unknown. There is no streaming benchmark, and
Jackson's own serialization plausibly dominates a String allocation for any
realistic object. The number is missing, not the analysis.

## Not doing

- **Nothing to the decode path.** Reading a body is a separate question with a
  separate shape (a stream of bytes arriving, not one value leaving).
- **No Pekko type in core.** `ByteString` stays on the backend's side of the
  seam; core writes to `java.io.OutputStream`, which is JDK and costs core's
  no-third-party claim nothing.
- **No new codec module, and no change to `Codecs`.** One method with a default
  body, on the interface that already exists.
- **No decision to ship this in 1.0.** See Open questions.

## Shape

```kotlin
interface BodyCodec<T> {
    fun encodeToString(value: T): String
    fun decodeFromString(text: String): T

    /** The default keeps every codec written against 1.0 working unchanged. */
    fun encodeTo(value: T, out: OutputStream): Unit = out.write(encodeToString(value).toByteArray())
}
```

`pelican-jackson` overrides it with `writeValue(out, value)`. `NdjsonOutput`
and `SseOutput` gain `frameBytes(codec, value): ByteArray`, writing the ASCII
envelope and the payload into one `ByteArrayOutputStream`. `Responses.kt` wraps
that with `ByteString.fromArrayUnsafe`, which copies nothing.

Three allocations per element become one.

## Why this shape

`encodeToBytes(value): ByteArray` is the simpler signature and still allocates
twice for SSE, because the envelope has to be joined to the payload afterwards.
An `OutputStream` lets the framing and the payload share one buffer, which is
the whole point. Recommended.

A default body rather than a second interface: a codec that does not care keeps
compiling, and `Codecs` implementors outside this repository are unaffected.

## Stack

- [x] **`spec-0039-measure`** — a JMH benchmark in `benchmarks/`, no socket:
      `frame` + `ByteString.fromString` against a hand-written bytes path, for
      NDJSON and SSE, at three payload sizes. Reports numbers; changes no
      production code.
      Done when: `./gradlew :benchmarks:jmh` reports both paths, and the
      numbers are written into this spec under a **Measured** heading.
- [ ] ~~**`spec-0039-bytes`**~~ — not happening; see Measured. — conditional on the numbers above: `encodeTo` on
      `BodyCodec`, the Jackson override, `frameBytes` on both streaming
      outputs, `fromArrayUnsafe` in `Responses.kt`, `.api` dumps updated.
      Done when: `./gradlew build` is green and the benchmark shows the win the
      first entry predicted.

## Measured

`FramingBenchmark`, 2026-09-04, JMH on the maintainer's machine, ns/op:

| payload | `ndjson` String | `ndjson` bytes | `sse` String | `sse` bytes |
|---|---|---|---|---|
| 100 B | **142** | 175 | 202 | **180** |
| 1 KB | **678** | 1157 | **775** | 1156 |
| 64 KB | **57,424** | 69,822 | **63,188** | 70,423 |

The String path is faster in five rows of six. The one win — SSE at 100 bytes,
11% — is below the bar. A `ByteArrayOutputStream` variant was slower still, so
the bytes rows above use Jackson's own recycled buffers, which is the best
plausible implementation rather than a strawman.

Allocation moves the other way, from the first run (`-prof gc`, B/op):
896 → 728 for `ndjson` at 100 bytes, and 393,968 → 131,600 at 64 KB; `sse` at
64 KB, 590,976 → 131,684. Real, and largest exactly where the time regression
is also largest.

So the premise was wrong. `writeValueAsString` plus `ByteString.fromString` is
not three naive allocations — Jackson recycles its character buffers and
Pekko's UTF-8 encoder is fast — while `writeValueAsBytes` pays for a second
generator setup and the framing pays for an array copy. Even the small-payload
allocation win is 19%, under the bar on either metric.

**Entry two does not happen.** `BodyCodec` keeps its two methods, 1.0 ships no
new surface for this, and the benchmark stays as the reason.

## Acceptance

```bash
./gradlew :benchmarks:jmh
./gradlew build
```

## Open questions

None — answered by the maintainer in chat, 2026-09-04, and recorded as decided:

- **The benchmark lands regardless**, and the numbers go into this spec under
  **Measured**.
- **The bar is 20%** off the framing path at the small payload. Below it, entry
  two does not happen and this spec closes having bought a number.
- **If the bar is met, entry two lands now** rather than waiting for 1.1. A
  default method is additive, and a candidate is when additive surface should
  arrive — after the freeze it waits for a minor.
- **Payloads: ~100 bytes, ~1KB, ~64KB.**
- **`JsonArrayOutput` is out of scope.** It goes through Pekko's own
  `framingRenderer`, which is a different path.
