# 0103 — The caller's address, on every backend

## Problem

No backend gives a handler or filter the address its request came from. A service that limits per address, logs it
or checks it reaches into its backend: on Jetty, `Request.getRemoteAddr(p.request)` through `pelican-jetty`'s
extension over `Params.underlying`. That code then runs only on Jetty. Under the in-memory client, where
`p.request` is not a Jetty request, it throws, so a service written this way cannot be tested in memory. Spec 0096
already notes this: "Exposing the socket address through every backend is the alternative, and a spec of its own."

Behind a proxy the socket address is the proxy's. The real client is in a header only that proxy writes
(`CloudFront-Viewer-Address`, `X-Forwarded-For`). Each service decides which header to trust, and does it by hand.

## Not doing

- **Trusting a forwarding header by default.** A header any client can send is never the address unless the service
  names it, for the proxy it actually sits behind.
- **Geolocation, reverse DNS or anything else about the address.** It is a string.
- **A per-address limiter.** That is 0096's `rateLimited(perAddress)`; this only gives it a key.

## Shape

```kotlin
api(routes, JacksonCodecs) {
    // Behind a proxy: take the client from the header it writes. Without this, the socket peer.
    callerAddress(trustHeader = "CloudFront-Viewer-Address")
}

val audit = before { p -> log.info("${p.endpoint.name} from ${p.callerAddress}") }   // never null on a served request
```

- **`Params.callerAddress: String?`** is the socket peer by default, on Jetty, Pekko and Gannet alike. The in-memory
  transport answers `"127.0.0.1"`, or what the test sets:
  `inMemory(callerAddress = "203.0.113.7")`. A `Params` built by hand answers null.
- **`callerAddress(trustHeader = …)`** on the `Api` takes the address from that header's value when present: its
  first entry, without a port (`203.0.113.7:46532` reads `203.0.113.7`). When the header is absent it falls back to
  the socket peer, and the fallback is visible to `onRefusal` and metrics as the same string.
- **0096's `rateLimited(perAddress)`** can then key on it with no header of its own.

## Why this shape

One property on `Params`, filled the same way by every backend, is what makes a filter that reads it run unchanged
in memory, and that is the point. The trusted header is the `Api`'s, not each endpoint's, because a service sits
behind one proxy.

**Alternative:** leave the address to each backend's own request, as today, and give the in-memory transport a fake
Jetty request. It keeps services backend-specific, and fakes a type Pelican does not own. Recommended: the property.

## Stack

- [x] **`spec-0103-core`**: `Params.callerAddress` and `callerAddress(trustHeader)` on the `Api`, filled by Jetty and
      the in-memory transport. Done when: a filter reading it answers the same on both, and a hand-built `Params`
      reads null.
- [ ] **`spec-0103-backends`**: Pekko and Gannet fill it, and the cross-backend suite checks a trusted header and its
      fallback on every runnable backend. Done when: `BackendsTest` passes on all three.
- [ ] **`spec-0103-docs`**: the reference's Filters section and the changelog. Done when: the sample compiles in
      `DocsTest`.

## Acceptance

```bash
./gradlew build
```

## Decisions

Each recommendation was taken on 2026-10-28.

1. **The in-memory transport answers `127.0.0.1`** by default, as a loopback client would be.
2. **No brackets and no port**, so a key is the same however it arrived.
3. **One trusted header**, until a service needs two.
4. **The trusted header's address is the caller's address** wherever Pelican reads one; the peer is still on the
   backend's own request.
