# 0004 — Naming the mistake at the binder

## Problem

Binding the wrong handler shape to an endpoint is the first mistake everyone
makes, and Kotlin reports it in terms nobody wrote:

```
// handledNow on an endpoint that declares a failure
e: Return type mismatch: expected 'Fallible<NotFoundFault, Item>', actual 'Item'.

// handledOrFail on an endpoint that declares none
e: Cannot infer type for type parameter 'E'. Specify it explicitly.
e: Cannot infer type for type parameter 'T'. Specify it explicitly.
e: Unresolved reference. None of the following candidates is applicable
   because of a receiver type mismatch:
```

`Fallible` is a phantom marker with a private constructor. It appears in no
example and in no user code, and neither message names `handledOrFail` or
`handledNow`. `TODO.md` already carries this as open, with the right reason: a
coding agent compiles, reads the error and retries, and an error naming neither
the endpoint nor the fix costs the retry loop its convergence.

## Not doing

- No compiler plugin, and no change to `Fallible`, `StreamOf` or `ByteStream`.
  The phantom markers are how a backend binds only the endpoints it can.
- No audit of every message in the library. Construction-time refusals are
  already the standard the rest should meet; this is the binder pair only.
- Not `handledWith`, `handledOneOf`, `handledByOneOf` or `bytesNow`. Three
  pairs, named below, and stop.

## Shape

```kotlin
@Deprecated(
    "This endpoint declares failures with orFail, so its handler returns an " +
        "Outcome. Use handledOrFail { … } and produce ok(value) or one of the " +
        "declared failures.",
    level = DeprecationLevel.ERROR,
)
infix fun <I, E : Any, T : Any> Endpoint<I, Fallible<E, T>>.handledNow(
    f: Params.(I) -> T,
): ServerEndpoint = error("unreachable")
```

Six per backend: `handledNow`/`handledOrFail`, `handledBy`/`handledByOrFail`,
`streamedNow`/`streamedOrFail`, each pair in both directions. Ktor's take
`suspend` lambdas and it has no `handledBy`, so it gets four.

## Why this shape

Kotlin prints a `DeprecationLevel.ERROR` message verbatim, and the more
specific receiver wins overload resolution — the trick `FallibleOutput.or`
already relies on, for the same reason and with the same comment. The
alternative is renaming `Fallible` to something self-describing: cheaper, and
it still would not say what to write instead. Not recommended.

## Stack

- [ ] **`spec-0004-binder-diagnostics`** — the refusing overloads on all three backends.
      Done when: each of the six mistakes compiles to exactly one error, carrying the name of the binder to use instead.
- [ ] **`spec-0004-negative-compilation`** — a harness compiling fixture sources through the Kotlin compiler API and asserting each fails with a named substring.
      Done when: the six messages above are pinned, and deleting one overload turns a test red rather than a doc stale.
- [ ] **`spec-0004-init-error-note`** — one paragraph in `docs/reference.md`: a refused description surfaces as `ExceptionInInitializerError` with a null message and the real one on the cause.
      Done when: the page shows the two-line stack trace and says which half to read.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Does the specific-receiver overload actually win, or does it read as
   ambiguous? `FallibleOutput.or` says it wins. Recommend proving it in the
   first stack entry before writing the other five.
2. Harness: `kotlin-compiler-embeddable`, or shell out to `kotlinc`? Recommend
   embeddable — no toolchain on PATH, and it is test-scoped in one module.
3. Which module owns the harness? Recommend `example`, where the three backends
   are already on the test classpath.
4. Is `error("unreachable")` the right body, or should it be `TODO()`?
   Recommend `error` with the same text, so a reflective call cannot look like
   an unimplemented feature.
