# 0029 — The traffic the dashboards miss

*Post-1.0.*

## Problem

Requests refused before the filter chain — undecodable path params, oversize
bodies, undeclared content types, unmatched 404/405 — produce no measurement
(`Metrics.kt:79-82`, `Telemetry.kt:108-116`). The limitation is documented
(`reference.md:2790`), but the effect is that `http.server.requests`
under-counts exactly during an attack or a broken-client rollout — the
moments an operator reads the dashboard. An incident review that trusts the
counter concludes the 413 flood never happened.

## Not doing

- **No filters on the refusal path.** Refusals stay before user code; this
  is a counter, not an interception point.
- **No per-refusal cardinality.** No raw paths as tags — unmatched requests
  all count under one route label, or the metric becomes the attack surface.
- **No tracing spans for refusals.** Counters first; spans only if a real
  need shows up.

## Shape

```kotlin
// recorded by the interpreters, exposed by pelican-metrics
http.server.refusals{reason="unmatched|decode|body_limit|content_type|accept", status}
```

A small core hook — `RefusalObserver`, a value on `Api` — invoked at the
same three interpreter sites that render refusals. pelican-metrics and
pelican-metrics-otel implement it; the existing request metrics stay
untouched so no dashboard changes meaning.

## Why this shape

A separate counter keeps `http.server.requests` meaning what it has always
meant (requests that reached the pipeline) while making the missing traffic
visible under a name that says what it is. The alternative — folding
refusals into the request counter with a tag — silently changes every
existing dashboard's totals, which is the kind of surprise this library
exists to avoid.

## Stack

- [x] **`spec-0029-observer-hook`** — the core value, invoked from all three
      interpreters' refusal sites.
      Done when: a parity test sees the same observations for the same refusals on all three backends.
- [x] **`spec-0029-meters`** — the counter in pelican-metrics and the OTel
      equivalent; `docs/reference.md`'s metrics table updated.
      Done when: `MetricsAcrossBackendsTest` asserts the refusal counter for the 400/406/413 cases.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Does the observer see the matched endpoint when one exists (413 after
   routing)? Recommend yes — route template tag where known, constant
   `_unmatched` otherwise.
2. Is 500-undeclared a "refusal" here or already counted by the request
   metrics? It is counted today (the chain ran); recommend leaving it out
   to keep the two counters disjoint.
