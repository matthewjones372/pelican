# 0087 — A runtime on io_uring

> **Superseded.** The runtime this spec built now lives outside Pelican, as
> [Gannet](https://github.com/matthewjones372/gannet)
> (`io.github.matthewjones372:gannet`). `pelican-gannet` depends on it and
> carries no copy; see **Closing**.

## Problem

Pelican serves on Pekko or Jetty, so it is bounded by them. On a 4-core VM with two
server cores, serving HttpArena's `baseline`, a virtual-thread-per-connection server
reached about 1.9× a hand-written Jetty handler. The `bvr.arena` prototype in
`blocking-vs-reactive` (an io_uring per core, each connection a JDK `Continuation` with
blocking-looking `read`/`write`) reached roughly 3× it, measured in separate runs: a
blocking-style handler model at io_uring speed. Raw-server numbers; Pelican's cost on
top is unmeasured.

This spec ports that runtime and writes the HTTP/1.1 Jetty gives for free; spec 0088
puts the interpreter on it. Split because together they run past two pages.

## Not doing

- **TLS, HTTP/2, h2c, compression.** Neither backend does the last; a proxy does TLS.
- **Non-Linux, or Linux before 6.1.** `start` refuses with a message naming the
  kernel it found and the one it needs, and the JVM flags if they are missing.
- **The interpreter, `Handlers`, `-docs`.** Spec 0088.
- **`-mcp`, `-test-uring`, `-streams-uring`, the importer's `Backend`.** A later spec.
- **IPv6 and Unix sockets.** The prototype binds IPv4 only.

## Shape

`backends/uring/pelican-uring`, package `io.github.matthewjones372.pelican.uring`,
JDK floor 25, internal apart from what 0088 exposes.

- One carrier platform thread per core, each owning a ring (`SINGLE_ISSUER`,
  `DEFER_TASKRUN`, registered fd), a `SO_REUSEPORT` listener, and a multishot recv
  into a provided buffer ring.
- Each connection is a fiber over `jdk.internal.vm.Continuation`. Reads park the
  fiber; writes queue and never park, so parks fall only between drains.
- Port 0: the first carrier binds, reads its port with `getsockname`, and the rest
  bind to that, so `PelicanServer.port` is one number.
- Other threads reach a carrier through an MPSC queue plus an `eventfd` whose read is
  kept armed on the ring; 0088's virtual-thread handlers hand results back this way.
  A thread writing a stream gets a completion per write, which it awaits before the
  next: Jetty's backpressure, without the fiber ever parking on a write.
- HTTP/1.1: request line and headers with size limits (400, 414, 431), `Host`
  required, `Content-Length` and chunked request bodies, `Expect: 100-continue`,
  keep-alive and pipelining, `Connection: close`, idle timeout, strict replies with
  `Content-Length`, chunked replies left unterminated on failure, HEAD bodies
  suppressed, and a refused body drained (64 KiB, as Jetty) so the connection lives.

Consumers run with `--enable-native-access=ALL-UNNAMED
--add-exports java.base/jdk.internal.vm=ALL-UNNAMED`; the module's test tasks set them.

## Why this shape

**Language.** Option A: all Kotlin, the continuation reached through `MethodHandles`
looked up once. Kotlin can call `invokeExact` and VarHandles, but a cast that is not
exact boxes and fails only at run time. Option B: a small Java source set (`Sys`,
`Ring`, `BufferRing`, `Fiber`, about 400 lines), Kotlin for the rest. Recommended: B,
with spotless's Java formatter, and a `FunctionalStyleTest` entry per Kotlin file
that holds connection state.

**Coverage.** Tests skip off Linux, so the 90% aggregate drops for whoever builds on
a Mac. Option A: leave `pelican-uring*` out of the aggregate unless the build host
is Linux. Option B: out of the aggregate always, with its own 90% floor checked on
Linux. Option C: count it everywhere and accept a red `check` off Linux. Recommended:
A, plus `PELICAN_REQUIRE_URING=1` in CI so a CI host without io_uring fails rather
than skips and passes on fewer lines.

## Stack

- [ ] **`spec-0087-module`**: module, floor 25, JVM flags, platform probe, coverage rule.
      Done when: the probe refuses a non-Linux or pre-6.1 host with its message.
- [ ] **`spec-0087-ring`**: `Sys` and `Ring`: setup, SQ/CQ, registered fd.
      Done when: a NOP round trip completes on a ring.
- [ ] **`spec-0087-listen`**: buffer ring, multishot accept and recv, shared port 0.
      Done when: four carriers accept on one port chosen by the kernel.
- [ ] **`spec-0087-fibers`**: `Fiber`, `Carrier`, `Io`: parking reads, queued writes.
      Done when: an echo test passes on every carrier.
- [ ] **`spec-0087-wake`**: MPSC queue, `eventfd`, write completions; `stop` joins.
      Done when: a slow reader holds a virtual-thread writer, by a gate; `stop` leaves no thread.
- [ ] **`spec-0087-request`**: request parsing, limits, keep-alive, pipelining.
      Done when: 200 generated bad request lines are each a 4xx.
- [ ] **`spec-0087-bodies`**: `Content-Length`, chunked in, `100-continue`, the drain.
      Done when: a refused body leaves the connection answering the next request.
- [ ] **`spec-0087-replies`**: strict and chunked replies, HEAD, idle timeout.
      Done when: JDK `HttpClient` and Jetty `HttpClient` both read every reply shape.

## Acceptance

On Linux 6.1+ with JDK 25:

```bash
PELICAN_REQUIRE_URING=1 ./gradlew :pelican-uring:check
./gradlew build
```

## Open questions

1. **Flags at run time.** `--add-exports` falls on every consumer. Recommended: `start`
   fails with the exact flags to add; no reflection workaround.
2. **aarch64.** The prototype says x86-64 only, though io_uring's syscall numbers are
   the same on arm64. Recommended: test both in CI; claim only what CI runs.
3. **Containers.** Docker's default seccomp profile blocks io_uring. Recommended: the
   refusal message says so; no fallback to another backend.
4. **Unbounded fiber writes.** A strict reply queues whole, however large. Recommended:
   accept it, since a strict body is already in memory; only streams need the ack.

## Closing

Built as described, in `pelican-uring`, then extracted into Gannet, a library of
its own, which has since added TLS (`GannetConfig.tls`). The Pelican module is now
`backends/gannet/pelican-gannet`, package `io.github.matthewjones372.pelican.gannet`,
and holds only spec 0088's interpreter. The runtime's tests, and its design from
here on, are Gannet's. `PELICAN_REQUIRE_URING=1` still decides whether the Gannet
modules' tests may skip and whether Kover counts them.
