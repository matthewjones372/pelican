# 0096 — How often a caller may call

## Problem

A service has no way to say "a hundred a minute per caller" in Pelican. It writes a filter around a counter of its
own, keyed on whatever it can find, and answers 429 by hand. Nothing else knows: the document does not say the 429
can happen or what `Retry-After` it carries, the generated client has no `TooManyRequests` failure for it, the
counter is per node unless the service built a shared one, and `onRefusal` never hears of it. A caller from tenant A
and one from tenant B share a budget unless the filter remembered spec 0095.

## Not doing

- **Retrying.** `RetryPolicy` already sends a 429 again after the `Retry-After` it names, capped, and only for a
  method safe twice or a request with an `Idempotency-Key`. That stays as it is; this makes the 429 declared so the
  client knows the header is there.
- **The `RateLimit` and `RateLimit-Policy` headers.** The IETF draft is still moving; `Retry-After` is the one
  header every client already reads. A later spec can add them once the draft settles.
- **A persistent limiter.** An interface and an in-memory one; Surefold's lives in Surefold.
- **Weighted requests, quotas by plan, burst credits.** One limit is a count over a period.
- **Limiting the 401s and 400s Pelican answers before the key is known.** Flooding is the network's to absorb.

## Shape

```kotlin
val perCaller = rateLimit("orders", 100, per = 1.minutes)       // a named budget, shared by every endpoint naming it

val placeOrder = endpoint(orderBody) {
    post("orders")
    authenticatedBy(caller)
    tenanted(shop)
    rateLimited(perCaller)                                        // 429 with Retry-After once the budget is spent
    json<Order>(201)
}

api(routes, JacksonCodecs) { rateLimits(InMemoryRateLimiter()) }
```

- **`RateLimiter`** answers one question atomically: may `key` spend one from `limit` now, and if not, how long until
  it may. `InMemoryRateLimiter(clock)` is for tests and one node.
- **The key** is the limit's name, the caller's subject and the tenant's id, whichever the endpoint declares. An
  endpoint with neither names its own key: `rateLimited(perAddress) { header("X-Forwarded-For") }`.
- **Checked after the caller and tenant, before the body**, so a refused request costs no decoding. A 429 carries
  `Retry-After` in whole seconds, rounded up, and the refusal envelope tag `rate_limited`. `onRefusal` hears it as
  `RefusalReason.RATE_LIMITED`.
- **Document and client:** the 429 is declared with its `Retry-After` header, and the operation's description says
  the limit ("100 requests a minute per caller"). The generated client's failure gains `retryAfter: Long`.

## Why this shape

The limit is declared on the endpoint, like the caller and the tenant, so the route, the document and the client read
one value, and the key is built from what those already resolved rather than from a second reading of the request. A
filter would be the shape the service has today. Option A, a named limit an endpoint opts into, recommended. Option
B, one limit on the whole `Api`, is simpler but cannot tell a cheap read from an expensive write.

## Stack

- [ ] **`spec-0096-limit`**: `rateLimit(...)`, `rateLimited(...)`, `RateLimiter`, `InMemoryRateLimiter`,
      `rateLimits(...)`, the key, the 429 and `Retry-After`. Done when: served in process, the 101st call in a minute
      is a 429 naming when to come back, and another caller or tenant is unaffected.
- [ ] **`spec-0096-refusal`**: `RefusalReason.RATE_LIMITED`, and the 429 declared in the refusal envelope.
      Done when: `onRefusal` hears it, and the endpoint's errors list it unless it declares its own 429.
- [ ] **`spec-0096-document`**: the 429 and its header in the document, the limit in the description, `retryAfter` on
      the generated client's failure. Done when: a golden pins it and `RetryingClientTest` waits what it names.
- [ ] **`spec-0096-parity`**: a limited endpoint in the backends example. Done when: every backend answers alike.
- [ ] **`spec-0096-docs`**: reference, cookbook, changelog.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Which algorithm does `InMemoryRateLimiter` use?** Recommend GCRA, a token bucket kept as one instant per key: it
   spreads the budget over the period rather than letting it all go at the top of a minute, and it is one value a
   persistent limiter can compare-and-set. A fixed window is simpler to read and lets a caller spend double across a
   window edge.
2. **What does a limiter that cannot answer do?** Recommend letting the request through and reporting the failure to
   `onServerError`, since a limiter protects capacity rather than data, and an outage of it should not become an
   outage of the service. Failing closed is the alternative, for a service whose limit is a contract.
3. **What keys an endpoint with no caller and no tenant?** Recommend refusing to build it unless the endpoint names a
   key, since no backend exposes the client's address today and a header a proxy writes is the service's to trust.
   Exposing the socket address through every backend is the alternative, and a spec of its own.
4. **Does the limit count refused requests?** Recommend only requests that reach the check, which is after the
   caller, tenant, `requires` and cross-site checks, so a 401 flood cannot spend a real caller's budget.
