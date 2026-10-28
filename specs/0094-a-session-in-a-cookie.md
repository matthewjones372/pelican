# 0094 — A session in a cookie

## Problem

A browser calls with a cookie, not a bearer token. Pelican's one session today is `pelican-oidc`'s sealed cookie,
which needs no store but cannot be revoked before it expires and only exists behind an OIDC sign-in. A service with
its own login, or one that must end a session now (sign out everywhere, a stolen laptop), has nowhere to keep
sessions and no scheme that reads one. Every `Set-Cookie` is written by hand: the oidc module has one private
`cookie(...)` string builder. And a cookie-authenticated POST is open to cross-site request forgery: nothing checks
where it came from.

## Not doing

- **Login itself.** How a person proves who they are (password, passkey, OIDC) is the service's; Pelican starts a
  session once the service says who it is, and reads it back after.
- **A persistent store.** `SessionStore` is an interface with an in-memory implementation; Surefold's is elsewhere.
- **Replacing the oidc sealed cookie.** It stays; a service may use either.

## Shape

```kotlin
val sessions = sessionCookie(name = "sid", store = InMemorySessionStore(), idle = 30.minutes, absolute = 12.hours)

api(routes, JacksonCodecs) {
    authenticate(sessions.scheme, by = sessions)      // an Authenticator, so authenticatedBy works unchanged
    csrf(sessions)                                     // cookie-authenticated, state-changing requests checked
}

// in a sign-in handler, once the service has decided who this is:
setCookie(sessions.start(Identity("ada")))            // HttpOnly; Secure; SameSite=Lax; Path=/
setCookie(sessions.end(credentials))                  // revoked in the store, and the cookie cleared
```

- **`SessionStore`**: `create(identity, expires)`, `lookup(id)`, `touch(id, expires)`, `revoke(id)`,
  `revokeAll(subject)`. Ids are 256 random bits; the store keeps a hash of each, never the id itself.
- **`ResponseCookie`** with safe defaults (`HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`), refusing `SameSite=None`
  without `Secure` and a `__Host-` name with a `Domain`. The oidc module's cookies move onto it.
- **CSRF**: a POST, PUT, PATCH or DELETE authenticated by a session cookie is refused with 403 unless its `Origin`, or
  failing that `Sec-Fetch-Site`, says same-origin or an origin the `Api`'s CORS policy allows. Bearer and API-key
  requests are not checked: a browser does not attach those by itself. The 403 is declared.
- The session cookie is an `apiKeyCookie` scheme in the document.

## Why this shape

A session is one more `Authenticator`, so everything spec 0061 and 0093 built (the 401, `requires`, the document)
works on it unchanged. For CSRF, option A checks `Origin`/`Sec-Fetch-Site`: no token, no state, and every browser
Pelican's CORS support assumes sends them. Option B is a double-submit token, which works in browsers that send
neither but needs a cookie, a header and client code on every form. Recommended: A, with B later only if asked for.

## Stack

- [x] **`spec-0094-cookies`** ([#399](https://github.com/matthewjones372/pelican/pull/399)): `ResponseCookie` and `setCookie`; the oidc module moved onto it.
      Done when: the refusals are tested, and oidc's `Set-Cookie` headers are byte-for-byte unchanged.
- [x] **`spec-0094-sessions`** ([#400](https://github.com/matthewjones372/pelican/pull/400)): `SessionStore`, `InMemorySessionStore`, `sessionCookie`, start, end, idle and
      absolute expiry. Done when: served in process, a revoked or expired session is a 401.
- [x] **`spec-0094-sessions-parity`** ([#401](https://github.com/matthewjones372/pelican/pull/401); split from the entry above, which ran past the soft cap): a session endpoint in
      the backends example. Done when: a revoked or expired session is a 401 on every backend.
- [x] **`spec-0094-csrf`** ([#402](https://github.com/matthewjones372/pelican/pull/402)): `csrf(...)`, the declared 403. Done when: the parity suite refuses a cross-origin
      cookie POST and lets the same POST through with a bearer token, on every backend.
- [x] **`spec-0094-docs`** ([#403](https://github.com/matthewjones372/pelican/pull/403)): reference, cookbook, changelog.

## Acceptance

```bash
./gradlew build
```

## Decisions

Matt took each recommendation on 2026-10-27.

1. **The store keeps a SHA-256 of each id**, so a leaked store is not a set of live sessions.
2. **Idle expiry slides at most once a minute per session**, so a busy page is not a store write per request.
3. **CSRF is on by default for a session cookie**: `csrf(sessions)` is implied unless turned off, since a cookie
   session without it is the bug.
4. **Two specs.** This is feature 2's second half, kept apart from 0093 so each stays a page.
