# 0043 — Every Pekko name the bytecode holds

## Problem

Spec 0042 put one end-to-end request on the Scala 3 cross-build, in one module
of the five that speak Pekko. Counting the `org/apache/pekko/**` references in
each module's compiled classes says how thin that is:

| module | Pekko types in bytecode | covered on `_3` |
|---|---|---|
| `pelican-pekko` | 57 | roughly 20, by one request |
| `pelican-client-pekko` | 31 | none |
| `pelican-test-pekko` | 23 | none |
| `pelican-pekko-docs` | 21 | none |
| `pelican-pekko-mcp` | 16 | none |

So `_2.13` gets every suite and `_3` gets one happy path. A Pekko type reached
only by the SSE framing, the client transport or the docs route could have
moved between the cross-builds and nothing would say so.

## Not doing

- **No second copy of the suites.** Running four hundred tests twice buys
  coverage of Pelican's logic, which the cross-build cannot change, and costs
  the build minutes.
- **No behavioural claim.** Both cross-builds are compiled from one source, so
  what differs is what exists, not what it does. Linking is the risk.
- **No new module, and no source set per module.** One, as 0042 decided.

## Shape

The check is derived from the bytecode rather than written down, so it cannot
drift as the code grows: every `org/apache/pekko/**` name in a Pelican class
file must load on a classpath holding only Pekko `_3`.

```kotlin
val referenced = pelicanClasspathEntries().flatMap { pekkoNamesIn(it) }.toSet()
val missing = referenced.filterNot { it.loadsOnThisClasspath() }

missing.shouldBeEmpty()
```

`pelican-client-pekko`, `pelican-pekko-docs`, `pelican-pekko-mcp` and
`pelican-test-pekko` join `pelican-pekko`'s `scala3Test` classpath. Since 0037
made Pekko `compileOnly` in all of them, a project dependency brings no Pekko
with it, so adding them cannot put `_2.13` back on the classpath — a fact worth
asserting rather than assuming.

0042's end-to-end request stays: one test that actually answers is worth
having beside a link check.

## Why this shape

A hand-written list of types to check is the obvious alternative and rots the
first time somebody imports a new Pekko class. Reading the constant pool asks
the question of the artifact rather than of the author. Internal names carry
`/` separators, so a string constant naming a package in dotted form cannot be
mistaken for a class reference.

## Stack

- [ ] **`spec-0043-linkage`** — the four project dependencies on the existing
      source set, the linkage test, and an assertion that no `_2.13` artifact
      is on that classpath.
      Done when: `./gradlew build` is green and the test reports it scanned
      every one of the five modules.

## Acceptance

```bash
./gradlew build
./gradlew :pelican-pekko:scala3Test --info | grep "Pekko types"
```

## Open questions

None — the maintainer asked for this shape in chat, 2026-09-04, after seeing
the coverage table above.
