# 0093 — Who may call

## Problem

Spec 0061 says who is calling; nothing says what they may do. A service that needs a role writes a filter, as
`example/secured` does, or checks in every handler and throws `forbidden(...)`. Neither reaches the document: a 401
from `authenticatedBy` and a 403 from a filter both land under `default`, so a generated client cannot tell "sign in"
from "not allowed" from "the server broke" without reading the body. And nothing holds the claim that a credential
never reaches a log line, a span or a refusal body; today that is true by accident.

## Not doing

- **Being a policy engine.** Roles and permissions are sets of strings matched against the caller; anything that needs
  the resource (does Ada own order 7?) stays in the handler, which already has `forbidden(...)`.
- **Sessions, cookies and CSRF.** Spec 0094.
- **Changing what `Authenticator` is.** It already turns a request into an `Identity` or nothing, which is the
  `SessionResolver` the brief asks for; a second name for it would be two ways to say one thing.

## Shape

```kotlin
val support = authenticated(bearer) { Staff(it.subject, it.groups) }

val refund = endpoint(orderId) {
    post("orders" / orderId / "refund")
    authenticatedBy(support)
    requires(role("support"), permission("orders:refund"))   // all of them; anyOf(...) for either
    json<Refund>()
}

api(routes, JacksonCodecs) { authenticate(bearer, by = verifier) }
```

- **401 and 403 are declared** by `authenticatedBy` and `requires`: listed in the document under the scheme's
  requirement, in the refusal envelope, with `WWW-Authenticate` on the 401. The handler never produces either.
- **`role(name)`** matches `Identity.groups`; **`permission(name)`** matches the space-separated `scope` claim
  (or `scp`, as a list). Both are checked after the caller, before any other input is decoded, so a forbidden caller
  learns nothing else about the endpoint.
- **The document** puts permissions on the requirement as OAuth scopes where the scheme declares them, and lists
  roles in the operation's description under "Requires", since OpenAPI has no field for them.
- **No credential in what Pelican writes:** a test sends a bearer token, an API key and a session cookie through every
  backend with `pelican-metrics-otel` and the example request log, and asserts none of them appears in a span
  attribute, a log line or any refusal body.

## Why this shape

Roles and permissions belong next to `authenticatedBy` for the reason the caller does: one declaration that the route
enforces and the document publishes. A filter reading `endpoint.security` (today's `example/secured`) works but is a
second list beside the first, and its 403 cannot be declared. Option A, `requires(...)` on the endpoint, recommended.
Option B, a typed `Authorizer<Caller>` bound on the `Api` like `Authenticator`, can express any policy but cannot be
published; it could come later behind A.

## Stack

- [ ] **`spec-0093-refusals`**: the 401 that `authenticatedBy` already raises, declared and in the document.
      Done when: a golden shows the 401 with `WWW-Authenticate`, and the generated client reaches it by status.
- [ ] **`spec-0093-requires`**: `requires`, `role`, `permission`, `anyOf`; the 403, enforced and declared.
      Done when: the parity suite refuses a caller missing a role on every backend, before the body is read.
- [ ] **`spec-0093-redaction`**: the no-credentials test across backends, spans, the example log and refusal bodies,
      and whatever it finds fixed. Done when: it passes, and fails if a token is put in an `Unauthenticated` message.
- [ ] **`spec-0093-docs`**: reference, cookbook, changelog; `example/secured` rewritten on `requires`.

## Acceptance

```bash
./gradlew build
```

## Decisions

Matt took each recommendation on 2026-10-27.

1. **`requires(a, b)` means all of them**, with `anyOf(a, b)` for either. OpenAPI reads a list of requirements as
   "any", so the document says it in words where the two disagree.
2. **`permission` reads the `scope` claim, then `scp`**, as RFC 9068 and most providers write them. A provider that
   puts them elsewhere maps them in its `Authenticator`.
3. **An optional caller who is absent is a 401**, not a 403, since there is nobody to forbid.
4. **Declaring the 401 is taken** as a compatible change (a new documented status) to every document with a caller
   in it, with the goldens it moves read and accepted in `spec-0093-refusals`.
