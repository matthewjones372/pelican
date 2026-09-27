# 0058 — A handler that blocks

## Problem

The synchronous binders (`handledNow`, `handledOrFail`, `handledWith`,
`handledOneOf`) call the handler on the thread that matched the route, which
on Pekko is a thread of the default dispatcher, and wrap the answer in
`completedFuture`:

```kotlin
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledOrFail(
    f: Params.(I) -> Outcome<E, T>,
): ServerEndpoint = ServerEndpoint(this) { p ->
    CompletableFuture.completedFuture(p.f(inputs.extract(p)) as Any?)
}
```

Almost every handler written against these blocks: a JDBC call, an actor ask,
a generated client joined. Petshop's handlers block for up to three seconds on
an ask. The dispatcher has as many threads as cores, so a few slow handlers
stall every request on the server, and the parsing, routing and response
writing that Pekko runs on those same threads stall with them. It holds at
petshop's 200 requests a second. At lark-bank's thousands of requests a second,
each blocking on an entity ask to another node, it does not hold.

Today a service avoids this by switching to `handledByOrFail` and writing
`CompletableFuture.supplyAsync({ … }, virtualThreads)` in every handler. That is
the same line in every handler, and the binder's name no longer says what the
handler does.

## Not doing

- **No change to `handledBy*`.** An asynchronous handler already chose where it
  runs.
- **No coroutine or `suspend` binder.** Pelican core has no coroutines.
- **No per-endpoint executor.** One per server; see Open questions.
- **Nothing for streaming responses.** A `Source` handler returns at once.

## Shape

The synchronous binders run the handler on a virtual thread of its own. Nothing
changes at the call site:

```kotlin
adoptPet handledOrFail { id -> shop.adopt(PetId(id)) }   // may block; the dispatcher never waits on it
```

The executor is a start setting, with a virtual thread per request as the
default:

```kotlin
api.start(system, port = 8080, handlers = Executors.newVirtualThreadPerTaskExecutor())
api.start(system, port = 8080, handlers = Handlers.onDispatcher)   // today's behaviour, for handlers that never block
```

A handler that throws still fails the request as it does today, and the
request's `Params` stays readable from the handler thread.

## Why this shape

Moving the default fixes every existing service, petshop included, without a
code change, at the cost of one thread hop per request, which a virtual thread
makes a few microseconds. The alternative is a new family of binders
(`handledBlockingOrFail`, and so on) and leaving the default alone. That keeps
today's cost model, but it doubles the binder surface and keeps the wrong
default for the common case. The recommendation is to move the default.

## Stack

- [ ] **`spec-0058-dispatch`** — the synchronous binders produce a
      `ServerEndpoint` marked synchronous, and the Pekko interpreter runs it on
      the start's `handlers` executor, a virtual thread per task by default.
      Done when: a test with 64 handlers each sleeping 1 s answers all 64 in
      under 2 s on a 2-core dispatcher, and a test reads
      `Thread.currentThread().isVirtual` inside a handler as true.
- [ ] **`spec-0058-opt-out`** — `Handlers.onDispatcher` plus the `handlers`
      parameter on `start` and `startWithDocs`, and a README section on
      choosing.
      Done when: a test on `onDispatcher` sees the handler on a dispatcher
      thread, and the README example compiles in `example/`.

## Acceptance

```bash
./gradlew build
./gradlew :pelican-pekko:test --tests '*BlockingHandlerTest*'
```

## Open questions

Answered 2026-09-27, taking each recommendation:

1. **The default, or opt-in?** The default. `Handlers.onDispatcher` is the
   opt-out.
2. **Should the executor be per endpoint?** No. One per route.
3. **Should a `LarkLocal`/OTel context cross the hop?** Not by capturing it.
   The hop is taken before the filter chain, so filters run on the handler's
   thread too and whatever context a filter opens is the handler's. The
   default executor is the JDK's thread-per-task executor, which the OTel
   agent already propagates across, so `pelican-pekko` gains no OTel
   dependency.

Settled while building: `toRoute(system, handlers)` takes the executor too,
since a route mounted by hand never passes through `start`; and the
signatures `start` and `toRoute` had before stay in the bytecode, hidden, so a
caller compiled against RC2 still links.
