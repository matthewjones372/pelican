# 0075 — No Pekko on the classpath

## Problem

After 0073 and 0074 a Jetty service still needs Pekko for one thing: the only
released client transport, `PekkoHttpTransport`, takes an `ActorSystem`.
Petshop's registry client is that case. And nothing proves a Jetty service is
free of Pekko: one stray `api` dependency would put it back unnoticed.

## Not doing

- **The JDK's `HttpClient`.** `pelican-client-java` was restored by this spec's
  first entry (#258) and leaves again before any release: one non-Pekko client
  ships, and it is Jetty's.
- **OkHttp and Ktor clients.** They return as restores, later.

## Shape

```kotlin
val registry = ChipRegistryClient(JettyHttpTransport(httpClient).traced(telemetry), baseUrl, JacksonCodecs())
```

- `pelican-client-jetty`: `JettyHttpTransport` on Jetty's
  `org.eclipse.jetty.client.HttpClient`, the same Jetty release as
  `pelican-jetty`. It keeps the contract `JavaHttpTransport` was brought up to:
  a caller's cancel closes the exchange, other cancellation is an
  `IOException` (spec 0052), a declared `Content-Length` is sent as declared,
  the per-request timeout covers only the wait for the response head, and a
  timeout is a `TimeoutException`, never retried by the default policy.
- A no-argument constructor owns and stops its `HttpClient`; one taking an
  `HttpClient` borrows it, so a service can share the server's thread pool.
- `pelican-client-java` removed, with its parity subclass, docs and CHANGELOG
  line.
- A test resolving the runtime classpath of `pelican-jetty`, `-docs`, `-mcp`,
  `pelican-test-jetty`, `pelican-streams-jetty`, `pelican-client-jetty`,
  `pelican-jackson`, `pelican-kotlinx`, health, metrics and oidc, failing on
  any `org.apache.pekko` or `scala-library` jar.

## Why this shape

One HTTP stack: the client shares `jetty-io` and `jetty-util` with the server,
can share its threads, and has the pool, timeout and streaming controls the
JDK client lacks. The JDK client needs no jar, but two non-Pekko clients is
two contracts to keep. Matt chose Jetty's on 2026-10-14.

## Stack

- [x] **`spec-0075-client`**: `pelican-client-java`, caught up (#258). Superseded by the entry below.
- [ ] **`spec-0075-jetty-client`**: `pelican-client-jetty` in the client parity suite; `pelican-client-java` removed.
      Done when: the parity suite passes for Pekko and Jetty, and `JavaHttpTransportTest`'s cases pass ported to Jetty.
- [ ] **`spec-0075-classpath`**: the classpath test.
      Done when: it passes, and fails if `pelican-pekko` is added to the set.

## Acceptance

```bash
./gradlew build
```

## Decisions

1. **Jetty's client, and only Jetty's**, chosen by Matt on 2026-10-14; the JDK
   client restored in #258 is removed before it is released.
