# 0098 — A limit the document shows

## Problem

A sign-in endpoint needs a cap per address, a booking endpoint a cap per caller, and every instance behind the load
balancer has to draw on the same count. Pelican answers none of it: `tooManyRequests(retryAfterSeconds)` is an
exception a handler throws once it has counted for itself. Surefold's core `RateLimiter` is per process and says
"server-side admission belongs to the HTTP layer", and `surefold-ratelimit` 0.14.0 now ships `RateLimits.take(key,
bucket, cost)`, in memory and on Postgres, waiting for a Pelican interface to adapt to (Surefold spec 0226).
Doorlist counts inside its auth and booking code, declares a `RateLimited` problem per endpoint, and reads the peer
address out of Jetty's own `Request`, since `Params` has none (dependencies.md item 6, `AuthHandlers.ip()`).

## Not doing

- **A persistent store.** `RateLimitStore` is an interface with an in-memory implementation; Surefold's Postgres
  buckets come through `surefold-pelican-ratelimit`, in Surefold, as 0230 did for idempotency keys.
- **Concurrency limits or load shedding.** Requests over time only.
- **Limits that depend on the body.** Taken before the body is read; a limit on, say, an email address in the body
  stays in the handler.
- **`RateLimit` and `RateLimit-Policy` headers.** The IETF draft is not final; see the open questions.

## Shape

```kotlin
val signIns = rateLimit("sign-in", TokenBucket(5, per = 1.minutes, burst = 10), key = byAddress)
val bookings = rateLimit("bookings", TokenBucket(30, per = 1.minutes), key = byCaller)

val signIn = endpoint { post("v1" / "auth" / "sign-in"); limitedBy(signIns); body(credentials); json<SignedIn>() }

api(routes, codecs) {
    rateLimits(store = InMemoryRateLimitStore())
    clientAddress(trustedProxies = 1)              // X-Forwarded-For hops to believe; 0 reads the socket
}

interface RateLimitStore {
    fun take(key: String, bucket: TokenBucket, cost: Int): Taken     // Allowed(remaining) | Denied(retryAfter)
}
```

- **Keys:** `byAddress` (`Params.clientAddress()`, new, the same on every backend), `byCaller` (the caller's subject;
  an endpoint without one is refused when built), `byEndpoint` (one bucket for everyone), or `key { p -> ... }`.
  The store key is `name`, the operation and the key, so two limits never share a bucket.
- **When:** an address or endpoint limit is taken before the caller is authenticated, so failed sign-ins count; a
  caller limit after it. Both before the body is read. Several limits on one endpoint are taken in order and the
  first denial answers.
- **The answer:** 429 in the refusal envelope with `Retry-After` in whole seconds, rounded up. Declared by
  `limitedBy`, so the document lists the 429, its header, and the limit in words in the operation's description
  ("5 a minute per client address, bursts of 10"). An endpoint that declares its own 429 gets tagged failures (0063).
- **MCP:** a denied tool call is an `isError` result, as any refusal is.

## Why this shape

The limit is declared on the endpoint, as 0092's key is, so the route, the document and the generated client read one
value; a `Filter` answering 429 works but is invisible to all three, and its 429 cannot be declared. Option B, a
`rateLimit` filter carrying a declaration that `onlyWhen` copies onto the endpoints it narrows to, has two lists to
keep in step. Recommended: A, declared, and taken in core beside `decodeCaller`, which every backend already calls.
The port's shape is Surefold's `take`, so its adapter is a few lines, and the bucket is a value a test can read.

## Stack

- [ ] **`spec-0098-store`**: `TokenBucket`, `Taken`, `RateLimitStore`, `InMemoryRateLimitStore` with an injectable
      clock, and a contract suite. Done when: 8 threads taking 1,000 times from 10 a second, burst 20, over 5 s of a
      test clock, are allowed exactly 70, as Surefold's suite asserts of its own.
- [ ] **`spec-0098-address`**: `Params.clientAddress()` on every backend, `clientAddress(trustedProxies)`.
      Done when: the parity suite reads the socket's address, and the right hop of a forwarded chain.
- [ ] **`spec-0098-declare`**: `rateLimit`, `limitedBy`, the keys, build refusals, the 429 in the document.
      Done when: a golden pins the 429, `Retry-After` and the sentence, and `OpenApiSpecQualityTest` reads it.
- [ ] **`spec-0098-serve`**: `rateLimits(store)`, taken in core in the order above, the startup check for a store.
      Done when: on every backend the eleventh sign-in in a burst of ten is a 429 with `Retry-After`, before
      authentication, and a caller limit does not count another caller.
- [ ] **`spec-0098-docs`**: reference, cookbook, changelog; how `surefold-ratelimit` plugs in.

## Acceptance

```bash
./gradlew build   # parity suite, goldens, OpenApiSpecQualityTest, ImportedOrdersTest
```

## Open questions

1. **Does a store call block?** Recommended: yes, like `Authenticator` and 0092's store, and a limited endpoint
   marked `nonBlocking()` is refused when built.
2. **What if the store is down?** Recommended: the request fails as a 500, never let through silently; a service that
   prefers to fail open wraps its store.
3. **Send the draft `RateLimit` headers?** Recommended: not yet; `Retry-After` is the one header every client reads.
4. **Does a denied request still count?** Recommended: no: a denial takes nothing, as Surefold's bucket already does,
   so a client that backs off as told gets in when `Retry-After` says.
