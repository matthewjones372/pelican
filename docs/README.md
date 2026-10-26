# Documentation

The guides here are grouped by what you are trying to do: decide whether
Pelican fits, use it, understand it in depth, or work with one specialised
feature.

**What is released.** The current release is 1.1.0, with Pekko HTTP and
Jetty 12 as backends and Jackson and kotlinx.serialization as codecs. The
[cookbook](cookbook.md) and several other guides are written against Jetty, so
their dependency blocks use `pelican-bom` and the Jetty modules. On Pekko, use
the Pekko equivalents (`pelican-pekko`, `pelican-pekko-docs`,
`pelican-test-pekko`) and the Pekko dependencies your service names itself, as
the [README's Getting started](../README.md#getting-started) and the cookbook's
[On Pekko](cookbook.md#on-pekko) show. Every binder has the same name in
`io.github.matthewjones372.pelican.pekko`; only a streaming handler changes, to
a Pekko `Source` where Jetty takes a `Sequence`. The Gannet backend is on `main`
and in no release; see [Modules](modules.md#gannet-backend).

## Evaluating it

1. [Choosing between Pelican and the alternatives](choosing.md): when it fits,
   when it is unnecessary, how it compares with http4k, Ktor, Spring,
   Micronaut, Quarkus, tapir and writing the document first, and when it is the
   wrong choice.
2. [What it costs](what-it-costs.md): the interpreter measured against
   hand-written routes on each backend, with error bars and limits.
3. [The same endpoints, by hand](by-hand.md): two endpoints written both ways,
   so the difference can be read rather than taken on trust.

## Using it

1. [Getting started](../README.md#getting-started) in the README: installing
   1.1.0 and a first endpoint.
2. [Cookbook](cookbook.md): complete recipes, one problem each, roughly in the
   order you need them.
3. [Modules](modules.md): every module, what it brings with it, and the
   minimum set for each setup.
4. [A whole service, in one file](a-whole-service.md): models, inputs,
   endpoints, handlers, server and docs together.

## In depth

- [Reference manual](reference.md): every feature, the reasoning behind it,
  its limitations, the stability promise and the supported versions.

## Specialised topics

- [The generated client](generated-client.md): a Kotlin client for callers who
  cannot depend on your descriptions.
- [Golden testing](golden-testing.md): failing the build when a change to the
  published document would break existing callers.
- [MCP tools](mcp.md): the same descriptions served as tools a model can call.
- [Importing an OpenAPI document](importing.md): descriptions generated from a
  document somebody else wrote, to serve, call or stub.
- [Schemas](schemas.md): a standalone JSON Schema for one type.
- [Roadmap](roadmap.md): what is not built yet.
