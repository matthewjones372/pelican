# 0046 — The decode path, unmeasured

## Problem

0039 asked whether `BodyCodec`'s two String-shaped methods cost anything,
measured the encode half, and closed it: the String path won five of six rows,
because Jackson recycles its character buffers and Pekko's UTF-8 encoder is
fast. It excluded the other half by name — *"Nothing to the decode path.
Reading a body is a separate question with a separate shape."*

That question was never asked. `Interpreter.kt:468` calls
`strict.data.utf8String()`, materialising the whole body as UTF-16, and
`JacksonCodecs.kt:63` then calls `mapper.readValue(text, javaType)` — so Jackson
runs its char-based `ReaderBasedJsonParser` over a fresh copy rather than
`UTF8StreamJsonParser` over the bytes Pekko already had. The recycled-buffer
argument that sank the encode case does not obviously apply here.

Separately and regardless of any of that: the encode side caches an
`ObjectWriter` at `JacksonCodecs.kt:60` and the decode side caches nothing, so
every request repeats a root-deserializer lookup and builds a fresh
`DeserializationConfig`. That asymmetry looks like an oversight rather than a
decision.

## Not doing

- **Nothing to the encode path.** 0039 measured it and closed it. This does not
  reopen it.
- **No Pekko type in core.** A byte-shaped member takes `ByteArray`, which is
  JDK and costs core's dependency claim nothing.
- **No new codec module and no change to `Codecs`.**
- **No streaming-decode change.** `NdjsonFrames` materialises a String per frame
  at `Frames.kt:97`; that is a third question and waits for this answer.

## Shape

```kotlin
interface BodyCodec<T> {
    fun encodeToString(value: T): String
    fun decodeFromString(text: String): T

    /** The default keeps every codec written against 1.0 working unchanged. */
    fun decodeFrom(bytes: ByteArray): T = decodeFromString(String(bytes, StandardCharsets.UTF_8))
}
```

`pelican-jackson` overrides it with a cached `ObjectReader`; `Interpreter.kt`
hands `strict.data.toArrayUnsafe()` instead of `utf8String()`.

## Why this shape

`ByteArray` rather than `InputStream`: the strict path already holds the whole
body and applied its size limit at `Interpreter.kt:463`, so a stream is a
wrapper around an array that exists. 0039 chose `OutputStream` for the opposite
reason — framing wanted the envelope and the payload in one buffer — and the
shapes differ because the directions do.

A default body rather than a second interface, matching
`CodecFactory.codec(type, mediaType)`. `jvmDefault = NO_COMPATIBILITY` makes it
a real JVM default method, so an implementor compiled against 1.0 keeps working.

The stack is measure-first because 0039's was, and because 0039 is the reason:
an argument from the code path predicted three naive allocations and the
benchmark found recycled buffers. The same argument is being made again here,
about a different half, and it is worth no more than it was worth then.

## Stack

- [ ] **`spec-0046-reader-cache`** — cache an `ObjectReader` beside the
      `ObjectWriter` already there. No SPI change, no `.api` churn, and it lands
      whatever the benchmark says.
      Done when: `codec()` builds a writer and a reader once, at `Api` assembly.
- [ ] **`spec-0046-measure`** — `DecodeBenchmark` in `benchmarks/`:
      `utf8String()` + `readValue(String)` against `readValue(ByteArray)`, three
      payload sizes, with `-prof gc`. Changes no production code.
      Done when: `./gradlew :benchmarks:jmh` reports both paths and the numbers
      are written into this spec under a **Measured** heading.
- [x] **`spec-0046-bytes`** — `decodeFrom` on `BodyCodec`, the Jackson
      override, a byte-shaped `RequestBodyCodecs.decode` beside the String one,
      `Interpreter.kt` handing `toArrayUnsafe`, `.api` dumps updated.
      Done when: the build is green and the benchmark shows the win the entry
      above predicted.

## Measured

`DecodeBenchmark`, 2026-09-21, JMH in a container, 3 forks × 15 iterations,
`-prof gc`.

Time, ns/op:

| payload | `viaString` | `viaBytes` | bytes faster by |
|---|---|---|---|
| 100 B | 774.4 ± 42.0 | **629.5 ± 31.8** | 18.7% |
| 1 KB | 1863.9 ± 66.0 | **1449.3 ± 40.6** | 22.2% |
| 64 KB | 198,672 ± 4,835 | **106,545 ± 5,125** | 46.4% |

Allocation, `gc.alloc.rate.norm`, B/op:

| payload | `viaString` | `viaBytes` | |
|---|---|---|---|
| 100 B | 1,632 | **1,552** | 4.9% better |
| 1 KB | 3,480 | **2,480** | 28.7% better |
| 64 KB | **132,545** | 263,737 | 99% worse |

**The bytes path is faster at every size**, which is the opposite of what 0039
found on the encode side — there is no recycled-buffer counterpart here to the
one that made `writeValueAsString` hard to beat, and skipping the UTF-16
materialisation is work genuinely not done.

**The small-payload row misses the stated 20% bar, at 18.7% — and straddles
it.** The error bars are wide enough to matter: worst case for bytes the gap is
9.7%, best case 26.8%. The 1 KB and 64 KB rows clear the bar outside their error
bars, so the bar is met at every size except the one the bar was written about.

**Allocation regresses at 64 KB, and the copy is not the reason.** A first run
had `viaBytes` call `ByteString.toArray()`; that copy was removed in favour of
`toArrayUnsafe` and the row improved on both axes at 100 B and 1 KB — but 64 KB
barely moved, 329,314 → 263,737 against the String path's 132,545. What is left
is Jackson's UTF-8 parser buffering a 64 KB string field, not anything the
interpreter would control.

So the numbers do not answer the question the way 0039's did. There the String
path won outright and entry two died cleanly. Here time favours bytes
everywhere, allocation favours bytes below about a kilobyte and loses badly
above it, and the one row the bar names sits on the line.

## Acceptance

```bash
./gradlew :benchmarks:jmh
./gradlew build
```

## Open questions

- **What is the bar?** Answered, and then outgrown. 20% at the small payload was
  the number; 18.7% was the measurement, inside error bars spanning 9.7% to
  26.8%, with both larger payloads clearing it outright. Decided by the
  maintainer in chat, 2026-09-21: **entry three happens**, because time favours
  the bytes path at every size and the row that misses is the one where the
  error swamps the margin. The 64 KB allocation regression is a known cost, and
  it is Jackson's buffering rather than anything the interpreter chooses.
- **Payload sizes?** Recommend 100B, 1KB, 64KB — 0039's, for the same reason.
- **Does `RequestBodyCodecs.decode(contentType, text: String)` grow a
  byte-shaped sibling, or does the interpreter reach past it?** The sibling,
  built as recommended: reaching past the SPI would leave every other backend on
  the slow path with no way onto the fast one.
- **Does `readStrictBody(InputStream, Long): String` (`Requests.kt:53`) need one
  too?** Not in this entry. Its only caller on `main` is the in-memory test
  transport, whose speed is not what 0046 is about, and the http4k and Ktor
  interpreters that would want it are on the `multi-backend` branch rather than
  here. Adding public API to core for a caller that does not exist yet is the
  surface spec 0044 argues against; it lands with the backend that needs it.
- **Does additive surface belong in 1.0 or wait for 1.1?** 0039 answered "now,
  because a candidate is when additive surface should arrive". Recommend the
  same answer unless the freeze has moved.
