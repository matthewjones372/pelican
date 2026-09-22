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

- [x] **`spec-0011-routing-at-scale`** — the decoy parameter on the Pekko and http4k harnesses; the new section in `docs/what-it-costs.md`.
      Done when: the page reports per-request cost at 1, 50 and 200 endpoints for both, and says plainly whether the curve is flat. Landed in [#63](https://github.com/matthewjones372/pelican/pull/63).
- [ ] ~~**`spec-0011-ktor-benchmark`**~~ — not happening; see Closing. — a Ktor harness beside the other two, same endpoint, same decoys.
      Done when: all three backends appear in the table and Ktor's curve is measured rather than asserted from its router.
- [ ] ~~**`spec-0011-segment-index`**~~ — not happening; see Closing. — *conditional.* A first-literal-segment bucket in `orderedEndpoints`, Pekko and http4k.
      Done when: the 200-endpoint number is within noise of the 1-endpoint one, and `AllBackendsTest`, `ConcatenatedRoutesTest` and `Http4kInterpreterTest` are unchanged and green. Delete this entry if the first one shows a flat curve.

> **The remaining two entries were overtaken.** The measurement showed the
> curve was not flat, so the conditional third entry became
> [spec 0018](0018-pelican-dispatches-for-itself.md), which replaced the ordered
> scan outright rather than indexing it — both backends are now flat and ahead
> of hand-written routing at every size. The Ktor harness is still unwritten:
> Ktor uses its own routing tree and is exempt by construction, so it is the
> least urgent of the three and the claim remains unmeasured.

## Closing

**Both remaining entries were overtaken by 0018, not one.** The note above
records the third; the second went with it and nobody noticed, because the
sentence it rests on stopped being true.

This spec's **Problem** says Ktor is exempt by construction — *"`Route.pelican`
installs one Ktor route per endpoint and lets Ktor's routing tree score them,
and the KDoc says so"*. 0018 changed that. The KDoc on the `multi-backend`
branch now reads:

> Interprets an `Api` as Ktor routes: one route per method the descriptions use,
> each dispatching through the same `RouteIndex` the other two backends walk, so
> a request line means one thing whichever server reads it.
>
> **Ktor's own tree used to do the matching**, from `PathSpec.template`. It
> decoded a segment its own way, applied its own trailing-slash rule and could
> not be asked what the other two would have answered — three routers to keep in
> step rather than one to prove.

So entry two would measure a router that no longer matches anything. Ktor walks
the same `RouteIndex` as Pekko, and *that* curve is the one already reported
above: flat at 1, 50 and 200 endpoints. Running a Ktor sweep would re-measure
the Pekko column with a different server's constant added to every row.

What is genuinely still unmeasured is **Ktor's per-request constant** — its own
overhead around the shared index, the sort of number the `GET /ping` row gives
for http4k. That is a different question from this spec's, which was about the
*shape of the curve*, and it belongs with the Ktor module rather than here.

**And the module is not here.** [Spec 0034](0034-one-backend-to-stand-behind.md)
moved `pelican-ktor` to the `multi-backend` branch by the maintainer's decision
of 2026-08-26; 1.0 ships Pekko and Jackson, and the other backends *"return
after 1.0 as restores"*. A benchmark for a module that is not on main cannot be
built on main, and when the module comes back its restore is the place for it.

`docs/what-it-costs.md` already tells this truth — *"Ktor dispatches through the
same index … and is not measured here either"* — so nothing on a published page
is over-claiming while this closes.

Open question 5 closes with it: the stale `RoutingTest` reference is gone from
`Route.pelican`'s KDoc, rewritten by 0018 along with the routing it described.

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
5. ~~Fix the stale `RoutingTest` reference in `Route.pelican`'s KDoc here, or
   leave it for 0010?~~ **Moot: the reference is gone**, rewritten by 0018 with
   the routing it described. See **Closing**.
