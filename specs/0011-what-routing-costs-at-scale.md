# 0011 — What routing costs at scale

## Problem

`docs/what-it-costs.md` answers the question it set: interpreting a description
costs 75ns and 259 bytes against a hand-tuned http4k route, measured properly —
three forks, pinned heaps, allocation counted, a bimodal result reported as
bimodal rather than averaged.

Every benchmark in it uses a **single-endpoint API**. Matching is not measured
against the thing that varies in a real service.

Pekko's interpreter reduces its routes with `Directives.concat`, and http4k's
`routes(...)` tries them in order, so both are an ordered scan: a request runs a
`method` comparison and, when that passes, a full `matchPath` walk per candidate
until one matches. A 200-endpoint service's last-declared route pays that 200
times. Ktor is exempt — `Route.pelican` installs one Ktor route per endpoint and
lets Ktor's routing tree score them, and the KDoc says so — and Ktor has no
benchmark at all, so two of three backends are measured. (That KDoc credits a
`RoutingTest` which does not exist; the assertion lives in
`KtorInterpreterTest`.)

Nobody has been bitten by this. Nobody has looked either, and the house rule is
that cost is measured rather than argued about.

## Not doing

- No index, no trie, no change to routing, unless the numbers below ask for it.
  The third stack entry exists to be deleted in review if they do not.
- No change to `orderedEndpoints`'s literal-count sort. That is a correctness
  rule — `/orders/watch` must beat `/orders/{orderId}` — and not a performance
  one.
- No new benchmark dimensions. Same endpoint, same payload, same harness;
  only the number of decoys changes.

## Shape

The existing `GET /items/{itemId}?limit=` benchmark, declared last behind 0, 49
and 199 decoy endpoints of similar shape, plus the same three on Ktor:

```kotlin
@Param("1", "50", "200") var endpoints: Int = 1
```

and a section in `what-it-costs.md` reporting per-request cost against endpoint
count for all three backends, with the same error bars as everything else there.

If the curve argues for it, the change is a bucket index in `orderedEndpoints`:
group routes by their first literal segment, scan only the matching bucket and
the bucket of routes whose first segment is a capture. Roughly forty lines, no
public API change, and the sort survives inside each bucket.

## Why this shape

Measuring first is what the rest of that page does, and an index added without a
number is complexity bought on a hunch. The alternative — index now, on the
reasoning that O(N) is obviously worse — would land untested code on the hottest
path in the library to fix something that may be 200ns at realistic sizes. Not
recommended.

A first-segment bucket rather than a full trie because it is the smallest thing
that turns the common shape (`/orders/...`, `/users/...`, `/health`) from a scan
of everything into a scan of one family, and because a trie has to answer what a
capture at the first segment means, which a bucket sidesteps by scanning both.

## Stack

- [ ] **`spec-0011-routing-at-scale`** — the decoy parameter on the Pekko and http4k harnesses; the new section in `docs/what-it-costs.md`.
      Done when: the page reports per-request cost at 1, 50 and 200 endpoints for both, and says plainly whether the curve is flat.
- [ ] **`spec-0011-ktor-benchmark`** — a Ktor harness beside the other two, same endpoint, same decoys.
      Done when: all three backends appear in the table and Ktor's curve is measured rather than asserted from its router.
- [ ] **`spec-0011-segment-index`** — *conditional.* A first-literal-segment bucket in `orderedEndpoints`, Pekko and http4k.
      Done when: the 200-endpoint number is within noise of the 1-endpoint one, and `AllBackendsTest`, `ConcatenatedRoutesTest` and `Http4kInterpreterTest` are unchanged and green. Delete this entry if the first one shows a flat curve.

## Acceptance

```bash
./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 Scaling"
./gradlew build
```

## Open questions

1. What counts as a decoy? Recommend endpoints of the same segment count with
   different literals, which is the realistic worst case — a scan that fails
   late rather than on the first segment.
2. Is 200 the right ceiling? Recommend yes: past that a service is usually
   several services, and the curve's shape is visible by then.
3. Should the decoys be bound to handlers, or is a description enough?
   Recommend bound — `toRoute` and `toHttpHandler` take an `Api`, and an
   unbound endpoint is refused.
4. Does the JMH `@Param` sweep push the run past the six minutes the page
   quotes? Recommend a separate benchmark class so `./gradlew :benchmarks:jmh`
   stays what it is and the sweep is asked for by name.
5. Fix the stale `RoutingTest` reference in `Route.pelican`'s KDoc here, or
   leave it for 0010? Recommend here — it is one line and this is the spec
   reading that KDoc.
