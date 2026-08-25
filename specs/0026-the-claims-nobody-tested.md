# 0026 — The claims nobody tested

## Problem

The parity suite is superb on outputs and refusals and thin on five claims a
production team relies on. A throwing handler's 500 body — the `ApiError`
shape with its reference — is rendered at three hand-built call sites
(`pekko Responses.kt:181-198`, `http4k Responses.kt:157-171`,
`ktor Responses.kt:229-241`) and never asserted across backends; spec 0013
deferred it. HEAD is mapped in all three routers and has zero tests; whether
each engine auto-answers HEAD for a GET endpoint is unknown. Chunked bodies
over the limit are tested on Pekko only (`ChunkedBodyLimitTest`). List
headers arriving as two field lines are untested (the suite sends one
comma-joined line, `AllBackendsTest.kt:248`). And nothing anywhere exercises
a slow or disconnecting stream consumer — `StreamingSunHttp.kt:33-37` is
hand-modified server code on an unbounded `newCachedThreadPool`, exactly
where a stalled-reader leak would live.

## Not doing

- **No load or soak infrastructure.** These are correctness tests with small
  fixed concurrency, not benchmarks; JMH keeps the performance job.
- **No fixing divergences this uncovers** beyond pinning them — a divergence
  found here gets the `MethodMismatchTest` treatment (documented and
  pinned) or its own spec, decided in review, not silently in the test PR.

## Shape

Five additions to the cross-backend suites in `example/backends`, each a
sentence-named test: the throwing-handler 500 asserting the `ApiError` body
and reference header on all three; a declared HEAD endpoint answering on all
three plus a pinned answer for HEAD-against-GET; the chunked over-limit 413
on http4k and Ktor; a two-field-line list header decoding as two values; and
a streaming consumer that reads one frame, stalls past the keep-alive
period, then disconnects — asserting the handler's stream is closed and the
server thread count returns to baseline.

## Why this shape

Each test pins a claim the reference already makes or a fourth backend (spec
0014) would need answered before it can claim parity. Writing them before
the routing rework of spec 0022 lands would test moving code; this spec
stacks after it. The alternative — folding these into 0022 — makes that spec
propose two things.

## Stack

- [ ] **`spec-0026-refusal-claims`** — the 500-shape, HEAD, chunked-limit,
      and two-line-header tests.
      Done when: all four run against all three backends in `AllBackendsTest` or a sibling suite.
- [ ] **`spec-0026-slow-consumer`** — the stall-and-disconnect streaming
      test, per backend, over real sockets.
      Done when: the test fails against a deliberately-leaking `StreamingSunHttp` and passes against the shipped one.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. HEAD-against-GET: pin whatever the engines do today as a documented
   difference, or normalise to 404/405? Recommend pin first — normalising is
   a behaviour change that deserves its own decision with the evidence in
   hand.
2. Thread-count assertions are flaky by nature; is "stream closed within N
   seconds" enough? Recommend asserting the handler-side close signal and
   leaving thread counts to a `withClue` diagnostic.
