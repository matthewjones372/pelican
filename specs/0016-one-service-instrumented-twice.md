# 0016 — One service, instrumented twice

## Problem

`example/metrics` and `example/tracing` each make the argument well and each
make it alone. Between them they describe two services, four endpoints apiece,
and the thing a reader most wants to see is the thing neither shows: **the same
descriptions driving both instruments, agreeing.**

What the examples cannot demonstrate as they stand:

- **Nothing takes any time.** Every handler returns a literal, so the timer and
  the duration histogram record noise; a reader cannot tell a p99 from a typo.
- **One failure mode.** A declared 404, and in the tracing example a throw. No
  filter refusal, no slow call — so "which endpoint is failing, and how" has one
  answer and needs no dashboard to find it.
- **The output is a dump.** `meterTable` prints every meter. It shows *that*
  tags exist; not the three questions a dashboard is built from — how often, how
  slow, how often wrong — per operation.
- **The two never meet.** A metric says an endpoint is slow; a trace says which
  part of it was. That handover is why a service runs both, and there is nowhere
  here to see it.

## Not doing

- **No Prometheus, no OTLP exporter, no collector.** A second dependency to make
  a point the `SimpleMeterRegistry` and the in-memory span exporter already
  make, and a docker-compose nobody runs.
- No Grafana screenshots in `docs/`.
- No new module. This is `example/`, which is not published.
- No change to `pelican-metrics` or `pelican-metrics-otel`. If the example
  cannot show something, that is a finding for a spec of its own, not a reason
  to widen a filter.
- Not deleting `example/tracing`'s `pekkoHeaders` note. Continuing an inbound
  trace is the one thing a backend-agnostic filter cannot do, and it is the most
  useful paragraph in either file.

## Shape

One service under `example/telemetry`, both filters, endpoints chosen so the
report is worth reading:

```kotlin
filters = listOf(
    metrics(registry),
    openTelemetry(sdk, incomingHeaders = pekkoHeaders),
    gate,                       // refuses without a key: a 403 the meters see
)
```

| endpoint | what it is there to show |
|---|---|
| `fetchOrder` | fast, and a declared 404 — a failure that is not an error |
| `searchOrders` | deliberately slow and variable, so p99 differs from mean |
| `placeOrder` | 201, and a nested span for the "database" call inside it |
| `fetchReceipt` | throws — the one case that marks a span an error |
| `listOrdersV1` | deprecated, still served, still counted |

and a report that answers the questions rather than dumping the registry:

```
operation      calls  errors  p50     p99
fetchOrder        14      2   0.4ms   1.1ms
searchOrders       6      0   38ms    210ms      <- slow: see trace 8f3c1a
placeOrder         3      0   2.1ms   2.4ms
```

with the last trace printed as a tree beneath it, so the handover from "this
operation is slow" to "this is where it went" is one page.

## Why this shape

The examples are already right about the argument; what they lack is a reason to
look at the output. Making one endpoint genuinely slow and one genuinely broken
costs a `Thread.sleep` and a `throw`, and turns a table nobody reads into a
report that points somewhere.

One service rather than two because the claim is that both instruments come from
one set of descriptions. Two files cannot make that claim; they can only each
assert half of it.

The alternative is to leave both files and add a third showing them together.
Not recommended: three examples of one idea is how a reader learns none of them,
and the two existing files are 349 lines that would then need keeping in step.

## Stack

- [ ] **`spec-0016-telemetry-service`** — `example/telemetry`: the descriptions, the five endpoints, both filters, the gate.
      Done when: `./gradlew :example:runTelemetry` serves all five, and one suite asserts each produces the status its description promises.
- [ ] **`spec-0016-the-report`** — calls, errors, p50 and p99 per operation, from the registry; the last trace as a tree.
      Done when: the slow endpoint's p99 is visibly apart from its p50, the thrown one shows as an error in both instruments, and the declared 404 shows as neither.
- [ ] **`spec-0016-retire-the-old-two`** — `example/metrics` and `example/tracing` deleted, their run tasks replaced, `docs/reference.md`'s Metrics and OpenTelemetry sections pointed at the new file.
      Done when: no link in `docs/` or `README.md` names a file that is gone, and `MeteredOrdersTest`/`TracedOrdersTest`'s claims all have a home in the new suite.

## Acceptance

```bash
./gradlew build
./gradlew :example:runTelemetry
```

## Open questions

1. Is a `Thread.sleep` in an example handler dishonest about what a real slow
   endpoint is? Recommend it with a comment saying it stands for a database
   call — the alternative is a fake repository with a sleep in it, which is the
   same sleep further away.
2. Should the report live in the example or become something `pelican-metrics`
   offers? Recommend the example: a registry summary is Micrometer's business
   and every backend already has one.
3. Does deleting both existing examples lose the per-backend claim? Neither is
   parameterised over backends today — both are Pekko — so no. Recommend
   deleting, and noting that spec 0012 is where backend parity is asserted.
4. p99 from a `SimpleMeterRegistry` needs percentile publishing turned on for
   the timer. Does `pelican-metrics` let a caller configure that, or does this
   spec need it to? Recommend finding out in entry 2 and raising a separate spec
   if the answer is no.
