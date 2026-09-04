# 0041 — What the description costs a frame

## Problem

`PekkoOverheadBenchmark` says what interpreting a description costs for one
value. Nothing said what it costs for a streamed one, and that is the case
where an interpreter could plausibly charge per *element* rather than per
request — a per-frame codec lookup, a wrapper stage, a copy.

The question was asked directly (chat, 2026-09-04): is Pelican worse than raw
Pekko at unbounded streams, better, or the same? Spec 0038 answers the
behavioural half — demand reaches the source, nothing collects it. The cost
half had only an argument from the code path.

## Not doing

- **No socket.** Sealed with `Route.function(system)`, as every benchmark here
  is: a bound port measures the loopback.
- **No SSE row.** The framing differs, the interpreter does not, and
  `FramingBenchmark` already has the per-frame numbers for both.
- **No third hand-written variant.** `PekkoOverheadBenchmark` needs three
  because most of what an idiomatic route costs is its directives; a streamed
  response has one directive and ten thousand frames.

## Shape

Two rows, one request each, every frame drained so the source runs to
completion:

```kotlin
@Benchmark fun describedStream(): Done = drain(described)
@Benchmark fun handWrittenStream(): Done = drain(handWritten)
```

The control maps the same `Source` to `ByteString` and hands it to
`createChunked`, which is what a Pekko service writes by hand for NDJSON —
so the difference is the interpreter and the codec lookup, not the framing.

## Why this shape

An end-to-end load test over a socket would answer a different and less useful
question, because the socket dominates and the answer changes with the
machine. Sealing the route puts everything above the wire in the number and
nothing below it.

## Measured

`StreamingOverheadBenchmark`, 2026-09-04:

| elements | Pelican | hand-written | difference |
|---|---|---|---|
| 100 | 35.2 ± 0.3 µs | 35.4 ± 0.8 µs | −0.5% |
| 10,000 | 2372 ± 101 µs | 2312 ± 58 µs | +2.6% |

Allocation is identical: 728 bytes per element on both rows at 10,000, and
78,664 against 78,619 bytes for the whole 100-element response — +0.0%.

The 2.6% is +6 ns per element against a ±10 ns/element confidence interval, so
the two rows are the same within the noise. Interpreting a description costs
nothing measurable per frame, and costs no bytes at all.

## Stack

- [x] **`spec-0041-streaming-overhead`** — `StreamingOverheadBenchmark`, two
      rows, two element counts.
      Done when: `./gradlew :benchmarks:jmh` reports both and the numbers are
      recorded above.

## Acceptance

```bash
./gradlew :benchmarks:jmh -PbenchmarkArgs="StreamingOverheadBenchmark"
./gradlew build
```

## Open questions

None — the spec was written against numbers that already existed, which is the
one case where an empty list is not the agent having guessed.
