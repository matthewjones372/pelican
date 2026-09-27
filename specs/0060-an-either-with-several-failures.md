# 0060 — An Either with several declared failures

## Problem

`pelican-arrow` (spec 0036) converts an `Either` into an `Outcome` in one call only when the endpoint declares a
single failure: `toOutcome()`, or `toOutcome(failure)` naming it. When the `Left` can become one of several
declarations, which is the usual case for a domain error with a sealed supertype, the module offers nothing. Spec
0036 and the Arrow example both answer "write a `fold`", so every handler of that shape spells out the same
four lines, with `ok` on the right each time:

```kotlin
Billing.subscribe(signup).fold(
    { error -> when (error) { is NoSuchPlan -> planMissing(...); is AlreadySubscribed -> alreadySubscribed(...) } },
    { subscription -> ok(subscription) },
)
```

lark-bank wrote a private `answer` helper to stop repeating it. That helper is this module's missing overload.

## Not doing

- **No automatic mapping** from an error's type to a declaration. Which declared response a `Left` becomes stays the
  service's decision, written where it can be read, as spec 0036 settled.
- **No change to the two overloads there are.**
- **Nothing for accumulated errors.** `mapLeft` into one payload, then `toOutcome(failure)`, already reads well.

## Shape

```kotlin
// The Left named case by case; a Right is ok.
Billing.subscribe(signup).toOutcome { error ->
    when (error) {
        is NoSuchPlan -> planMissing(Problem("no_such_plan", "No plan called '${error.code}'"))
        is AlreadySubscribed -> alreadySubscribed(Problem("already_subscribed", error.email))
    }
}
```

```kotlin
inline fun <L, E, A> Either<L, A>.toOutcome(refused: (L) -> Outcome<E, Nothing>): Outcome<E, A>
```

## Why this shape

The lambda answers `Outcome<E, Nothing>`, so it can only be a failure. A declaration's `invoke` already returns
exactly that, and so does `error(...)` for a case that cannot happen, which returns `Nothing`. `ok` cannot be
returned from it by mistake. It is `inline`, as `fold` is, so a `return@handledOrFail` still works inside it. The
alternative was an overload taking a map from the error's class to a declaration. That would pick the status by
type, which spec 0036 refused, and could not build each payload from the error.

## Stack

- [x] **`spec-0060-refused`**: the overload, its tests, the dump, the reference and the example.
      Done when: `:pelican-arrow:check` passes, and the Arrow example's several-failure handler uses it.

## Acceptance

```bash
./gradlew :pelican-arrow:check :example:build
```

## Open questions

1. **Should it be called `toOutcome` or `refusedAs`?**
   Recommended: `toOutcome`. It is the same conversion as the other two, and an overload is found where they are.
2. **Should the lambda answer `Outcome<E, Nothing>` or `Outcome<E, A>`?**
   Recommended: `Nothing`, so the lambda cannot turn a `Left` into a success.
