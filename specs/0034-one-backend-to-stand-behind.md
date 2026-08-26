# 0034 — One backend to stand behind

## Problem

Three server backends and three codecs proved the interpreter is not shaped
by any one of them — and that proof is now a maintenance surface three times
the size of what 1.0 needs to promise. The maintainer has decided (in
review, 2026-08-26): the 1.0.0 release ships Pekko and Jackson. The other
backends and codecs are not abandoned; they move, complete and green, to a
snapshot branch and return after 1.0 as restores.

## Not doing

- **No deletion of history.** The `multi-backend` branch is cut from main
  immediately before the removal, holding the complete three-backend,
  three-codec world with all fifteen specs landed.
- **No client-side amputation beyond Ktor.** `pelican-client-java` and
  `pelican-client-okhttp` stay — decided; `pelican-client-ktor` leaves with
  the Ktor server stack — decided.
- **No harness deletion.** `allBackends`, the parity suites, and the codec
  agreement matrix keep their shapes with a single entry each, so a
  returning module plugs back into a live socket — decided.
- **No renumbering, no spec deletion.** Ticked specs stay as the record.

## Shape

Removed from main: `pelican-http4k`, `pelican-http4k-docs`,
`pelican-http4k-mcp`, `pelican-test-http4k`, `pelican-ktor`,
`pelican-ktor-docs`, `pelican-ktor-mcp`, `pelican-client-ktor`,
`pelican-jsoniter`, `pelican-kotlinx` — and, decided after the freeze
landed, `pelican-client-java` and `pelican-client-okhttp`: 1.0 ships one
client transport, `pelican-client-pekko` — the client a Pekko stack already
runs. The JDK, OkHttp and Ktor transports return with the branch.

Kept: core, schema, openapi, import, codegen, gradle-plugin, jackson, mcp,
mcp-server, pekko + pekko-docs + pekko-mcp, client-pekko,
test, test-golden, test-pekko, metrics, metrics-otel, benchmarks, example.

Example keeps the `Backend`/`Running` seam and every parity test, running
Pekko alone. The codec matrix runs Jackson alone. Docs tell one-backend
truth everywhere, with the roadmap's "Coming back after 1.0" section
carrying the pointer to the branch.

## Why this shape

A snapshot branch beats deleting-and-trusting-git because the branch is a
named, buildable world — the roadmap can point at it, and restoring is a
merge conversation rather than an archaeology dig. Keeping harness shapes
costs a few single-entry parametrized tests and buys back the parity
infrastructure the moment a second backend returns.

## Stack

- [x] **`multi-backend`** — the snapshot branch, cut from main after spec
      0033 merges. Not a PR; a branch push. Done when: the branch builds green.
- [x] **`spec-0034-remove-modules`** — module directories, settings.gradle.kts,
      version catalog entries, cross-module test references; example rewired
      to Pekko-only with harness shapes kept.
      Done when: `./gradlew build` is green with the kept module set only.
- [x] **`spec-0034-docs-truth`** — README, docs/reference.md, choosing.md,
      modules.md, golden-testing.md, llms.txt, roadmap coherence (the
      "fourth backend" and "three is enough" sentences), CHANGELOG breaking
      note naming the branch.
      Done when: no doc claims a module main does not ship; 0025's pins stay green.

## Acceptance

```bash
./gradlew build
```

## Open questions

None — the decisions this spec records were made by the maintainer in chat,
2026-08-26, and are marked "decided" above.
