# 0056 — A stub written in endpoints

## Problem

A service that calls somebody else's API tests its client against WireMock, and
writes the stubs as URLs and JSON strings. A renamed path or a changed payload
leaves every stub in place, answering 404 or a body the client no longer reads,
and the test that was meant to catch the change fails for the wrong reason or
passes.

When the other API is described as Pelican endpoints, the stubs could be built
from them too. The petshop proved it with a module of its own
(`pelican-wiremock`, petshop#8); this moves it here, where the next consumer
does not have to copy it.

Neither side has to be Pelican's. The client under test can be Retrofit, Ktor
or hand-written, because a stub is only HTTP. The endpoints can be imported
from the other API's OpenAPI document by `pelican-import`, which already exists,
so nobody has to write them by hand.

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

// whatever client the service already has
val client = Retrofit.Builder().baseUrl(registry.baseUrl)/* … */.create(RegistryApi::class.java)

// endpoints from the vendor's document: `pelican { endpoints { create("registry") {
//     document.set(file("registry-openapi.yaml")) } } }` generates lookupChip and recordKeeper

registry.wireMock.stubFor(get("/health").willReturn(ok()))   // anything the contract leaves out
```

- A stub matches the parts of the request `ApiClient.request(endpoint, input)`
  builds: method, path, each query parameter, and the body as JSON. It does not
  match the exact URL string, because another client may order or encode the
  query differently and mean the same request.
- An answer is `Outcome<E, T>` for that endpoint, so only a declared failure
  compiles. It is rendered by `InMemoryClientTransport` over a one-endpoint
  `api`, bound with `ServerEndpoint`'s public constructor, so there's no Pekko.

## Why this shape

Rendering through Pelican's own response code means a stub's status, content type and
body are the ones a Pelican server would send, rather than a second encoder that
can drift. The per-input `stub(endpoint, input)` is the common case; the handler
form `stub(endpoint) { input -> … }` covers load tests and anything stateful.

Matching by parts is what lets a non-Pelican client hit the stub. Matching the
exact string would be stricter, and it would fail on `?a=1&b=2` against
`?b=2&a=1`, which is noise rather than a finding. `wireMock` exposes the
underlying server for what no endpoint describes, so nobody has to run a
second WireMock beside this one.

A WireMock `ResponseDefinitionTransformerV2` does the rendering per request. The
alternative is to render once at stub time. That is simpler, but it cannot serve the handler form.

## Stack

One entry: the module is small, and a stub API without its extension or its
dependency test is not reviewable on its own terms. It will run past the
200-line soft cap on tests alone; the main source is expected near 200.

- [ ] **`spec-0056-wiremock`**: `pelican-test-wiremock` with `stub`, `answers`,
      `fails`, `breaksWith`, `verify` and `calls` as a plain `AutoCloseable`,
      the JUnit 5 extension over it, the `docs/modules.md` row with its
      dependency test, and a reference section.
      Done when: the example's `OrdersClient` passes a suite against it that
      names no path and no JSON, so does a plain `java.net.http` client that
      sends the query in a different order, a test with only `@RegisterExtension` gets a
      started and stopped server, and the module's dependency claim is a test.

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
6. **How loose is a match?** Headers are ignored and unknown query parameters
   are allowed, the way WireMock matches by default. Should an extra query
   parameter the endpoint does not declare fail the match instead? *Recommend
   allowing it. A client sending more than the contract asks for is not what
   these tests are about.*
7. **Exposing `wireMock`.** It lets a test reach around the contract. Should it
   be there, or should anything undescribed go on a second server? *Recommend
   exposing it. Health checks and auth endpoints are rarely in a vendor's
   document.*
