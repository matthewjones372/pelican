# 0004 — Naming the mistake at the binder

Supersedes the first draft of this spec, whose shape was tried and does not
work. See **Why this shape**.

## Problem

Binding the wrong handler shape to an endpoint is the first mistake everyone
makes, and Kotlin reports it in terms nobody wrote:

```
// handledNow on an endpoint that declares a failure
e: Return type mismatch: expected 'Fallible<NotFoundFault, Item>', actual 'Item'.
```

`Fallible` is a phantom marker with a private constructor, in no example and in
no user code: its whole existence is to give `Output` a type argument only the
`orFail` binders accept. The one place a user meets it is this message, where it
names neither what to return nor which binder returns it.

`TODO.md` carries this as open with the right reason: an agent compiles, reads
the error and retries, and an error naming neither the endpoint nor the fix
costs the retry loop its convergence.

## Not doing

- **No `@Deprecated(ERROR)` overloads.** That was this spec's first shape and it
  is refuted below.
- No compiler plugin.
- No change to `StreamOf` or `ByteStream`. They mark a handler's *carrier*,
  which core genuinely cannot name; `Fallible` marks a value core names already.
- No change to what any binder does at runtime.

## Shape

Delete the phantom and put the type the handler actually returns in its place:

```kotlin
// core
class FallibleOutput<E, T> internal constructor(…) : Output<Outcome<E, T>>()

// each backend
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledOrFail(
    f: Params.(I) -> Outcome<E, T>,
): ServerEndpoint
```

The message becomes, verbatim from a compile of this tree:

```
e: Return type mismatch: expected 'Outcome<Problem, Item>', actual 'Item'.
```

One error, no ambiguity, no inference cascade behind it — and `Outcome` is a
type the user already writes, because `ok(…)` and `notFound(…)` return one.

## Why this shape

**The first shape was tried and refuted.** A `@Deprecated(ERROR)` overload on
the more specific receiver produces two compiler errors, in order: a platform
declaration clash, fixable with `@JvmName`; and then

```
e: Overload resolution ambiguity between candidates:
fun <I, T : Any> Endpoint<I, T>.handledNow(f: Params.(I) -> T): ServerEndpoint
fun <I, E : Any, T : Any> Endpoint<I, Fallible<E, T>>.handledNow(f: Params.(I) -> T): ServerEndpoint
```

so the mistake gets *worse* and the message never prints. The draft justified
the trick by `FallibleOutput.or`, which does work — but `FallibleOutput<E, T>`
is a genuine subtype of `Output<Fallible<E, T>>`, whereas
`Endpoint<I, Fallible<E, T>>` and `Endpoint<I, T>` are two instantiations of one
invariant class, and neither receiver is more specific.

**The cost, measured rather than guessed.** With `Outcome` in the phantom's
place, `ep handledNow { ok(Item(1)) }` compiles: the total binder becomes a
second spelling of `handledOrFail`. Runtime is identical — both wrap the same
lambda, and the interpreter switches on `out is FallibleOutput`. What is lost is
a claim three `Handlers.kt` files make in comments, "these are the only binders
that fit it", which has to be corrected rather than left to rot.

The alternative is to leave the message as it is and spend the effort on
`docs/`. Not recommended: the error is what a user meets first and a page is
what they read second.

## Stack

- [x] **`spec-0004-outcome-in-place-of-fallible`** — `Fallible` deleted; `FallibleOutput : Output<Outcome<E, T>>`; the binder receivers in three `Handlers.kt`; the three comments corrected; `ApiClient` and `DeclaredFailuresTest`'s annotations.
      Done when: `handledNow` on an endpoint declaring a failure reports `expected 'Outcome<…>'`, `./gradlew build` is green, and no `Fallible` remains outside `FallibleOutput`. Landed in [#36](https://github.com/matthewjones372/pelican/pull/36).
- [x] **`spec-0004-negative-compilation`** — a harness compiling fixture sources through the Kotlin compiler API, asserting each fails with a named substring.
      Done when: the message above is pinned by a test, and changing the phantom back turns it red. Landed in [#76](https://github.com/matthewjones372/pelican/pull/76).

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Is `handledNow` accepting an `Outcome` acceptable, or should it be refused?
   It cannot be refused by the type system for the reason the first shape
   failed. Recommend accepting it and correcting the three comments — the
   behaviour is identical and the better message is worth one more spelling.
2. Does `Outcome`'s covariance (`out E, out T`) widen what a binder accepts now
   that it sits in `Endpoint`'s invariant `R`? Recommend proving it in the first
   entry: an endpoint declaring `Outcome<Fault, Item>` must not bind a handler
   returning `Outcome<Fault, Any>`.
3. `Fallible` is public API. Delete it, or keep it deprecated for a release?
   Recommend deleting: the library is 0.x, it has no instances, and nothing
   outside these signatures could hold one.
4. Does the harness in entry 2 still earn its place with only one message left
   to pin? Recommend yes, but it is the entry to cut first if it does not.
