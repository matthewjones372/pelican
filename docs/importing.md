# Importing an OpenAPI document

The reverse of generating a document: an OpenAPI document someone else wrote,
read into Pelican endpoint descriptions. Use it to call another team's API
through a [generated client](generated-client.md), or to build a service
against a spec agreed before the code.

## Configuring the plugin

The import runs through the Gradle plugin (setup in the
[README](../README.md#the-gradle-plugin)) against a classpath that holds
`pelican-import`. `pelican-import` is build tooling, so the example keeps it
off the application's classpath with a configuration of its own:

```kotlin
// build.gradle.kts
plugins { id("io.github.matthewjones372.pelican") version "1.1.0" }

val pelicanImport = configurations.register("pelicanImport")

dependencies {
    implementation("io.github.matthewjones372:pelican-core:1.1.0")    // what the generated file compiles against
    pelicanImport("io.github.matthewjones372:pelican-import:1.1.0")   // what the task runs
}

pelican {
    endpoints {
        create("orders") {
            document.set(layout.projectDirectory.file("orders.yaml"))
            packageName.set("com.example.orders")
            classpath.setFrom(pelicanImport)
            handlers.set("pekko")                 // optional: a stub per operation
        }
    }
}
```

### Every setting on an `endpoints` entry

| Property | Default | What it does |
|---|---|---|
| `document` | none, required | The OpenAPI document to read, JSON or YAML. |
| `packageName` | none, required | The package the generated files declare, and the directories they are written into. |
| `exclude(...)` | none | `operationId`s to leave out. See [What is refused](#what-is-refused-and-why). |
| `discriminator(schema, property)` | none | States which property tells a `oneOf`'s branches apart where the document did not. |
| `allowRemote(...)` | none | Origins a `$ref` may be fetched from. See [References to another host](#references-to-another-host). |
| `lockfile` | `<document name>.refs.lock` beside the document | Where fetched URLs and their hashes are recorded. |
| `handlers` | unset, no stubs | `pekko` or `jetty`: also write a handler stub file for that backend. |
| `codec` | `jackson` | `jackson` or `kotlinx`: which library the payload types are annotated for. |
| `outputDir` | `build/generated/pelican/<name>` | The source root written into. |
| `classpath` | `main`'s runtime classpath | What the import runs against; `pelican-import` has to be on it. |

`handlers` and `codec` are matched case-insensitively, and an unknown value
fails naming the accepted ones.

### Tasks

Named from the entry with its first letter capitalised; `orders` gives:

- `generateOrdersEndpoints` writes the descriptions. Inside `build/` the output
  directory is emptied first and tracked as the task's output. Anywhere else it
  is a source root you commit, and the task always runs.
- `updateOrdersEndpointsLock` records remote references. It is the only task
  that fetches without checking against the lockfile, nothing depends on it,
  and you run it by hand. `--accept-changes` records a new hash for a URL that
  is already in the lockfile.

The plugin does not add the output to a source set. With the default
`outputDir`, add it to the one that compiles it and make that compile depend on
the task, as `example/build.gradle.kts` does:

```kotlin
sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/pelican/orders"))
tasks.named("compileKotlin") { dependsOn("generateOrdersEndpoints") }
```

## What is generated

`OrdersEndpoints.kt` (the entry name, capitalised, plus `Endpoints.kt`) holds
the inputs as values, the payload types as data classes, one `endpoint(...)`
per operation, the document's own schemas, and two spec functions. From the
import test's bookmarks document:

```kotlin
val bookmarkId = pathParam<Long>("bookmarkId")
val limit = queryParam("limit", IntCodec.atLeast(1).atMost(100), "How many to return").default(20)

val problemNotFound = errorJson<Problem>(404, "No bookmark with that id")

/** Fetch one bookmark */
val getBookmark = endpoint(bookmarkId) {
    get("bookmarks" / bookmarkId)
    summary = "Fetch one bookmark"
    operationId = "getBookmark"
    json<Bookmark>() orFail problemNotFound
}
```

- `ordersSpec()` describes the payloads with the schemas copied from the
  document, so it needs no JSON library. Reading a document, describing it and
  generating a client from it involves no codec.
- `ordersSpec(schemas: SchemaSource)` describes them from the generated Kotlin
  classes instead, for example `ordersSpec(JacksonCodecs)`. Use it once you
  have edited those classes.

Point a `clients` entry's `specFunction` at `ordersSpec` for a typed client of
the other API, or bind the endpoints to handlers and serve them.

`handlers.set("pekko")` also writes `OrdersHandlers.kt`: one `TODO()` per
operation, in the binder for each output kind. It is written once and never
overwritten, because after the first run it is your service. Keep it in a
committed source root: in an `outputDir` inside `build/`, the directory is
emptied each run and the stub is regenerated. `pekko` stubs compile against
`pelican-pekko`; `jetty` stubs compile against `pelican-jetty`.

`codec` matters for a `oneOf`. With `jackson`, the sealed interface carries
`@JsonTypeInfo` and `@JsonSubTypes` and nothing else is annotated. With
`kotlinx`, every generated class is `@Serializable` (kotlinx.serialization
has no reflective fallback), so the build needs the kotlinx serialization
compiler plugin.

## What is read

- OpenAPI 3.2, 3.1 and 3.0, and Swagger 2.0, as JSON or YAML. A 2.0 document
  and its 3.0 equivalent generate the same descriptions (`VersionsTest`).
- `$ref`s to other files are followed, and the schemas they name keep those
  names. `$ref`s to another host are refused unless the host is allowed; see
  below.
- A `oneOf` with a `discriminator` becomes a sealed interface and one data
  class per branch. Branches are named from the `discriminator.mapping` key,
  then the referenced component, then `<Parent>Variant<n>`, all read from the
  document, so the same document always generates the same names.
- An `allOf` of several schemas is flattened into one class. Where two of them
  disagree about a property it is refused rather than resolved.
- A request body offered under several media types is read when every entry
  carries the same schema and each is JSON or a form.

## What is refused, and why

The import is strict. An operation using something Pelican cannot describe
fails the build rather than generating an endpoint whose type says less than
the document does. Examples:

- a request body offered under several media types with a different schema
  under each (a handler is given one value of one type);
- a `oneOf` with nothing saying which branch a payload is;
- a streamed response beside another 2xx response;
- an operation documenting no 2xx response.

Every such operation is reported in one failure, each with its place in the
document and the way out, and the message ends with an `exclude(...)` line
listing them. For a document you own, change the document. For one you do
not, list the operations you have decided to live without:

```kotlin
create("orders") {
    document.set(file("orders.yaml"))
    packageName.set("com.example.orders")
    exclude("uploadReceipt", "searchAnything")
}
```

The list is reviewed in the build file, and a new undescribable operation
appearing in the document still fails. Excluding an operation also drops the
schemas only it reached.

### Stating a discriminator

A `oneOf` with no `discriminator` is refused because a decoder that tries each
branch and keeps the first that parses is wrong, silently, on the first
payload two branches both accept. Where you know which property selects the
branch, say so:

```kotlin
create("orders") {
    document.set(file("orders.yaml"))
    packageName.set("com.example.orders")
    discriminator("Payment", property = "kind")
    discriminator("Order/properties/payment", property = "kind")
}
```

The first argument is a component name, or a JSON pointer for a union the
document wrote inline. The `discriminator` is written into the document before
anything reads it, so branch names, codec annotations and the republished
`mapping` are what a document that stated it would have produced. Each
branch's wire value is read from the document: a `const` the branch declares
for the property, or the name of the schema it references. An inline branch
with neither is refused.

A hint fails the build when it names a property no branch declares, addresses
something that is not a union or already has a `discriminator`, gives two
branches one value, or is no longer needed by anything generated (because the
operations using it were excluded or the document stopped referencing it).
All wrong hints are reported together. An `anyOf` of several branches stays
refused, with or without a hint.

## References to another host

By default a `$ref` to another host fails the import: a build that fetches a
URL to know what to generate cannot be reproduced. Bundle the document, or
vendor the file it needs beside it. Where neither is possible (the document is
published in pieces by someone else, and rewriting their `$ref`s would fork
it), allow the host:

```kotlin
create("orders") {
    document.set(file("orders.yaml"))
    packageName.set("com.example.orders")
    allowRemote("https://schemas.example.com")
}
```

Then run `./gradlew updateOrdersEndpointsLock`. It writes:

- `orders.refs.lock`: every URL reached, transitively, with the SHA-256 of
  what came back, one line per URL sorted by URL;
- `orders.refs.lock.d/`: the fetched documents, named by hash.

It prints one line per URL added (`+`), changed (`~`) or dropped (`-`). Commit
both. `generateOrdersEndpoints` then reads only what the lockfile records,
from the cache directory, and makes no request; with the cache missing it
fetches and checks the hash.

The rules:

- https only. Plain `http://` is fetched only for an origin written with
  `http://` in `allowRemote`.
- Redirects are refused, even to an allowed host.
- A `$ref` to a URL not in the lockfile fails, naming the update task.
- A fetched document whose hash differs from the lockfile fails, showing both
  hashes. So does a cached copy that has been edited.
- `updateOrdersEndpointsLock` refuses to record a changed hash unless run with
  `--accept-changes`. Read what changed upstream first.

The details are under [References](reference.md#references).

## What you maintain

- **The document.** When it changes, rerun `generate<Name>Endpoints`; the
  endpoints file is rewritten every time, so never edit it.
- **The handler stubs.** After the first run they are yours. A new operation in
  the document does not add a stub to an existing handlers file; write its
  handler yourself (or delete the file to have it regenerated).
- **`exclude` and `discriminator` entries.** A hint that is no longer needed
  fails the build, so remove it. An `exclude` for an `operationId` that has
  left the document is not reported.
- **The lockfile and its cache**, when `allowRemote` is set. Rerun the update
  task when the document's references change, and review its output before
  committing.

This repository imports its own document on every build: `:example` publishes
`openapi.json` from its endpoint values, generates descriptions back from it
(and from its 3.2 rendering), compiles them, and `ImportedOrdersTest` compares
what they publish with the original.
