# 0061 — Who is calling

## Problem

A Pelican endpoint cannot say who called it. A service that needs to know writes a `Filter` that reads a header,
verifies a token itself, and throws to refuse; then it has nowhere typed to put the answer, so each handler reads the
header again or reaches for a thread-local. The OpenAPI document says nothing of it unless the service also
declares a `SecurityRequirement` by hand, which can then disagree with what the filter does.

lark-bank needs this now, for every endpoint: customers, support staff and ops calling the same API, each allowed
different things, signed in through an OpenID Connect provider (Pocket ID). bank-checks and bank-approvals will need
the same. Three services writing the same token verification is three chances to get it subtly wrong.

## Not doing

- **Authorisation.** Pelican says *who* is calling, verified. What they may do is the service's decision, in its own
  code, with its own data: a handler is handed the caller and decides.
- **Being an identity provider.** No users, passwords or token issuing. Pelican verifies what a provider issued.
- **Every OAuth flow.** Bearer tokens for APIs and the authorization-code flow for pages; no implicit or password
  flows, no device flow.
- **Storing sessions.** A page's session is a signed, encrypted cookie; nothing server-side to share between nodes.

## Shape

**A caller is an input**, like a path parameter: declared on the endpoint, typed, and in the OpenAPI document.

```kotlin
// The service's own type, built from a verified identity.
data class Caller(val subject: String, val name: String, val groups: Set<String>)

val caller = authenticated(bearerOrSession) { identity -> Caller(identity.subject, identity.name, identity.groups) }

val getAccount = endpoint(caller, accountId) {
    get("accounts" / accountId)
    json<AccountView>().orFail(accountMissing, forbidden)
}

getAccount handledOrFail { (who, id) ->
    if (!who.owns(id)) forbidden(...) else ok(...)
}
```

- A request with no credential, or one that fails verification, is refused with 401 before the handler runs, in the
  service's refusal envelope, with `WWW-Authenticate`. The handler only ever sees a verified caller.
- `authenticated(...).optional()` hands over `Caller?` for endpoints open to anyone that answer more to someone signed
  in.
- The document gets the scheme and the requirement from the same value: `openIdConnect` with the provider's discovery
  URL, and each endpoint that takes `caller` requires it. They cannot disagree.

**Verifying tokens from an OpenID Connect provider**, in a module of its own, `pelican-oidc`:

```kotlin
val bearerOrSession = oidc(
    issuer = "https://id.home.arpa",          // discovery, and its signing keys, fetched and cached
    audience = "lark-bank",
    groupsClaim = "groups",
)
```

It checks the signature against the provider's published keys (refetched on an unknown key id, at most once a minute),
the issuer, the audience, expiry with a small leeway, and the algorithm against an allow-list (never `none`, never HMAC
with a public key).

**Signing people in to pages**: `oidc.pages()` adds `/login`, `/callback` and `/logout` beside a service's pages (spec
0059): the authorization-code flow with PKCE, then a session cookie (`HttpOnly`, `Secure`, `SameSite=Lax`, signed and
encrypted with a key the service holds), which `bearerOrSession` accepts as it accepts a bearer token. A page asked for
by someone not signed in is redirected to `/login` and back.

**Acting as someone else.** An identity may carry an *actor*: `identity.actor` is who is really there when the subject
is someone they are acting as (RFC 8693's `act` claim, or the session's own). Pelican verifies it and hands it over;
whether acting-as is allowed, and for what, is the service's decision.

**Tests** call an endpoint as anyone, without a provider:

```kotlin
val app = bankApi().inMemory(callers = TestCallers.of("ada" to setOf("customer")))
app.call(getAccount, "acc-1", as = "ada")
```

## Why this shape

An input, rather than a filter, is what makes the caller typed in the handler and present in the document from one
declaration. Pelican already treats everything a handler needs from a request that way. Verification in its own module
keeps pelican-core's runtime classpath the Kotlin standard library, which is a test. The alternative was a `Filter`
that stores the caller in a request attribute: less new surface, but untyped, invisible to the document, and easy to
forget on one endpoint.

## Depends on

Nothing in Pelican's current modules beyond pages (spec 0059) for the sign-in routes.

## Stack

- [ ] **`spec-0061-caller`** — `authenticated(...)`, the caller input, 401 before the handler, `optional()`, the
      scheme and requirement in the document, and `inMemory(callers = ...)` for tests.
      Done when: an endpoint taking a caller refuses a request without one with 401 and its handler never runs, and the
      document requires the scheme on that endpoint only.
- [ ] **`spec-0061-oidc`** — `pelican-oidc`: discovery, cached keys, signature, issuer, audience, expiry and algorithm
      checks, groups from a claim.
      Done when: tests against a local signing key refuse an expired token, a wrong audience, an unknown key id twice,
      `alg: none` and an HMAC token signed with the public key, and accept a valid one.
- [ ] **`spec-0061-pages`** — `/login`, `/callback` and `/logout`, PKCE, the encrypted session cookie.
      Done when: a Playwright test against a stub provider signs in, sees a page that shows the caller's name, signs
      out, and is sent back to `/login`.
- [ ] **`spec-0061-actor`** — the actor claim, verified and handed over.
      Done when: a token with `act` gives an identity whose subject and actor differ, and one without gives the same.

## Acceptance

```bash
./gradlew :pelican-core:check :pelican-oidc:check :example:build
```

## Open questions

1. **Which JWT library?** Recommended: Nimbus JOSE + JWT, the de facto Java choice, in `pelican-oidc` only. Alternative:
   verify with the JDK's own `java.security` and a small parser, which is less to depend on and more to get right.
2. **Sessions for pages: cookie or server-side?** Recommended: an encrypted cookie, so nothing is shared between nodes.
   Alternative: a session id and a store, which can be revoked at once.
3. **Refresh tokens?** Recommended: not kept; a session lasts as long as the provider's token, then the person signs in
   again (silently, if the provider's own session is still alive).
4. **Where does acting-as start?** Recommended: Pelican verifies an `act` claim and hands it over; minting such a
   session (after an approved grant) is the service's, through `oidc.actAs(subject, grant)`.
