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
val registry = PelicanWireMockExtension()               // random port; stubs cleared after each test

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

- A request matches a stub when Pelican's own routing and decoding turn it into
  the stubbed input. It does not match the exact URL or body string, because
  another client may order or encode the query differently, or leave a
  defaulted field out, and mean the same request.
- An answer is `Outcome<E, T>` for that endpoint, so only a declared failure
  compiles. It is rendered by `InMemoryClientTransport` over a one-endpoint
  `api`, bound with `ServerEndpoint`'s public constructor, so there's no Pekko.

## Why this shape

Rendering through Pelican's own response code means a stub's status, content type and
body are the ones a Pelican server would send, rather than a second encoder that
can drift. The per-input `stub(endpoint, input)` is the common case; the handler
form `stub(endpoint) { input -> … }` covers load tests and anything stateful.

Matching by decoded input is what lets a non-Pelican client hit the stub.
Matching the exact string would fail on `?a=1&b=2` against `?b=2&a=1`, which is
noise rather than a finding. Matching by parts was tried first, and it failed on
the example's own generated client, which leaves a defaulted field out of the
body. `wireMock` exposes the
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

## Decisions

Each open question in the draft took its recommendation.

1. **Name:** `pelican-test-wiremock`.
2. **WireMock:** `wiremock-standalone`, as `api`.
3. **JUnit:** `junit-jupiter-api` is `compileOnly`. `PelicanWireMock` is a plain
   `AutoCloseable`, and `PelicanWireMockExtension` adds the callbacks, so a
   build without JUnit never loads a JUnit type.
4. **`breaksWith`:** kept, for a status the endpoint never declared.
5. **Petshop:** keeps its copy until this ships in an RC, then deletes it in the
   PR that bumps Pelican.
6. **Matching:** by decoded input, so undeclared headers and query parameters
   make no difference and declared ones do. With this, the module needs only
   `pelican-core`, not `pelican-test`.
7. **`wireMock`:** exposed.
