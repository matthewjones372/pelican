# 0056 — A stub written in endpoints

## Problem

A service that calls somebody else's API tests its client against WireMock, and
writes the stubs as URLs and JSON strings. A renamed path or a changed payload
leaves every stub in place, answering 404 or a body the client no longer reads,
and the test that was meant to catch the change fails for the wrong reason or
passes.

When the other API is described as Pelican endpoints — the ones a generated
client is built from — the stubs could be built from them too. The petshop
proved it with a module of its own (`pelican-wiremock`, petshop#8); this moves
it here, where the next consumer does not have to copy it.

## Not doing

- No record-and-replay, no proxying to a real service, no stub files on disk.
- No new server-side behaviour: answers are rendered by the code a server runs.
- Nothing in `pelican-core`, and no Pekko.

## Shape

```kotlin
@JvmField @RegisterExtension
val registry = PelicanWireMock()                        // random port, stopped after each test

registry.stub(lookupChip, 1L) answers ok(chip)
registry.stub(lookupChip, 2L) answers noSuchChip(Problem("never chipped"))
registry.stub(lookupChip, 3L).answers(ok(chip), after = 5.seconds)
registry.stub(lookupChip, 4L) breaksWith 500            // a status the endpoint never declared
registry.stub(recordKeeper, In2(number, NewKeeper("Ada"))) fails Fault.CONNECTION_RESET_BY_PEER
registry.stub(recordKeeper) { (number, keeper) -> ok(ChipRecord(number, keeper.keeper)) }

registry.verify(recordKeeper, In2(number, NewKeeper("Ada")))
registry.calls(recordKeeper) shouldBe 0

RegistryClient(baseUrl = registry.baseUrl, codecs = JacksonCodecs)   // what the test points at
```

- A stub matches the request `ApiClient.request(endpoint, input)` builds.
- An answer is `Outcome<E, T>` for that endpoint, so only a declared failure
  compiles. It is rendered by `InMemoryClientTransport` over a one-endpoint
  `api`, bound with `ServerEndpoint`'s public constructor, so there's no Pekko.

## Why this shape

Rendering through Pelican's own response code means a stub's status, content type and
body are the ones a Pelican server would send, rather than a second encoder that
can drift. The per-input `stub(endpoint, input)` is the common case; the handler
form `stub(endpoint) { input -> … }` covers load tests and anything stateful.

A WireMock `ResponseDefinitionTransformerV2` does the rendering per request. The
alternative is to render once at stub time. That is simpler, but it cannot serve the handler form.

## Stack

- [ ] **`spec-0056-wiremock-stubs`**: `pelican-test-wiremock` with `stub`,
      `answers`, `fails`, `breaksWith`, `verify` and `calls`, as a plain
      `AutoCloseable`.
      Done when: the example's `OrdersClient` passes a suite against it that
      names no path and no JSON.
- [ ] **`spec-0056-wiremock-junit`**: the JUnit 5 extension over it.
      Done when: a test with only `@RegisterExtension` gets a started server
      and a stopped one.
- [ ] **`spec-0056-wiremock-docs`**: `docs/modules.md` row and its dependency
      test, a reference section, README link.
      Done when: the module's dependency claim is a test.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :pelican-test-wiremock:test
```

## Open questions

1. **Name.** `pelican-test-wiremock`, beside `pelican-test-golden` and
   `pelican-test-pekko`, or `pelican-wiremock`? *Recommend the first.*
2. **Which WireMock.** `wiremock-standalone` shades Jetty and Jackson and so
   cannot clash with a service's own. `wiremock` is smaller and brings Jetty 11
   onto the test classpath. *Recommend standalone.*
3. **JUnit coupling.** `pelican-test-golden` claims no test framework. Should
   `junit-jupiter-api` be `compileOnly`, so the extension is there only for a
   build that already has JUnit? *Recommend yes.*
4. **Should `breaksWith` exist?** It is the one answer the contract does not describe, and
   also the one a client most needs to survive. *Recommend keeping it, named
   so it reads as undeclared.*
5. **Petshop meanwhile.** Should petshop keep its copy until this ships in an RC,
   or consume a `publishToMavenLocal` build? *Recommend keeping the copy, then deleting it
   in the PR that bumps Pelican.*
