# 0095 — Whose request is it

## Problem

A service that serves many tenants finds out which one a request is for in one of a few places: a subdomain
(`acme.example.com`), a header (`X-Tenant: acme`), or a claim on the caller's token. Pelican has none of them. A service
writes a filter that reads the header raw and puts a string in an attribute, as `FiltersAndHeadersTest` does, and then
nothing else knows: the document does not say the header is needed, a generated client has no parameter for it, an
idempotency key from tenant A replays for tenant B, and a token issued for tenant A is let into tenant B's endpoints.

## Not doing

- **Data isolation.** Which rows a tenant may read is the service's query, not Pelican's; Pelican says who, not what.
- **A tenant directory.** Whether `acme` exists is the service's lookup, behind an interface with an in-memory one for
  tests; Surefold's is elsewhere.
- **Per-tenant configuration** (limits, CORS, keys). Rate limiting is feature 4 and reads the tenant from here.
- **Tenant in a path segment.** `/t/{tenant}/orders` is already a path parameter; nothing to add.

## Shape

```kotlin
val tenant = tenantFrom(header("X-Tenant"))                 // or subdomain(of = "example.com"), or claim("tenant")

val listOrders = endpoint {
    get("orders")
    authenticatedBy(caller)
    tenanted(tenant)                                         // 400 without one, 404 for one the directory lacks
    jsonArray<Order>()
}

listOrders handledNow { orders.of(this[tenant]) }            // a Tenant(id), never a raw string

api(routes, JacksonCodecs) { tenants(tenant, directory = InMemoryTenantDirectory(setOf("acme", "globex"))) }
```

- **`TenantResolver`** turns a request into a tenant id or null: `header(name)`, `subdomain(of)` and `claim(name)` are
  built in, and a service may write its own. **`TenantDirectory`** says whether an id is a tenant at all.
- **Resolved before the body**, after the caller: a request for no tenant is a 400, for an unknown one a 404. Both
  declared, in the refusal envelope.
- **A caller from another tenant is a 403**: when the caller's identity carries the tenant claim and it disagrees.
- **The tenant scopes what keys on a caller**: an idempotency key and a session are each the tenant's own.
- **Document and client**: a header resolver is a required header parameter, and a parameter of the generated client;
  a subdomain resolver is a server variable, `https://{tenant}.example.com`; a claim resolver adds nothing.

## Why this shape

The tenant is declared on the endpoint like the caller, so the route, the document and the client read one value and
the checks that need it (idempotency, sessions, rate limits next) find it in one place. A filter writing an attribute
would be the shape the service has today. Option A, a resolver bound once on the `Api` with endpoints opting in,
recommended. Option B, resolving on every endpoint unless it opts out, is safer by default but turns every health
check and sign-in page into an exception to remember.

## Stack

- [x] **`spec-0095-resolve`** ([#405](https://github.com/matthewjones372/pelican/pull/405)): `TenantResolver`, the three built in, `TenantDirectory`, `tenants(...)`, `tenanted(...)`,
      `this[tenant]`, the 400 and 404. Done when: served in process, each resolver finds its tenant and refuses the rest.
- [x] **`spec-0095-document`** ([#406](https://github.com/matthewjones372/pelican/pull/406)): the header parameter, the server variable, the generated client's parameter.
      Done when: a golden pins both and the generated client sends the header.
- [x] **`spec-0095-scope`** ([#407](https://github.com/matthewjones372/pelican/pull/407)): the caller's tenant claim checked, idempotency keys scoped by tenant.
      Done when: a caller or a key from one tenant is neither let in nor replayed in another.
- [ ] **`spec-0095-sessions`** (split from scope, past the size cap): sessions scoped by tenant. Done when: a session
      started in one tenant reads as nobody in another.
- [ ] **`spec-0095-parity`**: a tenanted endpoint in the backends example. Done when: every backend answers alike.
- [ ] **`spec-0095-docs`**: reference, cookbook, changelog.

## Acceptance

```bash
./gradlew build
```

## Decisions

Matt took each recommendation on 2026-10-27.

1. **One resolver per `Api`** (option A); a service that finds its tenant two ways writes a resolver that tries both.
2. **No tenant is a 400; an unknown tenant is a 404.** A 404 says no more than the subdomain's DNS already does.
3. **The caller's tenant claim is checked by default**, with the claim named on `tenants(...)`, since a token for one
   tenant used against another is the bug this exists to stop.
4. **Idempotency keys and sessions are scoped by tenant**; a session started in one tenant reads as nobody in another,
   as an ended one does (spec 0094). A session started in no tenant, as an untenanted sign-in starts one, is read
   in every tenant; Matt took this recommendation on 2026-10-28.
