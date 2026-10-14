# 0078 — Modules grouped by what they are

## Problem

Thirty-one library modules, `example`, `benchmarks` and the Gradle plugin sit
side by side at the root of the repository, and every backend adds four to
six more. A reader looking for "the Jetty pieces" or "the codecs" reads the
whole list. The modules already fall into a few kinds, and the directory tree
does not say so.

## Not doing

- **Renaming anything a user sees.** Gradle project paths (`:pelican-jetty`),
  Maven coordinates and package names stay exactly as they are.
- **Splitting the repository.** The parity suites need every backend in one build.
- **Moving `example` or `benchmarks`.** They are not libraries.

## Shape

```
core/                 pelican-core, pelican-schema, pelican-openapi
codecs/               pelican-jackson, pelican-kotlinx
backends/pekko/       pelican-pekko, -pekko-docs, -pekko-mcp, pelican-test-pekko,
                      pelican-client-pekko, pelican-streams
backends/jetty/       pelican-jetty, -jetty-docs, -jetty-mcp, pelican-test-jetty,
                      pelican-client-jetty, pelican-streams-jetty
testing/              pelican-test, pelican-test-golden, pelican-test-wiremock
integrations/         pelican-health, -health-kafka, -health-redis, pelican-metrics,
                      pelican-metrics-otel, pelican-oidc, pelican-arrow,
                      pelican-mcp, pelican-mcp-server
tooling/              pelican-codegen, pelican-import, pelican-gradle-plugin
bom/                  pelican-bom
example/  benchmarks/
```

```kotlin
// settings.gradle.kts: the project keeps its name, only its directory moves
include(":pelican-jetty")
project(":pelican-jetty").projectDir = file("backends/jetty/pelican-jetty")
```

- Adapters are grouped by backend, the rest by kind: a backend's modules
  change together, and http4k or Ktor returning from `a6c20b2` is one new
  directory under `backends/`.
- `pelican-bom` is a `java-platform` constraining every published module to
  the build's version, so a consumer names one version:
  `implementation(platform("io.github.matthewjones372:pelican-bom:$v"))`.

## Why this shape

Moving directories while keeping project paths changes nothing for a user
and nothing for `./gradlew :pelican-x:test`, CI task names, docs or specs.
Renaming projects to `:backends:jetty` would break every one of those for no
gain. Grouping purely by kind (all `-docs` together, all `-mcp` together)
scatters a backend across five directories. Recommended: the layout above.

## Stack

- [ ] **`spec-0078-dirs`**: `git mv` every module, `projectDir` in settings,
      and every path that names a module directory: `FunctionalStyleTest`'s file
      paths, `includeBuild` for the plugin, `publish.yml`'s `-p`,
      `dependabot.yml`, Kover, Dokka, doc links, AGENTS.md and README trees.
      Done when: `./gradlew build` passes, `./gradlew projects` lists the same
      project names as before, `publishToMavenLocal` writes the same
      coordinates, and `git log --follow` reaches a moved file's history.
- [ ] **`spec-0078-bom`**: `pelican-bom`, published with the rest.
      Done when: a test resolves modules through the platform with no versions
      of their own, and the README shows it.

## Acceptance

```bash
./gradlew build
./gradlew projects
./gradlew -p tooling/pelican-gradle-plugin build
```

## Decisions

Matt agreed the layout and the BOM on 2026-10-14.

1. **Project paths and coordinates do not change**, only directories.
2. **The Gradle plugin moves too**, under `tooling/`, with `includeBuild`,
   `publish.yml` and `dependabot.yml` following it.
