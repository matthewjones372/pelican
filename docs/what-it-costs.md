# What it costs

Linked from the [README](../README.md); kept here because a benchmark is
detail, and the front page only has to say that it was measured.

A description has to be interpreted, and that is not free. What it costs is
measured rather than argued about:
[`OverheadBenchmark`](../example/src/test/kotlin/example/OverheadBenchmark.kt)
serves one endpoint twice — once described with Pelican and interpreted onto
http4k, once written directly against http4k's own routing — and compares them.
Both decode a path parameter and an optional query parameter, and both encode
the same object with the same Jackson mapper, so what is left in the difference
is the interpreter.

| against | per request |
|---|---|
| http4k written the ordinary way | **0-35ns, 112 bytes** |
| http4k with the response hand-tuned | ~150ns, ~400 bytes |
| Pekko, idiomatic (`PathMatchers` + `parameterOptional`) | 95ns and 1.4KB *faster* |
| Pekko, `PathMatchers` with the query read off the request | level on time, ~570 bytes lighter |
| Pekko, one directive and both read off the request | ~140ns, ~950 bytes |

Two baselines each, because one would flatter. An http4k `Response` is immutable, so
the idiomatic `Response(status).header(...).body(...)` copies it at each step —
three responses and two header lists where one of each will do. Pelican builds
it in one construction, which is worth about 300 bytes a request and is most of
why the first row is what it is. The second row is the honest comparison
against someone who has done the same thing by hand.

Pelican beating an idiomatic Pekko route is not a claim that the library is
faster than the server it runs on — it is a claim about `parameterOptional`,
which costs about 100ns and 900 bytes a request. Take that one directive out
and a hand-written route draws level; write the whole thing as a single
`extractRequest` that reads the path and query itself — which is what the
interpreter does — and the hand-written version is ~140ns ahead, where you
would expect it to be. Three Pekko rows rather than one because the first
number on its own would leave the wrong impression.
[`PekkoOverheadBenchmark`](../example/src/test/kotlin/example/PekkoOverheadBenchmark.kt)
seals every route with `Route.function(system)`, so none of them pays for a
socket.

Reading a body is the other half of the story, and the one where Pekko's
streams actually turn: `toStrict` materialises. A POST with a JSON body costs
~11µs on this backend whoever writes the route — two orders of magnitude more
than the routing above — and the interpreter is about a microsecond *under* a
hand-written `extractStrictEntity`, because it asks Pekko for the cheaper of
two reads when the request declared its length. See
[`ChunkedBodyLimitTest`](../pelican-pekko/src/test/kotlin/io/github/matthewjones372/pelican/pekko/ChunkedBodyLimitTest.kt)
for why the other read still exists.

For scale: a loopback socket round trip is tens of microseconds and a database
call is milliseconds, so on a realistic endpoint this is a fraction of a
percent. It does not grow with the endpoint either — the cost is the
per-request bag of decoded values, the `Params` around it and the response, and
none of those scale with how many inputs are declared or how big the payload
is. An endpoint that decodes nothing pays much the same as one that decodes
two, which on the smaller absolute numbers of an empty endpoint reads as a
larger ratio.

```bash
./gradlew :example:test -Dbenchmark=true --tests "*OverheadBenchmark*"   # both
```

It is not JMH: one JVM, no forks, no blackholes. Treat the ratio as sound and
the absolute numbers as indicative. It runs only when asked for, and turns the
coverage agent off for itself — measuring through instrumentation reports the
agent rather than the library, which cost an afternoon to notice.
