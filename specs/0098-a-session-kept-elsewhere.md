# 0098 — A session kept elsewhere

Builds on 0093 (who may call) and 0094 (a session in a cookie), both on their `spec-0093-*` branches and not
yet on `main`. It changes 0094's store before `spec-0094-sessions` is built.

## Problem

Surefold's `surefold-auth` 0.14.0 keeps sessions in Postgres behind `Sessions`: it mints the token, slides the idle
expiry itself, `rotate`s, and resolves a token to a `Principal` with the roles and permissions of its tenant. Its
`docs/auth.md` says "Pelican is adding a `SessionResolver`" for the cookie, CSRF and per-endpoint permission. 0093
decided that `Authenticator` already is that resolver, and 0094 designs a `SessionStore` where Pelican mints the id
and the store keeps a hash and is `touch`ed. Surefold's store fits neither half: it mints its own tokens and has no
`touch`. Doorlist, on Pelican 1.2.0, hand-rolls all of it: `SessionAuthenticator` reads `__Host-dl_staff`, compares
an `X-CSRF-Token` with a secret from the session row, checks `Origin` against a list, and every handler that rotates
writes its own `Set-Cookie` (dependencies.md item 5).

## Not doing

- **Login, TOTP, step-up.** The service's. Doorlist's second-step gate stays an `Authenticator` wrapping this one.
- **The Surefold adapter.** `surefold-pelican-sessions` lives in Surefold, as `surefold-pelican-idempotency` does
  (Surefold spec 0230). Pelican depends on no Surefold module; `surefold-auth` stays free of HTTP.
- **Rotation on a timer.** A handler rotates after sign-in and after a privilege change, as Surefold's docs say.
- **`requires`, roles and the 403.** 0093, unchanged.

## Shape

```kotlin
interface SessionStore {                                   // replaces 0094's before it is built
    fun start(identity: Identity): StartedSession          // the store mints the token; Pelican sees it once
    fun resolve(token: String): Identity?                  // slides the idle expiry itself, or does not
    fun rotate(token: String): String?                     // a new token, the old one dead; null if it has ended
    fun end(token: String): Boolean
    fun endAll(subject: String): Int
}

val sessions = sessionCookie(store, csrfKey = key)        // "__Host-session"; HttpOnly; Secure; SameSite=Lax; Path=/

api(routes, codecs) { authenticate(sessions.scheme, by = sessions) }

setCookies(sessions.rotate(this))                          // in a handler: the session cookie and its CSRF cookie
```

- **Identity, not Principal.** The adapter maps Surefold's `Principal` to `Identity(subject = user, groups = roles,
  claims = { "scope": permissions, "tenant": tenant })`, so 0093's `role(...)` and `permission(...)` read it as they
  read a token. `InMemorySessionStore` is Pelican's, for tests and one instance.
- **`__Host-` by default.** The name starts `__Host-`, so 0094's `ResponseCookie` refuses a `Domain` and a `Path`
  other than `/`. Browsers keep a `Secure` cookie on `http://localhost`, so local development still works.
- **CSRF token, beside 0094's `Origin` check.** A signed double-submit token: a second cookie, `__Host-csrf`, not
  `HttpOnly`, holding HMAC-SHA256(`csrfKey`, session token). A cookie-authenticated POST, PUT, PATCH or DELETE must
  send it back as `X-CSRF-Token`, compared in constant time. `start` and `rotate` set both cookies, so a rotated
  session carries its new token with it. Bearer and API-key requests are not checked, as in 0094.
- **The document.** The session scheme stays `apiKey` in a cookie. Under 3.1 a requirement on a non-OAuth scheme may
  list role names, so an endpoint's `requires(permission(...))` goes in its requirement array as well as in 0093's
  "Requires" sentence. The CSRF header is a header parameter on each state-changing operation the scheme guards.

## Why this shape

The store mints the token because Surefold's does, and a port Surefold cannot implement is no port. For CSRF, option
A is a synchronizer token: a secret per session, kept in the store, handed to the page by an endpoint. It is what
Doorlist does, but needs a column Surefold's `surefold_sessions` lacks and a fetch after every rotation. Option B,
the signed double-submit, stores nothing, and the HMAC binds the token to one session, so a cookie planted from a
sibling subdomain cannot match (and `__Host-` stops it being planted). Recommended: B, layered on 0094's `Origin`
check, not replacing it.

## Stack

- [ ] **`spec-0098-store`**: `SessionStore` as above, `StartedSession`, `InMemorySessionStore` with an injectable
      clock; 0094's `spec-0094-sessions` builds on it. Done when: rotate kills the old token, and end, endAll and
      expiry are tested against a contract suite a store in another repository can run.
- [ ] **`spec-0098-rotate`**: `sessions.rotate`, `setCookies`, the `__Host-` default; on 0101's repeated `Set-Cookie`.
      Done when: on every backend a rotated session answers two `Set-Cookie` headers, and the old cookie is a 401.
- [ ] **`spec-0098-csrf-token`**: the signed token, `csrfKey`, the `X-CSRF-Token` check and its declared 403.
      Done when: the parity suite refuses a cookie POST with no token, or another session's, and passes a bearer POST.
- [ ] **`spec-0098-document`**: permissions in the requirement array, the CSRF header parameter.
      Done when: a golden pins both and `OpenApiSpecQualityTest` reads it.
- [ ] **`spec-0098-docs`**: reference, cookbook, changelog; how a Surefold `Sessions` plugs in.

## Acceptance

```bash
./gradlew build   # parity suite, goldens, OpenApiSpecQualityTest
```

## Open questions

1. **Amend 0094 instead?** 0094's decisions are recorded but its store is unbuilt. Recommended: a spec of its own
   that replaces 0094's store entry, so 0094's record stays as decided.
2. **Is the CSRF token on by default?** 0094 makes the `Origin` check implied. Recommended: the token is opt-in with
   `csrfKey`; without a key, `Origin` alone, as 0094 decided.
3. **Should `resolve` see the request?** Doorlist's stream check reads a session without sliding it. Recommended: no;
   a service needing that binds a second `Authenticator` over its own store call, as Doorlist does today.
4. **Permissions in the requirement array** could surprise a reader expecting OAuth scopes. Recommended: emit them,
   since 3.1 names exactly this use, and keep the sentence.
