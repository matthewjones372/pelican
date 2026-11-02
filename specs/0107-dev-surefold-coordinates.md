# 0107 — dev.surefold coordinates and packages

## Problem

pelican lives in the `surefold` GitHub organisation now, but it still publishes as `io.github.matthewjones372:<module>`,
its Kotlin sources are in `io.github.matthewjones372.pelican.*`, and its Gradle plugin is
`io.github.matthewjones372.pelican`. The `dev.surefold` namespace is verified on Maven Central, and surefold is moving
to it (surefold spec 0277). pelican depends on `surefold-stream`, so once surefold has moved, pelican would otherwise
depend on `dev.surefold` artifacts while still publishing under the old name.

## Not doing

- Not renaming modules or changing any API beyond the package name.
- Not deleting or rewriting anything already on Central.
- Not changing specs' text, the changelog's history or review notes.

## Shape

```kotlin
// build.gradle.kts in a project using pelican
plugins { id("dev.surefold.pelican") version "1.3.0" }
dependencies { implementation("dev.surefold:pelican-core:1.3.0") }

// sources
import dev.surefold.pelican.Webhook
```

`io.github.matthewjones372.pelican` becomes `dev.surefold.pelican`, including the codegen templates under
`src/main/resources/io/github/matthewjones372/pelican/`. `group` becomes `dev.surefold`. The plugin ID becomes
`dev.surefold.pelican` on Maven Central and the Gradle Plugin Portal. pelican's own dependency on surefold moves to
`dev.surefold:surefold-stream*` at surefold's first `dev.surefold` release.

The first `dev.surefold` release goes out alongside a relocation release: at the same version, a POM-only artifact per
module at `io.github.matthewjones372:<module>` with `<relocation><groupId>dev.surefold</groupId></relocation>`. The
Portal plugin `io.github.matthewjones372.pelican` gets a final version whose description points at the new ID.

## Why this shape

The same as surefold's: coordinates and packages move together, in one breaking minor, so what users depend on and
what they import agree. Moving after surefold means pelican never depends on one namespace while publishing another.

## Stack

- [ ] **`dev-surefold-build`** — `group`, plugin ID, POM `url`/`scm`, and the surefold dependency coordinates.
      Done when: `./gradlew publishToMavenLocal` writes `~/.m2/repository/dev/surefold/pelican-*`.
- [ ] **`dev-surefold-packages`** — `git mv` the source trees and codegen templates and rewrite `package`/`import`
      lines, by script; the script is in the PR description. One PR per tooling/runtime split if it reads better.
      Done when: `./gradlew build` is green with no `io.github.matthewjones372` in sources or templates.
- [ ] **`dev-surefold-docs`** — README, `llms.txt`, docs: coordinates, imports and the plugin ID.
      Done when: `grep -rn io.github.matthewjones372` finds only specs, CHANGELOG history and review notes.
- [ ] **`dev-surefold-relocation`** — the relocation publication and the Portal pointer.
      Done when: a project depending on `io.github.matthewjones372:pelican-core:<new>` resolves `dev.surefold:pelican-core`.

## Acceptance

```bash
./gradlew build
grep -rn "io.github.matthewjones372" --include=*.kt --include=*.kts . | grep -v '^./specs/'   # empty
./gradlew publishToMavenLocal && ls ~/.m2/repository/dev/surefold/
```

## Open questions

1. The package entry moves every source file, far over the ~200-line cap. **Recommended:** allow it for this mechanical
   entry and review the script plus a sample.
2. Version for the move. **Recommended:** the next minor, 1.3.0, which is a breaking change in a 1.x line. The
   alternative, 2.0.0, is more honest under semver; pick that if anyone outside depends on pelican.
3. Can the Gradle Plugin Portal relocate a plugin ID? **Recommended:** it can't, so publish one last
   `io.github.matthewjones372.pelican` version whose description names `dev.surefold.pelican`, and stop there.
