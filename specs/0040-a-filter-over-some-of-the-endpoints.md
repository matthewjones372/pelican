# 0040 — A filter over some of the endpoints

## Problem

Filters are registered on the `Api`, so they run for every endpoint in it. A
service with two groups — public endpoints and endpoints behind an
authenticated caller — has three ways to say so today, and none of them is
good.

`onlyWhen` scopes a filter by a predicate over the description, which is right
when the description carries the distinction (`p.endpoint?.security`, as
`SecuredReports` does) and wrong when it does not: the predicate becomes a
membership test against a list held somewhere else, evaluated per request, and
kept in sync by hand.

Two `Api` values whose routes are concatenated splits the thing worth keeping
whole. Documentation, the MCP tool list and the typed test client are all
functions of one `Api`; two of them means two documents to merge.

Writing the check into each handler is the option the filter mechanism exists
to remove.

## Not doing

- **`onlyWhen` stays.** A filter scoped by what the description declares is the
  better answer whenever the description declares it. This spec is for the
  case where grouping is structural.
- **No nesting, no sub-`Api`, no route prefixing.** A group is a list, not a
  scope with settings of its own.
- **Nothing in the document changes.** Filters are not described; security
  requirements are, and they already have a per-endpoint form.
- **No ordering configuration.** One rule, stated below.

## Shape

```kotlin
val open = listOf(health handledNow { "ok" })

val secured = listOf(
    getReport handledNow { id -> Reports.get(id, this[caller]) },
).filteredBy(authenticate, rateLimit)

val api = api(endpoints = open + secured, codecs = JacksonCodecs) {
    filter(requestLog)          // still every endpoint
}
```

`ServerEndpoint` carries `filters`. `filteredBy` returns copies with them
attached, so it is a value transformation over a list and composes with `+`
like any other.

Order is api-wide first, then the group's, then the handler — the same
outermost-first reading `wrap` already gives, so `requestLog` above sees the
401 that `authenticate` throws.

One line implements it, at the seam every caller already goes through:

```kotlin
fun Api.handlerFor(se: ServerEndpoint) = (filters + se.filters).wrap(se.invoke)
```

That covers the Pekko interpreter, the in-memory transport behind the typed
test client, and MCP dispatch, since all three fold their chain there.

## Why this shape

The alternative worth naming is a builder scope — `group(authenticate) { ... }`
inside `api { }` — which reads well and makes the group a construct with a
lifetime, settings and nesting questions. A list with filters attached has
none of those: it can be built in another file, returned from a function taking
the app's dependencies, concatenated, and filtered. Descriptions are values
here, and this keeps bound endpoints values too. Recommended.

## Stack

- [ ] **`spec-0040-endpoint-filters`** — `filters` on `ServerEndpoint`,
      `filteredBy` for one and for a list, the `handlerFor` line, `.api` dumps.
      Done when: a suite proves an api-wide filter runs for both groups, a
      group's filter runs for its own group only, and the order is
      api-wide-then-group; `./gradlew build` green.

## Acceptance

```bash
./gradlew build
```

## Open questions

None — answered by the maintainer in chat, 2026-09-04, and recorded as decided:

- **The two-argument constructor stays primary.** `filteredBy` is the only way
  to attach filters, so nothing compiled against `1.0.0-RC1` breaks.
- **An empty side returns the other list**, so the common case allocates
  nothing at route-build time.
- **The name is `filteredBy`**, beside `handledBy`.
- **A test says the typed client sees group filters**, rather than leaving it
  assumed because `handlerFor` happens to be shared.
