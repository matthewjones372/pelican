# 0037 — The Pekko the app already runs

## Problem

`pelican-pekko` and `pelican-client-pekko` ship Pekko as `api`, pinned to the
Scala 2.13 cross-build (`pelican-pekko/build.gradle.kts:15`,
`pelican-client-pekko/build.gradle.kts:15`).

An application already running the Scala 3 cross-build adds Pelican and gets
`pekko-actor_2.13` *and* `pekko-actor_3` on one classpath. They are different
Maven modules carrying identical fully-qualified class names, so no resolver
sees a conflict and `pekko-bom_3` never constrains the `_2.13` set. Both jars
land, Pekko's own `Version.check` reads two versions from two manifests, and
the service refuses to start — reported in chat, 2026-09-04, as "You are using
version 1.6.0 of Apache Pekko". The fix available today is an
`exclude(group = "org.apache.pekko")` on every Pelican dependency, worked out
from a runtime stack trace.

The suffix is the application's decision. Pelican compiles against `javadsl`
and four stable names — `NotUsed`, `ByteString`, `Creator`,
`ClassicActorSystemProvider` — identical in both cross-builds.

## Not doing

- **No second set of coordinates.** No `pelican-pekko-scala3`: five Pekko
  modules would become ten published artifacts.
- **No Gradle feature variant.** Capability-carrying variants are Gradle Module
  Metadata only, so sbt and Maven consumers never see them.
- **Nothing to deps that are not cross-built** — slf4j-api, Jackson,
  Micrometer, OpenTelemetry keep their scopes. Only `org.apache.pekko` has a
  suffix.
- **No change to `pelican-test`.** Backend-agnostic, names no Pekko.

## Shape

Pekko becomes provided: `compileOnly` in the five modules whose main sources
import it — `pelican-pekko`, `pelican-client-pekko`, `pelican-pekko-docs`,
`pelican-pekko-mcp`, `pelican-test-pekko` — and `testImplementation` at the
current version so the suites still run. No `org.apache.pekko` entry is
published in any Pelican POM or module metadata.

The consumer names the suffix, and either one works:

```kotlin
dependencies {
    implementation("io.github.matthewjones372:pelican-pekko:$pelicanVersion")

    // Pelican names no suffix, so this block is the whole decision.
    implementation(platform("org.apache.pekko:pekko-bom_3:1.6.0"))
    implementation("org.apache.pekko:pekko-actor-typed_3")
    implementation("org.apache.pekko:pekko-stream_3")
    implementation("org.apache.pekko:pekko-slf4j_3")
    implementation("org.apache.pekko:pekko-http_3:1.3.0")
}
```

`example/` and `benchmarks/` stop inheriting Pekko and declare their own, which
is what proves the published shape is usable.

## Why this shape

The alternative that keeps the one-line quickstart is publishing both suffixes
under separate coordinates: works everywhere, costs five extra artifacts and a
permanent question at every `implementation` line. Provided-scope costs each
user four lines once and keeps one artifact per module. Recommended, and chosen
by the maintainer in chat, 2026-09-04.

The Pekko floor stops being a floor and becomes a tested-against version: the
build still runs at 1.2.1 / Pekko HTTP 1.3.0, and that is what the docs should
promise was exercised rather than what a consumer resolves to.

## Stack

- [x] **`spec-0037-provided-pekko`** — the five modules' Pekko dependencies to
      `compileOnly` + `testImplementation`; `example` and `benchmarks` declare
      their own; `DependenciesTest`'s claim inverted.
      Done when: `./gradlew build` is green and `publishToMavenLocal` writes no
      `org.apache.pekko` entry into any Pelican POM or `.module`.
- [ ] **`spec-0037-docs-truth`** — every `dependencies { }` block naming a Pekko
      module across README, `docs/reference.md`, `modules.md`, `cookbook.md`,
      `mcp.md`, `a-whole-service.md`, `what-it-costs.md`, `llms.txt`; Versions
      reworded from floors to tested-against; the mixed-version troubleshooting
      section gains the suffix case; CHANGELOG breaking note.
      Done when: no doc adds a Pekko module without Pekko beside it.

## Acceptance

```bash
./gradlew build
./gradlew publishToMavenLocal
grep -rl "org.apache.pekko" ~/.m2/repository/io/github/matthewjones372/
```

## Open questions

None — the four this draft opened were answered by the maintainer in chat,
2026-09-04, with "whatever you think is best", and are recorded here as
decided:

- **No Pekko version constraints are published.** A constraint can only name
  `_2.13` coordinates, so it is inert for the `_3` user this spec exists for.
- **`DependenciesTest` inverts.** Its allowlist passes either way, which would
  leave it silent about this change; it asserts instead that the main runtime
  classpath is core and Kotlin alone, with no `pekko-`.
- **A missing Pekko is not checked for at startup.** A service that forgets the
  block gets `NoClassDefFoundError` on `Http`. A startup check would be a
  second error model for a mistake the compile already catches.
- **The Scala 3 smoke build is not in this spec.** "Either suffix works" is
  argued here, never run; proving it needs a second `example` configuration
  resolving `_3`, which is its own spec.
