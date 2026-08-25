# 0019 — Construction that survives a freeze

## Problem

`Api` is a fifteen-parameter public constructor with defaults
(`Api.kt:131-200`), and it is the expression every Pelican program contains.
In Kotlin, adding a parameter to such a constructor changes both the
descriptor and the synthetic defaults constructor, so after 1.0 every new
server setting is a binary break the BCV gate will rightly refuse. The same
shape sits on `RetryPolicy` (eleven parameters), `ClientRequest`,
`Docs`/`DocsOAuth` in pelican-openapi, and `McpOptions`. Today the cost is
invisible; the day after 1.0.0 it compounds with every release.

Two smaller frozen warts ride along: `Ansi` is public and implements a
private interface (`Report.kt:114,138`), and `DefaultImpls` bridge classes
sit in the dumps (`pelican-core.api:797-799,1166-1172,1354-1356`) although
the interfaces already emit real JVM default methods.

## Not doing

- **No builder for descriptions.** Endpoints, outputs, codecs keep their
  factory-function style; this spec touches only the settings bundles.
- **No behaviour change.** Every default stays what it is today. Aligning
  `start()` is spec 0020, not this.
- **No deprecation cycle.** Pre-1.0, the constructors go internal in one
  release; the CHANGELOG already warns breaking changes before 1.0.

## Shape

```kotlin
val api = api(routes, codecs = JacksonCodecs) {
    maxBodyBytes = 1 * MiB
    cors(corsPolicy(origins = setOf("https://shop.example")))
    onError { ref, t -> log.error("$ref", t) }
}
```

A top-level factory taking the required things as parameters and a
config-lambda over a mutable builder that freezes on return — the pattern
`FunctionalStyleTest` already licenses. Adding a setting is then adding a
`var`, which is binary-compatible. Same treatment for `retryPolicy { }`,
`docs { }`, `mcpOptions { }`; `ClientRequest` gains a `copy`-style wither set
instead, since transports construct it on a hot path.

## Why this shape

The alternative is keeping the constructors public and paying with a major
version per added setting, or a telescoping overload pile. A builder is one
mutable class per settings type and nothing else changes. Withers alone were
considered: fine for `ClientRequest`, unpleasant for fifteen `Api` fields.

## Stack

- [ ] **`spec-0019-api-builder`** — `api(...)` factory + builder in core, `Api`
      constructor internal, call sites and docs migrated.
      Done when: `apiDump` shows no public `Api` constructor and `./gradlew build` is green.
- [ ] **`spec-0019-satellites`** — same for `RetryPolicy`, `ClientRequest`
      (withers), `Docs`/`DocsOAuth`, `McpOptions`.
      Done when: no public constructor with more than four parameters remains in any dump.
- [ ] **`spec-0019-abi-hygiene`** — `Ansi` internal behind a public no-op or
      moved into the report; `-jvm-default=no-compatibility` across modules;
      dumps regenerated once.
      Done when: no `DefaultImpls` entry and no reference to a non-public supertype in any `.api` file.

## Acceptance

```bash
./gradlew apiDump build
```

## Open questions

1. Does `api()` return `Api` or an interface? Recommend the class — an
   interface here is speculative and the class is already final.
2. Builder vars or builder functions for the nested settings (`cors(...)` vs
   `cors { }`)? Recommend functions where a value already has a factory,
   vars for scalars.
3. Keep a four-arg `Api(routes, codecs)` convenience constructor public for
   tests? Recommend no — the factory with no lambda reads the same.
