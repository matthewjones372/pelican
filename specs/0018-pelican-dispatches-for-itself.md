# 0018 — Pelican dispatches for itself

## Problem

Matching does not scale, and it is not the interpreter's doing. Measured at two
hundred endpoints with the endpoint under test declared last:

```
Pelican on http4k, 200 endpoints:   134,344 ± 8559ns
the same routes written by hand:    135,104 ± 5465ns
```

`routes(...)` and `Directives.concat` are ordered scans, so both cost the same
and both cost about 150µs — against under two microseconds for one endpoint. A
service with a hundred endpoints spends more time being routed than served.

Nesting does not fix it. Grouped under a prefix with http4k's own nesting, two
hundred endpoints measured 138,631ns against 142,678ns flat — the outer scan is
still a scan, over as many entries as there are prefixes.

What fixes it is what neither router can do. They hold opaque handlers and can
only try them in turn. **Pelican holds the descriptions** and can index them
before a request arrives.

A prototype — one handler, a hash of the first path segment, then that bucket —
measured on the same machine:

| endpoints | ordered scan | indexed |
|---|---|---|
| 1 | 1771ns | 64ns |
| 50 | 37,827ns | 75ns |
| 200 | 142,678ns | 73ns |

Flat. The prototype's handlers decode nothing, so that is a dispatch ceiling
rather than a projection; a real endpoint still pays for its codecs. The number
to take from it is that the part underneath stops growing.

## Not doing

- **No change to what any endpoint answers.** Same statuses, same bodies, same
  headers, same order of precedence between a literal and a capture.
- No new public API. `toRoute`, `toHttpHandler` and `Route.pelican` keep their
  signatures; what changes is what they hand the backend.
- Not Ktor. `Route.pelican` gives Ktor one route per endpoint and Ktor's own
  tree scores them, which is already what this spec is trying to build.
- No caching keyed on the request. The index is built from descriptions at
  route-build time and never changes; anything per-request is a bug.

## Shape

A trie over path segments, built once in core from the descriptions, walked per
request:

```kotlin
// core
class RouteIndex private constructor(…) {
    /** The endpoint this path and method reach, or null for no match. */
    fun match(method: Method, path: String, into: MutableMap<ParamKey<*>, Any?>): ServerEndpoint?
}

fun Api.routeIndex(): RouteIndex
```

Each backend registers **one** route that consults it:

```kotlin
// pekko
Directives.extractRequest { req ->
    val matched = index.match(req.method(), req.uri.getPathString(), captures)
    if (matched == null) Directives.reject() else complete(invoke(matched, …))
}
```

`reject()` and http4k's not-matched are load-bearing: a Pelican route
concatenated with hand-written ones must decline what it does not describe, or
it swallows the rest of the service. `ConcatenatedRoutesTest` and
`MountedAlongsideTest` already say so.

## Why this shape

A trie rather than a hash of the first segment, because the shape that defeats
the hash is the common one: every endpoint under `/api/v1`. The benchmark's
decoys have distinct first segments, which flatters a first-segment index; a
trie is indifferent to where the paths diverge.

Owning dispatch means owning what a path nobody described gets, and what a path
that matched with the wrong method gets. Those answers currently differ per
backend — 404 on Pekko and Ktor, 405 on http4k — and `MethodMismatchTest` pins
them as a documented difference. This spec keeps that by *declining* rather than
answering, leaving each router to say what it says today.

The alternative is to leave it and document that Pelican is for services with
tens of endpoints. Not recommended: nothing about a description model deserves
that ceiling, and the fix is available precisely because descriptions are values.

## Stack

- [x] **`spec-0018-route-index`** — `RouteIndex` in core: build from `List<ServerEndpoint>`, walk per request, literal before capture, captures collected into the values map.
      Done when: a unit suite covers a literal beating a capture at the same position, a trailing capture, two methods on one path, and no match — and `declaredInputCount` sizing still holds. Landed in [#65](https://github.com/matthewjones372/pelican/pull/65).
- [x] **`spec-0018-pekko-and-http4k`** — both interpreters register one route over the index; rejection preserved.
      Done when: `AllBackendsTest`, `ConcatenatedRoutesTest`, `MountedAlongsideTest`, `MethodMismatchTest` and `CorsTest` are unchanged and green. Landed in [#66](https://github.com/matthewjones372/pelican/pull/66).
- [x] **`spec-0018-the-guard`** — a CI job asserting the curve stays flat.
      Done when: a change that reintroduces an ordered scan fails the build, and the assertion is a ratio rather than a wall-clock number. Landed in [#68](https://github.com/matthewjones372/pelican/pull/68).

## Acceptance

```bash
./gradlew build
./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 RoutingScale"
```

## Open questions

1. Does the index own method dispatch, or only paths? Owning both lets it answer
   "path matched, method did not", which is what http4k's 405 needs. Recommend
   paths only in the first entry and methods in the second, so the semantics
   change is reviewable on its own.
2. What does the guard assert? A wall-clock threshold on a shared runner is a
   flaky test with extra steps. Recommend a **ratio**: matching a 200-endpoint
   API costs no more than 3× a 1-endpoint API, measured in the same JVM, which
   is machine-independent and would have caught today's 80×.
3. Where does the guard run? A full JMH sweep is minutes. Recommend a plain JUnit
   test with a warmup loop, run on every build, and the JMH sweep left as the
   thing you ask for by name — a guard nobody runs is not a guard.
4. Does a path with no literal segments at all — `/{id}` — need its own bucket?
   Recommend yes, and that the walk tries literals before it, which is the rule
   `orderedEndpoints` encodes today by sorting on literal count.
