# 0076 — kotlinx returns

## Problem

1.0 ships one codec, Jackson. A service whose types are `@Serializable` has to
add Jackson annotations or a second model to use Pelican. `pelican-kotlinx`
was complete at `a6c20b2` and taken off `main` before 1.0; since then core's
codec seam gained `readTree` (spec 0045), `decodeFrom(bytes)` (spec 0046), and
the document gained tagged failures (0066) and oneOf golden checks (0065).

## Not doing

- **jsoniter.** Later, as its own restore.
- **Changes to the codegen.** `CodecAnnotations.KOTLINX` is still on `main`.

## Shape

```kotlin
val api = api(endpoints = routes, codecs = KotlinxCodecs(Json { ignoreUnknownKeys = true }))
```

- `KotlinxCodecs` overrides `readTree` with the configured `Json`, and
  `decodeFrom(bytes)` without a `String` in between.
- `DescriptorSchemas` writes what Jackson's schemas write for tagged failures
  and oneOf.
- `pelican-kotlinx` joins the codec agreement matrix in `PluggableCodecsTest`.

## Why this shape

A restore is code that already passed the matrix; the catch-up is the four
seams that moved. Rewriting it is the alternative and buys nothing.

## Stack

- [ ] **`spec-0076-restore`**: the module from `a6c20b2`, compiling on `main`.
      Done when: its own tests pass.
- [ ] **`spec-0076-seams`**: `readTree`, `decodeFrom(bytes)`.
      Done when: a body is decoded from bytes without a `String`, checked by a test codec.
- [ ] **`spec-0076-matrix`**: tagged failures and oneOf in the schemas; the matrix entry.
      Done when: `PluggableCodecsTest` passes for Jackson and kotlinx.

## Acceptance

```bash
./gradlew build
```

## Decisions

Nothing open.
