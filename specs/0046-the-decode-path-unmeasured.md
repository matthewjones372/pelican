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
- [ ] **`spec-0046-bytes`** — conditional on the numbers: `decodeFrom`, the
      Jackson override, `Interpreter.kt` handing bytes, `.api` dumps updated.
      Done when: the build is green and the benchmark shows the win the entry
      above predicted.

## Acceptance

```bash
./gradlew :benchmarks:jmh
./gradlew build
```

## Open questions

- **What is the bar?** 0039 used 20% at the small payload. Recommend the same
  number, so a "no" here is as cheap as that one was and the two specs can be
  read against each other.
- **Payload sizes?** Recommend 100B, 1KB, 64KB — 0039's, for the same reason.
- **Does `RequestBodyCodecs.decode(contentType, text: String)` grow a
  byte-shaped sibling, or does the interpreter reach past it?** It is dumped API
  at `pelican-core.api:1594`. Recommend the sibling: reaching past the SPI
  leaves every other backend on the slow path with no way onto the fast one.
- **Does `readStrictBody(InputStream, Long): String` (`Requests.kt:53`) need one
  too?** Recommend yes if entry three happens, so the `multi-backend` branch's
  http4k and Ktor interpreters do not inherit a boundary Pekko no longer has.
- **Does additive surface belong in 1.0 or wait for 1.1?** 0039 answered "now,
  because a candidate is when additive surface should arrive". Recommend the
  same answer unless the freeze has moved.
