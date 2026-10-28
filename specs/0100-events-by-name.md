# 0100 — Events by name, kept alive by the deployment

## Problem

Two things about `sse<T>(...)` in 1.2.0 are fixed where they should not be. First, `eventName` is one string for the
whole endpoint, so a stream of several kinds of event writes them all under one name, or none, and carries the kind
inside `data`. A page then uses `onmessage` and switches on a field, and the 3.2 document describes one shape where
the wire carries several. Doorlist's live sheet is that stream: `LiveEventDto.kind` is one of booking, hold, block or
`resync`, every other field nullable to fit them all. Second, `keepAlive` is part of the endpoint's declaration,
though the document never shows it and the right value is the proxy's idle timeout, a deployment fact. Doorlist
builds its stream endpoints from functions taking the heartbeat (`staffLiveEndpoint(heartbeat)`) so a test need not
wait 20 s (dependencies.md item 21).

What 1.2.0 already has, and this spec leaves alone: `id`, `retry`, `Last-Event-ID` and `lastEventId()`, the keep-alive
comment on all three backends, the producer stopped when the client goes, and the 3.2 event schema.

## Not doing

- **Event names on NDJSON or a JSON array.** SSE is the only format with a name field.
- **A name per value computed by a function.** The names must be known when the endpoint is built, or the document
  cannot list them.
- **Fan-out, replay storage or Postgres `LISTEN`.** The service's, and Surefold's.

## Shape

```kotlin
sealed interface LiveEvent
data class BookingChanged(val id: String, val version: Int) : LiveEvent
data class Resync(val reason: String) : LiveEvent

sse<LiveEvent>(events = eventNames { name<BookingChanged>("booking"); name<Resync>("resync") }, id = { ... })

api(routes, codecs) { streams { keepAlive = config.heartbeat } }    // every SSE endpoint in this Api
```

- **`eventNames { }`** maps each subtype of a sealed `T` to a name. When the endpoint is built, every permitted
  subclass (`Class.getPermittedSubclasses`, Java 21) must be named, once, or it is refused naming the one left out.
  `eventName = "x"` and `events` together are refused. Each frame's `event:` is its value's name; `data` is the
  value encoded as `T`, discriminator and all, so a client reading every frame with `T`'s codec still can.
- **The document, 3.2:** the event schema becomes a `oneOf`, one branch per name, each with `event` as a `const`
  and `data`'s `contentSchema` the subtype. Under 3.1, unchanged: the payload schema of `T`.
- **The generated client** decodes `data` as `T` whatever the name, so its signature does not change.
- **`streams { keepAlive }`** on the `Api` overrides every SSE endpoint's own; an endpoint's `keepAlive` remains the
  default where the `Api` sets none, so nothing that compiles today changes behaviour.

## Why this shape

Names belong to the type, so a sealed hierarchy is where they are written, once; an endpoint that sends one kind
keeps `eventName`. Option A, an explicit `eventNames { }` map, works with any codec and with names that differ from
the class or the discriminator. Option B derives names from the codec's discriminator (`@SerialName`, `@JsonTypeName`),
which is less to write but makes the wire depend on which codec is configured. Recommended: A. For keep-alive, the
`Api` is where a deployment's numbers already live (body limits, timeouts), and a test builds a second `Api`, not a
second endpoint.

## Stack

- [ ] **`spec-0100-keep-alive`**: `streams { keepAlive }` on the `Api`, read by Pekko, Jetty and Gannet.
      Done when: the parity suite sees the `Api`'s interval on an endpoint declared with another, and the endpoint's
      own where the `Api` sets none.
- [ ] **`spec-0100-names`**: `eventNames { }`, the build-time refusals, the frame's `event:` per value.
      Done when: `AllBackendsTest` pins the bytes of a two-kind stream on every backend, and an unnamed subtype fails
      the build naming it.
- [ ] **`spec-0100-document`**: the 3.2 `oneOf`, the generated client and the importer on it.
      Done when: a golden pins it, `OpenApiSpecQualityTest` reads it, and the round trip compiles.
- [ ] **`spec-0100-docs`**: reference ("An event stream a caller can pick up again"), cookbook, changelog.

## Acceptance

```bash
./gradlew build   # AllBackendsTest, goldens, OpenApiSpecQualityTest, ImportedOrdersTest
```

## Open questions

1. **Is the names entry wanted?** Doorlist's own record says one name per endpoint is "the SSE model" and asks only
   for the keep-alive. Recommended: build the keep-alive first and the names after, since a named event is how
   `EventSource` listeners are meant to be used and the 3.2 document can finally say what each one carries.
2. **Which wins, `Api` or endpoint?** Recommended: the `Api`, as above; the endpoint's value is a default, and is not
   in the document.
3. **Should `data` be the subtype's encoding, without the discriminator?** Recommended: no; encoding as `T` keeps one
   decoder per stream and a client that ignores names working.
4. **`keepAlive = null` on the `Api`:** off everywhere, or no override? Recommended: no override; `Duration.INFINITE`
   turns it off.
