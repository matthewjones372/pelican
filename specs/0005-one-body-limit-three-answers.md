# 0005 — One body limit, three answers

## Problem

`Api.maxBodyBytes` says what it is for: "the largest strict body that will be
read; over it is a 413 raised before any codec sees it. Defaulted because an
unbounded body is a way to run a service out of memory with one request."

That is true on Pekko, where the read is bounded — `toStrict(timeout,
maxBodyBytes + DRAIN_OVERRUN_BYTES, system)` — and the refusal is made on
`strict.data.size()`, which is bytes. On the other two it is neither:

```kotlin
val text = req.bodyString()                       // http4k
val text = withTimeout(…) { call.receiveText() }  // ktor
refuseIfOversize(text.length.toLong(), api.maxBodyBytes)
```

`String.length` is UTF-16 code units, so the default 8&nbsp;MB admits roughly
24&nbsp;MB of CJK. And the body is already whole in memory when the check runs:
the `Content-Length` pre-check above it covers the declared case, but a chunked
request declaring no length is read in full and refused afterwards.

The timeout diverges too. Ktor answers 408. Pekko's `toStrict` timeout is not
in `ServerErrors.described`, so it falls through to 500. http4k has none and
inherits the server's, which the code says honestly. `strictBodyTimeoutMillis`
is one `Api` setting, so a reader expects one behaviour.

## Not doing

- No change to Pekko's read, which is the one that is right.
- No change to multipart. `MultipartBody.decode` already spends a byte budget
  per part and drains before refusing, and streamed parts are exempt on
  purpose.
- No new `Api` setting. Two existing ones are being made true, not joined.

## Shape

Nothing in the public API changes. On http4k and Ktor, read bytes with a bound
and decode UTF-8 from the bounded buffer:

```kotlin
// `readAtMost` is already in Multipart.kt, with the drain-then-refuse rule:
//   private inline fun InputStream.readAtMost(limit: Long, tooLarge: () -> Nothing): ByteArray
val bytes = req.body.stream.readAtMost(api.maxBodyBytes) { throw PayloadTooLarge(api.maxBodyBytes) }
values[body] = codecs.body.decode(req.header("Content-Type"), String(bytes, UTF_8))
```

`PayloadTooLarge(limit)` as today, so `described()` still makes it a 413.
Pekko's timeout joins that table as a 408 beside it.

## Why this shape

The alternative is to keep the character count and weaken the KDoc to say the
limit is approximate. That is one line of work and it gives up the thing the
setting exists for, which is bounding memory. Not recommended.

Reading bytes rather than text also removes a decode of the whole body that is
thrown away when the request is refused.

## Stack

- [ ] **`spec-0005-http4k-bounded-body`** — bounded byte read on http4k; refusal on bytes.
      Done when: a chunked body of `maxBodyBytes + 1` bytes made of three-byte characters is a 413, and the process never held the whole body.
- [ ] **`spec-0005-ktor-bounded-body`** — the same on Ktor, off the IO dispatcher as the multipart read already is.
      Done when: as above.
- [ ] **`spec-0005-slow-body-parity`** — Pekko's `toStrict` timeout mapped to 408; the divergence that remains written down.
      Done when: `AllBackendsTest` asserts one status for an oversized chunked body across all three, and `Api.strictBodyTimeoutMillis` says http4k has none.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Should http4k get a body timeout of its own? It answers on the calling
   thread and the server owns the read. Recommend no — document it, as
   `MethodMismatchTest` documents the router differences.
2. `readAtMost` is `private` in `Multipart.kt` and `DRAIN_OVERRUN_BYTES` is
   defined there *and* in `pelican-pekko`'s `Interpreter.kt`. Recommend
   promoting both to `internal` in core and deleting the copy, rather than a
   third.
3. Is 408 right for Pekko, or should a timed-out body be a 400? Recommend 408:
   it is what Ktor already answers and what the status means.
4. Does the drain-before-refusing overrun apply here too? Recommend yes on
   http4k and Ktor, reusing Pekko's 64&nbsp;KB, so a client sees the 413 rather
   than a broken pipe.
