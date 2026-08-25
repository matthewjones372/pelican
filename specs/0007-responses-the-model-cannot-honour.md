# 0007 — Responses the model permits and cannot honour

## Problem

Two shapes type-check and cannot be served.

**A failure the endpoint never declared.** `Outcome` is `Outcome<out E, out T>`
and `orFail(vararg failures: ErrorOutput<out E>)` infers `E` to the failures'
common supertype. Declare two failures from one sealed hierarchy and `E` widens
to the hierarchy, after which every other `ErrorOutput` of that hierarchy fits:

```kotlin
val ep = endpoint(orgId, projectId, itemId) {
    delete("orgs" / orgId / "projects" / projectId / "two" / itemId)
    empty(204).orFail(missing, conflicted)              // E = Fault
}
ep handledOrFail { (_, _, _) -> throttled(ThrottledFault(30L), retryAfter of 30L) }
// compiles; 500 {"error":"Internal server error","detail":"Reference: 558b8c2f2331"}
```

`failureResponse`'s `check` fires, the interpreter's `exceptionally` catches it,
and the message reaches the log while the caller gets a reference. `example/shop`
has exactly this shape: `ShopError.declared()` is shared between `quoteBasket`,
which declares two of three, and `placeOrder`, which declares three.

**A byte stream that may fail.** `bytes() orFail gone` builds a
`FallibleOutput<E, ByteStream>`, validates, and emits a correct document with a
200 `application/octet-stream` and a 410. No backend has a binder for it —
`streamedOrFail` takes `Fallible<E, StreamOf<T>>` and `bytesNow` takes a bare
`ByteStream` — so the endpoint cannot be served at all.

## Not doing

- No per-endpoint failure-set type, and no arity of `orFail`. Manufacturing a
  type per declared set is the type-level machinery this library exists without.
- No invariance on `E`. Covariance is what lets `E` infer to a sealed supertype,
  which is what makes the handler's `when` exhaustive — the feature, not the bug.
- No change to `successNamedBy`, which already refuses an undeclared *success*
  by the same mechanism and reads well when it does.

## Shape

The runtime refusal keeps happening and stops being anonymous:

```kotlin
class UndeclaredResponse(message: String) : RuntimeException(message)

// described(): ApiError(500, "Undeclared response", t.message)
// -> {"status":500,"error":"Undeclared response",
//     "detail":"error:429 was returned by a handler but responses(empty:204,
//               error:404, error:409) never declared it"}
```

and the missing binder is written:

```kotlin
infix fun <I, E : Any> Endpoint<I, Fallible<E, ByteStream>>.bytesOrFail(
    f: Params.(I) -> Outcome<E, Source<ByteString, NotUsed>>,
): ServerEndpoint
```

## Why this shape

For the failure hole, the choice is between a legible 500 and a construction-time
refusal, and there is no construction-time refusal available: nothing at build
time knows what a handler will return. So the honest move is to make the one
failure mode readable — it is a bug in the service, and the person debugging it
should not need the log.

For the byte stream, the alternative is refusing `ByteStream` with `orFail` in
`FallibleOutput`'s `init`, which is one line and no binder. Not recommended: a
byte stream that 404s before its first byte is an ordinary requirement, and
`streamedOrFail` already proves the shape works.

## Stack

- [x] **`spec-0007-undeclared-response`** — `UndeclaredResponse`, thrown by both checks, in `described()`; the three interpreters unchanged otherwise.
      Done when: returning an undeclared failure answers a 500 whose body names the failure and the declared set, and `AllBackendsTest` says the same on all three. Landed in [#43](https://github.com/matthewjones372/pelican/pull/43).
- [x] **`spec-0007-bytes-or-fail`** — `bytesOrFail` on Pekko, http4k and Ktor.
      Done when: `bytes() orFail gone` binds, streams on success, and answers the declared 410 with its JSON body. Landed in [#45](https://github.com/matthewjones372/pelican/pull/45).
- [x] **`spec-0007-docs`** — the trade-off under `orFail` in `docs/reference.md`.
      Done when: the page says declaring two failures over a sealed supertype widens `E`, what that stops the compiler catching, and that a single failure pins it exactly. Landed in [#48](https://github.com/matthewjones372/pelican/pull/48).

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Does naming the declared set in a 500 body leak anything? It names statuses
   the document already publishes. Recommend always; put the `toString` of the
   output in, not the payload.
2. Should `UndeclaredResponse` still reach `onServerError`? Recommend yes — it
   is a bug, and the hook is where a service reports bugs.
3. Should `exposeInternalErrors` gate the detail? Recommend no. The whole point
   is that it is readable in production, where this is discovered.
