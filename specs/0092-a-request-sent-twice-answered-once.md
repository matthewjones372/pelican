# 0092 — A request sent twice, answered once

## Problem

A POST that times out leaves its caller not knowing whether the order was placed. `RetryPolicy` will not resend
one unless the caller names `Method.POST`, and its KDoc says that is only safe "for a POST that carries an
idempotency key", which nothing in Pelican reads. A service that wants Stripe-style keys writes a filter that reads
the header, keeps its own table and replays its own bytes. The document does not mention the header or the 409, and
the generated client sends no key, so a retry it makes is a second order.

## Not doing

- **A persistent store.** `IdempotencyStore` is an interface with an in-memory implementation for tests; the
  Postgres one lives in Surefold. Nothing here adds a dependency to `pelican-core`.
- **Streamed requests or responses.** An endpoint with a streamed body, `StreamOf`, `ByteStream`, NDJSON or SSE output
  that declares a key is refused when it is built: there is no single response to store.
- **GET, HEAD, PUT, DELETE, OPTIONS.** Already idempotent; declaring a key on one is refused.
- **Keys across endpoints.** A key is scoped to one operation and, where the endpoint has a caller, to its subject.

## Shape

```kotlin
val placeOrder = endpoint(order) {
    post("orders")
    authenticatedBy(caller)
    idempotencyKey()                       // Idempotency-Key header; required unless idempotencyKey(required = false)
    json<Order>(201).orFail(outOfStock)
}

api(routes, codecs = JacksonCodecs) { idempotency(store = InMemoryIdempotencyStore(), ttl = 24.hours) }

interface IdempotencyStore {
    fun reserve(key: IdempotencyScope, fingerprint: String, lease: Duration): Reservation  // Reserved | InFlight | Mismatch | Done(StoredResponse)
    fun complete(key: IdempotencyScope, response: StoredResponse, ttl: Duration)
    fun lookup(key: IdempotencyScope): Reservation?
    fun release(key: IdempotencyScope)                                                     // the handler threw; let a retry run it
}
```

- **First request:** `reserve` wins, the handler runs, and its outcome (declared success or declared failure: status,
  the declared headers it set, the bytes the endpoint's codec wrote) is `complete`d. A throw `release`s instead.
- **Same key, same fingerprint, done:** the stored response is answered; the handler does not run. Replays carry
  `Idempotent-Replayed: true`.
- **Same key, different fingerprint:** 422, a declared failure in the refusal envelope.
- **Same key while the first is still running:** 409 with `Retry-After: 1`, a declared failure.
- **No key where one is required:** 400, as any missing required header is.
- **Fingerprint:** SHA-256 over the operation, the decoded path, query and header inputs (the key excepted) and the
  body re-encoded by its request codec, so whitespace or field order in the JSON a client sent does not count.
- **Document:** an `Idempotency-Key` header parameter, and the 409 and 422 as responses whose schema is the refusal
  envelope's component. **Generated client:** an `idempotencyKey: String = IdempotencyKey.random()` parameter, put
  in the `ClientRequest` once, so `RetryingTransport`, which resends the request unchanged, reuses it.
  `RetryPolicy` treats a request carrying the header as safe to resend whatever its method.

## Why this shape

The key is declared on the endpoint, like `authenticatedBy`, so the route, the document, the test client and the
generated client read one value; a `Filter` would be invisible to all three. Replay is done in core, around
`handlerFor`: the stored bytes are decoded back into the outcome and handed to the backend as a handler result, so
Pekko, Jetty and Gannet answer a replay through the writer they already have and parity comes for free. The other way
is a pre-encoded-response SPI each backend writes raw; faster on replay, three writers to change. Recommended: core.

## Stack

- [x] **`spec-0092-store`** ([#376](https://github.com/matthewjones372/pelican/pull/376)): `IdempotencyStore`, `Reservation`, `StoredResponse`, `IdempotencyScope`,
      `InMemoryIdempotencyStore` with an injectable clock. Done when: expiry, lease timeout and the four
      `reserve` answers are tested.
- [x] **`spec-0092-declare`** ([#377](https://github.com/matthewjones372/pelican/pull/377)): `idempotencyKey()` on `EndpointBuilder`, `Endpoint.idempotency`, build-time
      refusals, the header and the 409/422 in the document. Done when: an OpenAPI golden pins them and
      `OpenApiSpecQualityTest` reads it.
- [x] **`spec-0092-serve`** ([#378](https://github.com/matthewjones372/pelican/pull/378)): `Api { idempotency(store, ttl) }`, the core wrapper, the startup check that a keyed
      endpoint has a store. Done when: served in process, a repeat is replayed, a reused key is a 422, one in
      flight a 409, and a throw releases the key.
- [x] **`spec-0092-parity`** ([#379](https://github.com/matthewjones372/pelican/pull/379)): a keyed endpoint in the backends example, bound on Pekko, Jetty and Gannet.
      Done when: the parity suite asserts replay, 422, 409 and release on every backend.
- [x] **`spec-0092-client`** ([#380](https://github.com/matthewjones372/pelican/pull/380)): the generated parameter, `RetryPolicy` on keyed requests, the test client sending a
      key. Done when: a `RetryingTransport` that loses the first response gets the stored one, the handler having run once.
- [ ] **`spec-0092-import-docs`**: the importer reads the header back as `idempotencyKey()`, reference and cookbook,
      changelog. Done when: `ImportedOrdersTest` round-trips an idempotent orders endpoint.
      Round-tripped through `tippingSpec` instead (`ImportedTippingTest`), so the committed orders golden and
      client keep their baselines; the keyed endpoint's document, the default response aside, reads back the same.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build   # parity suite, goldens, checkOrdersClient, ImportedOrdersTest
```

## Decisions

Matt took each recommendation on 2026-10-26.

1. **A throw releases.** Declared successes and declared failures are stored; an undeclared throw (500) releases the
   reservation, so a retry runs the handler again.
2. **Documented, not typed.** The 409 and 422 are documented responses and reach a generated client as
   `ApiCallFailed`; no endpoint's `E` grows a type its handler never returns.
3. **422 for a reused key with a new body, 409 while in flight**, as the IETF `Idempotency-Key` draft says. An endpoint
   that declares its own 409 or 422 gets tagged failures (spec 0063).
4. **Store calls block**, like `Authenticator`. A keyed endpoint marked `nonBlocking()` is refused at build time.
