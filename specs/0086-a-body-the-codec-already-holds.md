# 0086 — A body the codec already holds

## Problem

Jetty turns a finished JSON or media body into bytes with
`encodeToString(value).toByteArray()`, and Pekko hands that string to
`HttpEntities.create` or `ByteString.fromString`. A service that already holds
the bytes still pays for a copy on every response. Petshop is that service:
`GET /pets` reuses one catalogue string, and Jetty copies it per request.

This is not 0039, 0046, or 0085. 0039 measured encoding a fresh value to an
`OutputStream` and closed it. 0046 added `decodeFrom` and left encode alone.
0085 had Jackson and kotlinx encode straight to bytes and dropped it. This
lets a codec return an array it already built.

## Not doing

- **No override in `JacksonCodecs` or `KotlinxCodecs`.** Null from both.
- **No new dependency, and no Pekko type in core.**
- **Text, empty, byte stream, ndjson, SSE, a JSON array, and a declared failure.**
  A failure is `taggedBody` around `encodeToString`.
- **The in-memory server.** It keeps `encodeToString`.
- **Request bodies, the generated client, and a number in `docs/what-it-costs.md`.**
- **Petshop.** It consumes the snapshot after this is published.
- **A wrapper around `fromArrayUnsafe`, or a private copy cached by identity.**

## Shape

```kotlin
/** Bytes this codec already holds, or null to encode as today. The server does not modify the array. */
fun encoded(value: T): ByteArray? = null
```

Null is today's path. An array is the body for that value, UTF-8 where the
body is JSON. The caller may keep it and share it. Each response gets its own
buffer over it. An empty array is an empty body.

Jetty stores the array on the `Strict` reply. `ByteBuffer.wrap` does not copy.
Pekko keeps it with `ByteString.fromArrayUnsafe`. `fromArray` and
`HttpEntities.create(ContentType, byte[])` clone, so neither is used.
`HttpEntity.Strict` stores that ByteString. The write copies bytes out into
the socket buffer and does not write them back; `asByteBuffer` is read-only.
Each response builds its own ByteString, so two sends do not share an iterator.

Read only when the output about to be written is a `JsonOutput` or a
`MediaOutput`, including one reached as a declared success or by negotiation.
`pelican-core.api` gains the method. A JVM default, as `decodeFrom` is.

## Why this shape

A nullable default, rather than a method the stock codecs override. An
override that encodes on the spot is 0085, measured and dropped. A codec with
nothing cached has no bytes to return, and null leaves today's path as it is.

`fromArray` is safe by copying, and Pekko then gains nothing.
`fromArrayUnsafe` is safe here because the send path only reads the array,
and `encoded` already says the caller will not write it either. Same rule
Jetty's wrap relies on.

The server does not check the array against `encodeToString`. Bytes back
means those bytes are the body.

## Stack

- [x] **`spec-0086-encoded`** ([#333](https://github.com/matthewjones372/pelican/pull/333)):
      `encoded` on `BodyCodec`, Jetty wraps it, Pekko retains it with
      `fromArrayUnsafe`, the `.api` dump, and the one-line `BodyCodec` sketch
      in `docs/reference.md`.
      Done when: the checks below pass and `./gradlew build` is green.

## Acceptance

```bash
./gradlew build
```

- The default returns null, and a JSON response is then the UTF-8 of
  `encodeToString`, on both Jetty and Pekko.
- A codec whose `encoded` returns a fixed array that is not the UTF-8 of its
  `encodeToString` is what both servers put on the wire. After the response,
  that array's bytes are unchanged.
- The OpenAPI document for an endpoint declared as JSON is unchanged.
  Generating it does not call `encoded`.

## Decisions

- The fixed-array proof covers one media response as well as JSON.
- One changelog line under Unreleased / Added.
