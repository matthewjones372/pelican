# 0075 — No Pekko on the classpath

## Problem

After 0073 and 0074 a Jetty service still needs Pekko for one thing: the only
client transport, `PekkoHttpTransport`, takes an `ActorSystem`. Petshop's
registry client is that case. And nothing proves a Jetty service is free of
Pekko: one stray `api` dependency would put it back unnoticed.

## Not doing

- **A Jetty `HttpClient` transport.** The JDK's client needs no dependency.
- **OkHttp and Ktor clients.** They return as restores, later.

## Shape

```kotlin
val registry = ChipRegistryClient(JavaHttpTransport().traced(telemetry), baseUrl, JacksonCodecs())
```

- `pelican-client-java` restored from `a6c20b2` (`JavaHttpTransport`, 95
  lines), caught up with `main`: spec 0052's cancellation as an `IOException`,
  and whatever `ClientTransport` gained since.
- A test resolving the runtime classpath of `pelican-jetty`, `-docs`, `-mcp`,
  `pelican-test-jetty`, `pelican-streams-jetty`, `pelican-client-java`,
  `pelican-jackson`, health, metrics and oidc, failing on any
  `org.apache.pekko` or `scala-library` jar.

## Why this shape

`java.net.http.HttpClient` ships with the JDK, so the restore adds nothing to
the classpath and is code that already passed the client parity suite. A
Jetty client would match the server but is a second artifact to build and own.
Recommended: the restore.

## Stack

- [ ] **`spec-0075-client`**: `pelican-client-java`, caught up, in the client parity suite.
      Done when: the parity suite passes for both transports.
- [ ] **`spec-0075-classpath`**: the classpath test.
      Done when: it passes, and fails if `pelican-pekko` is added to the set.

## Acceptance

```bash
./gradlew build
```

## Decisions

Matt delegated the open questions on 2026-10-14; each took its recommendation.

1. **The JDK client, restored**, not a Jetty client.
