# 0012 — The parity suite samples the description surface

## Problem

`AllBackendsTest` states the claim the layering rests on: "a description that
two backends honour differently is a description one of them is getting wrong."
Five parameterised suites make it — roughly sixty cases run three times.

The shared descriptions they run against use `json`, `ndjson`, a multipart
envelope, a form, a negotiated body and cookies. **Six kinds are never asked of
all three:** `text()`, routed `empty()`, `jsonArray<T>()`, `bytes()`,
`rawBody()` and `sse<T>()`.

They are not untested — they are tested twice and separately. `pelican-http4k`
and `pelican-ktor` each carry a `TestApi.kt` describing all six, and the two are
near-identical copies exercised by their own interpreter tests. `pelican-pekko`
has neither: no `TestApi`, no `PekkoInterpreterTest`. So the backend the orders
example and both benchmarks run on has the least coverage of the three, for
exactly the outputs core frames itself.

## Not doing

- No deletion of the per-backend suites. `SseKeepAliveTest`, `StreamingTimingTest`,
  `MountedAlongsideTest`, `MethodMismatchTest` and `BorrowedSystemTest` are about
  things that differ on purpose, and `MethodMismatchTest` is the model for how
  a difference gets written down.
- No `PekkoInterpreterTest` to match the other two — that restores symmetry by
  keeping three copies of one description.
- Nothing beyond what the parity suite needs added to `Greetings.kt`. That file
  is read as documentation as well as run as a fixture.
- No assertion of one status where the routers genuinely differ.
- Not the refusals in `ServerErrors.described` that have no parity case. Spec
  0013, which is about that table rather than about the outputs.

## Shape

The six kinds join the shared descriptions, and the handlers appear once per
backend in the three files that already exist for that:

```kotlin
// example/backends/Greetings.kt — one description
val motd     = endpoint { get("motd"); text() }
val forget   = endpoint(name) { delete("greetings" / name); empty(204) }
val everyone = endpoint { get("everyone"); jsonArray<Greeting>() }
val logo     = endpoint { get("logo"); bytes("image/png") }
val echoRaw  = endpoint(rawBody()) { post("echo-raw"); bytes() }
val ticker   = endpoint { get("ticker"); sse<Tick>(eventName = "tick") }
```

```kotlin
// one assertion, three backends
@ParameterizedTest @MethodSource("backends")
fun `a jsonArray response is one array whichever backend framed it`(
    name: String, client: ApiClient,
) { … }
```

The two `TestApi.kt` files keep only what is genuinely backend-shaped — http4k's
`Sequence` laziness, Ktor's `Flow` — and lose the duplicated half.

## Why this shape

Two copies of one description in two modules is the risk `AllBackendsTest`
exists to remove, with the safety net taken off: nothing compares them, and
nothing covers the third backend at all. Moving the surface to where it is
described once is the same move the suite already made for `json` and `ndjson`.

The alternative is writing Pekko's own `TestApi` to match. It is less work, it
closes the Pekko hole, and it leaves three descriptions that can drift in three
directions. Not recommended.

## Stack

- [ ] **`spec-0012-parity-value-outputs`** — `text()` and a routed `empty(204)` on the shared descriptions, bound on all three, with cases.
      Done when: both answer identically on all three, `Content-Type` included, and a 204 carries no body anywhere.
- [ ] **`spec-0012-parity-stream-outputs`** — `jsonArray`, `bytes` and `rawBody` likewise; the duplicated half of both `TestApi.kt` files deleted.
      Done when: the three backends produce byte-identical bodies for the array and the echo, and neither module describes an endpoint the other also describes.
- [ ] **`spec-0012-parity-sse`** — `sse<Tick>` on the shared surface; frame format, `event:` line and `data:` payload.
      Done when: the three write the same frames, and `SseKeepAliveTest` stays per-backend and green.
## Acceptance

```bash
./gradlew build
```

## Open questions

1. Does adding six endpoints make `greetingsApi` too big to read as an example?
   Recommend accepting it — the file is already the "one description, three
   servers" exhibit, and six more lines of description is what that claim costs.
2. `bytes("image/png")` needs bytes to serve. Recommend a literal `ByteArray`
   in the fixture rather than a resource, so nothing has to be on a classpath.
3. Is Pekko missing an interpreter test a finding of its own? Recommend no: if
   the shared surface covers what the other two `TestApi` files cover, the gap
   closes without a fourth copy.
4. Where does a `rawBody` fixture leave `pelican-test`? `RequestSpec` carries a
   `String`, which is enough for the echo but not for binary. Recommend text,
   and leaving the byte-carrying `RequestSpec` on the refusals list where it is.
