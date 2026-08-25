# Modules

Linked from the [README](../README.md). What each of the eighteen modules is
for, and what it depends on — the list to read when deciding which ones a
build actually needs.

Eighteen modules and a Gradle plugin; you take four or five. The layering is enforced by tests
rather than convention.

| Module | Depends on | Contains |
|---|---|---|
| `pelican-core` | **nothing** | endpoint descriptions, plain codecs, a minimal JSON tree |
| `pelican-jackson` / `-kotlinx` / `-jsoniter` | core + one JSON library | your `Codecs` |
| `pelican-pekko` / `-http4k` / `-ktor` | core + one server library | descriptions → that server's routes |
| `pelican-*-docs` | its backend, openapi | serves the document and Swagger UI |
| `pelican-metrics` | core + micrometer-core | one filter; meters tagged from the descriptions |
| `pelican-openapi` | core | descriptions → OpenAPI 3.1.0 |
| `pelican-codegen` | core | descriptions → a Kotlin client, as source |
| `pelican-import` | codegen + snakeyaml-engine | an OpenAPI document → descriptions, as source |
| `pelican-gradle-plugin` | **nothing** | `io.github.matthewjones372.pelican`: every generator, as Gradle tasks |
| `pelican-test` | **core** | descriptions → a typed client for tests, on any backend |
| `pelican-test-golden` | test + openapi | per-endpoint goldens; fails on a breaking change |
| `pelican-test-pekko` / `-http4k` | test + that backend | the in-memory transport |

Every one of those dependency claims is a test. `pelican-core` asserts its
runtime classpath holds nothing but the Kotlin standard library, `pelican-openapi`
asserts Pekko is absent so docs can be generated in a build task with no server
present, each backend asserts the document generator and the other backends are
absent, `pelican-metrics` asserts it is core plus a meter API and no server
library, and `pelican-test` asserts it drags in no server library and no matcher
library. The full breakdown is in
[docs/reference.md](reference.md#modules).
