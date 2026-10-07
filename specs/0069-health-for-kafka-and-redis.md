# 0069 — Health checks for Kafka and Redis

## Problem

Spec 0068 gives every service `health { }` with JDK-only checks. A service on
Kafka or Redis still hand-writes those two, and they are the ones most often
written wrong: a Kafka producer that reports healthy because it has not tried
to send yet, a Redis check with no timeout.

## Not doing

- **Consumer lag.** Lag is a metric with a threshold someone has to choose, not
  an up/down. Later, if at all.
- **Owning the client.** The service passes the client it already built; the
  check never creates one or closes it.
- **Other stores.** Mongo, Cassandra, S3 and the rest wait for someone to ask.

## Shape

```kotlin
import io.github.matthewjones372.pelican.health.health
import io.github.matthewjones372.pelican.health.kafka.kafka
import io.github.matthewjones372.pelican.health.redis.redis

val health = health {
    ready("events") { kafka(adminClient) }
    ready("sessions") { redis(redisClient) }
}
```

- `pelican-health-kafka`: `kafka(Admin)` passes when `describeCluster()` returns
  a controller and at least one node within the check's timeout.
- `pelican-health-redis`: `redis(StatefulRedisConnection)` (Lettuce) passes
  when `PING` answers `PONG`.
- Each depends on `pelican-health` and its one client library, `compileOnly`,
  and its `NoOtherDependenciesTest` asserts that and no second stack.

## Why this shape

One leaf module per client keeps a Redis service from pulling in Kafka, which
is the rule `pelican-metrics-otel` already follows. Taking the service's own
client, rather than connection settings, means the check sees the same pool,
credentials and TLS as the code it vouches for.

## Depends on

Spec 0068, `spec-0068-health`.

## Stack

- [ ] **`spec-0069-kafka`** — `pelican-health-kafka`.
      Done when: against a Testcontainers broker, `Pass` while running and `Fail`
      within the timeout after the container stops.
- [ ] **`spec-0069-redis`** — `pelican-health-redis`.
      Done when: the same, against a Testcontainers Redis.

## Acceptance

```bash
./gradlew :pelican-health-kafka:check :pelican-health-redis:check
./gradlew :pelican-health-kafka:containerTests :pelican-health-redis:containerTests
```

## Decisions

Each open question in the draft took its recommendation.

1. **Redis client:** Lettuce, taking the service's `StatefulRedisConnection`.
2. **Testcontainers:** these tests are tagged `containers` and kept out of
   `check`, in a CI step of their own, so the build needs no Docker.
3. **Client versions:** `compileOnly`, so the service's version wins, with a
   test against the oldest supported Kafka 3.x.
