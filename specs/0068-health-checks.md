# 0068 — Health checks

## Problem

Every Pelican service writes its own health endpoint: `health handledNow { "ok" }`
in the reference, then a hand-rolled database ping, a timeout someone forgot, a
200 that hides a dead pool. No two services answer the same shape, so a probe
config, a dashboard or an on-call runbook is written once per service. A slow
dependency hangs the probe, and a check on the liveness path restarts pods
during a database blip.

## Not doing

- **Metrics.** Check outcomes as meters is `pelican-metrics`' job, later.
- **Caching or background polling.** Checks run when a probe asks.
- **Kafka and Redis.** Third-party clients are leaf modules, spec 0069.
- **A startup probe, or a UI.** Live and ready only; the body is JSON.
- **Changing pelican-core.** Health is built from ordinary endpoints.

## Shape

```kotlin
import io.github.matthewjones372.pelican.health.diskSpace
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.health.http
import io.github.matthewjones372.pelican.health.jdbc
import io.github.matthewjones372.pelican.health.noDeadlockedThreads

val health = health {
    live("threads") { noDeadlockedThreads() }
    ready("orders-db") { jdbc(dataSource) }
    ready("payments", timeout = 500.milliseconds) { http("https://payments.internal/health/ready") }
    ready("disk", critical = false) { diskSpace("/var/data", minFree = 1.gigabytes) }
}

val api = api(endpoints = health.endpoints + routes, codecs = JacksonCodecs)
```

- `pelican-health`, depending on `pelican-core` and nothing else.
- `GET /health/live` and `GET /health/ready`: `noSecurity()`, tagged `health`,
  in the OpenAPI document with a typed body. Live runs only `live` checks;
  ready runs both. `health(prefix = "api")` mounts them under `/api`.
- A check is `fun interface Check { fun check(): Status }`, `Status` sealed:
  `Up` or `Down(reason: String)`. Any lambda is a check.
- Checks run in parallel on virtual threads, each under its own timeout
  (default 2s). Past it: `Down("timed out after 2s")`, never a hung probe.
- 200 when every critical check is up, 503 otherwise. A non-critical check
  shows `DOWN` in the body and leaves the status 200.
- Body: `{"status":"UP","checks":{"orders-db":{"status":"UP","durationMs":3}}}`.
  `reason` is included only with `health(detail = true)`: driver errors name
  hosts, and the endpoint is public.
- Built in, JDK only: `jdbc(DataSource)` (`Connection.isValid`), `http(url)`
  (JDK client, 2xx is up), `diskSpace(path, minFree)`, `heapHeadroom(min)`,
  `noDeadlockedThreads()`.

## Why this shape

Plain endpoints, not a new interpreter hook, so one `health { }` means the same
on Pekko, Ktor and http4k, and the typed test client calls it like any route.
Live and ready are separate so a dependency outage drains traffic without
restarting the process. A check that throws is `Down` with the exception's
class name: catching `Exception` at that one boundary is the point of a health
check, and `Error` still propagates. The alternative, a `Filter` answering
`/health` before routing, skips the document and the test client.

## Stack

- [ ] **`spec-0068-health`** — module, `Check`, `Status`, `health { }`, the two
      endpoints, parallel bounded run, body, 200/503, `critical`, `detail`.
      Done when: a check that sleeps past its timeout answers 503 within
      timeout + margin, and a failing non-critical check still answers 200.
- [ ] **`spec-0068-jvm`** — `diskSpace`, `heapHeadroom`, `noDeadlockedThreads`.
      Done when: a test that deadlocks two threads gets `Down` naming them.
- [ ] **`spec-0068-jdbc`** — `jdbc(dataSource, timeout)`.
      Done when: against H2, up while open and down after the pool closes.
- [ ] **`spec-0068-http`** — `http(url)` for a downstream dependency.
      Done when: against WireMock, 200 is up, 503 and connect-refused are down.
- [ ] **`spec-0068-docs`** — reference, cookbook, `example/health`, README row.
      Done when: the example's test calls both probes through the typed client.

## Acceptance

```bash
./gradlew :pelican-health:check :example:build
```

## Decisions

Each open question in the draft took its recommendation.

1. **Paths:** `/health/live` and `/health/ready`, under an optional
   `health(prefix = "api")` for services mounted below the root.
2. **Ready includes live:** yes. Not alive is never ready.
3. **Probe storms:** no cache in v1. A 1s memo is added if a database
   complains, after measuring.
4. **Check names:** `[a-z0-9-]+` and unique, checked when `health { }` is
   built, so a duplicate is an error rather than a silently dropped key.
