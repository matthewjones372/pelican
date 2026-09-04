# 0042 — The other cross-build

## Problem

Spec 0037 removed the `_2.13` pin so that an application on the Scala 3
cross-build of Pekko could add Pelican without ending up with two of them on
one classpath. The argument that this works is that Pelican compiles against
`javadsl` plus four names — `NotUsed`, `ByteString`, `Creator`,
`ClassicActorSystemProvider` — which are identical in both cross-builds.

That argument has never been executed. The whole build, every suite and every
benchmark run against `_2.13`. What 0037 verified is that Pelican *ships* no
Pekko; that its bytecode links against `_3` is still a claim read off an import
list, and it is exactly the claim the user who reported the bug is relying on.

## Not doing

- **No cross-building of Pelican.** One artifact, no suffix, as 0037 decided.
  This checks that the one artifact links against both, not that two exist.
- **No second copy of the suite.** One service exercised end to end is the
  claim; running four hundred tests twice buys nothing and doubles the time.
- **No socket.** Sealed with `Route.function(system)`, as the benchmarks are.
- **No new module.** A published-module list, an `.api` dump and a coverage
  entry for something that is a check rather than a library.

## Shape

A `scala3Test` source set on `pelican-pekko`, whose classpath is this module's
own compiled output — the `_2.13`-compiled bytecode a consumer downloads —
against Pekko `_3`:

```kotlin
val scala3Test by sourceSets.creating

dependencies {
    "scala3TestImplementation"(sourceSets.main.get().output)
    "scala3TestImplementation"(project(":pelican-core"))
    "scala3TestImplementation"(project(":pelican-jackson"))
    "scala3TestImplementation"(platform("org.apache.pekko:pekko-bom_3:$pekkoVersion"))
    "scala3TestImplementation"("org.apache.pekko:pekko-http_3:$pekkoHttpVersion")
    // …actor-typed and stream, junit and kotest
}
```

One test: describe an endpoint with a JSON body, interpret it, run a request
through the sealed route, assert the bytes. That exercises the interpreter, the
codec seam and Pekko's own routing, which is every place a missing or moved
class would show up.

Wired into `check`, so it is a gate rather than a task nobody runs.

## Why this shape

The alternative is a module of its own, which needs three edits to the root
build — the published list, `apiValidation.ignoredProjects`, the kover
aggregate — to stop something that is a check from being treated as a library.
A source set needs none of them and puts the check beside the code it is about.

Depending on `sourceSets.main.output` rather than on `project(":pelican-pekko")`
is the point: the project dependency would drag the `_2.13` compile
dependencies back in, and the thing under test is the compiled output on its
own.

## Stack

- [x] **`spec-0042-scala3-smoke`** — the source set, its dependencies, the
      test, and `check` depending on it.
      Done when: `./gradlew build` is green and `./gradlew :pelican-pekko:scala3Test`
      resolves only `_3` Pekko artifacts.

## Acceptance

```bash
./gradlew build
./gradlew :pelican-pekko:dependencies --configuration scala3TestRuntimeClasspath | grep pekko
```

## Open questions

None — answered by the maintainer in chat, 2026-09-04, and recorded as decided:

- **A source set, not a module.**
- **One end-to-end request**, not a second copy of the suite.
- **Sealed, not bound**: the claim is about linking, and a port adds nothing.
- **Jackson is on the classpath**, because a body going through a codec is the
  realistic path and the one that touches the most Pekko types.
