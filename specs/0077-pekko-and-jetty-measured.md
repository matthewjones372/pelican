# 0077 — Pekko and Jetty, measured

## Problem

`docs/what-it-costs.md` prices Pelican on Pekko in process, with no socket.
With two servers a reader will ask which is faster, and the answer would be a
guess.

## Not doing

- **Other frameworks.** Pelican's two servers against each other only.
- **A gate on any number.** The results are published, not asserted.

## Shape

- `JettyOverheadBenchmark` (JMH): Pelican against hand-written Jetty core, GET
  and JSON POST, through `LocalConnector`, time and allocation with error.
- `:benchmarks:wire`, outside `build`: starts Pelican on Pekko and Pelican on
  Jetty with the same endpoints on loopback, and drives each at fixed rates
  from a Kotlin open-model driver on the JDK client, latency measured from each
  request's intended send time so a stall is not hidden. p50, p99 and p99.9 per
  server per rate.

## Why this shape

A driver in the build needs nothing installed and runs from one command. An
external tool (`oha`, `wrk2`) is better tested but is not on the machine and
would not be on CI. Recommended: the Kotlin driver.

## Stack

- [ ] **`spec-0077-jmh`**: `JettyOverheadBenchmark` and its table.
      Done when: `docs/what-it-costs.md` has it, and says `LocalConnector` parses HTTP and Pekko's `Route.function` does not.
- [ ] **`spec-0077-wire`**: the driver, the task and the table.
      Done when: `docs/what-it-costs.md` has p50/p99/p99.9 for both servers at each rate, the machine and the command.

## Acceptance

```bash
./gradlew :benchmarks:jmh -PbenchmarkArgs="Jetty"
./gradlew :benchmarks:wire
```

## Decisions

Matt delegated the open questions on 2026-10-14; each took its recommendation.
