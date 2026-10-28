# Roadmap

What is planned, in progress or blocked, and roughly in what order. Things
Pelican deliberately does not do are a different list:
[What isn't here](reference.md#what-isnt-here) in the reference manual.

No dates and no version numbers. The order is an argument about value, written
down so it can be disagreed with.

## 1. One request pipeline, in core

Routing and the decoding of path, query, header and cookie inputs are core's,
through `spi.EndpointPlan`. The rest of the steps between a matched route and a
handler (resolving an endpoint's codecs, refusing an oversize body, folding the
CORS policy) are still private to each interpreter, so each backend carries a
copy and a fix lands once per backend. Hoisting them into core is
core-side only and leaves each backend with the parts that are genuinely its
own: route registration, reading the body, writing the response, streaming and
the async model.

This comes before any further backend, because every backend added before it
is one more copy to keep in step.

## 2. Error messages a retry loop can converge on

A typed DSL suits coding agents because they compile, read the error and try
again. That only works if the error says what went wrong. The work is an audit
of what common mistakes actually print, starting with inference failures that
name neither the endpoint nor the declared type, and a fix for each one that
does not point at its cause.

## 3. OpenAPI 3.2.0 as the default

`pelican-openapi` writes 3.1.0 unless asked for 3.2.0. The default stays there
because swagger-parser, which `openapi-generator` and most of the JVM ecosystem
read documents with, returns `null` for a 3.2.0 document with no error.
`OpenApiSpecQualityTest` asserts that it still does. When that assertion starts
failing, the default moves to 3.2.0.

Blocked on swagger-parser. See
[Which version the document says](reference.md#which-version-the-document-says-and-how-to-choose).

## 4. The cover check, at compile time

`api { covers = ... }` fails the `Api` constructor when a described endpoint is
never bound. That is a runtime check, one test away from useless. A KSP
processor could make it a compile error. Worth doing after the items above; not
started.

## Under consideration

- **More than six typed inputs.** `endpoint(a..f)` is the largest overload, and
  past it a handler reads the whole `Params` through `endpoint(lensInputs)`.
  More overloads or a builder would both work. Not urgent: six covers nearly
  every endpoint.
- **Further transports, servers and codecs.** Specs 0073, 0075 and 0076 name
  OkHttp and Ktor client transports, http4k and Ktor servers, and a jsoniter
  codec as later work, under "Not doing". No spec is open for any of them and no
  order is set. Of the clients, OkHttp reaches the most new callers, since
  `java.net.http` does not exist on Android.

## Not on this list

Things that look like gaps and are not, with the reasoning in
[What isn't here](reference.md#what-isnt-here): writing OpenAPI 3.0, `callbacks`,
`anyOf` and `not`, a lenient importer, nested objects in form bodies, more than
one streamed file part. The MCP features an endpoint description cannot supply
(resources, prompts, sampling, a server-initiated event stream) are covered
under [No SDK, and what is not served](mcp.md#no-sdk-and-what-is-not-served).

A new server backend written to prove the interpreter seam is also not planned:
two backends share every binder name today, and another would not strengthen
that claim. A backend wanted for its audience (Spring Boot, for example) would
start as a new spec.
