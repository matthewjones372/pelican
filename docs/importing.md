# Importing an OpenAPI document

Linked from the [README](../README.md). The other direction: a document
somebody else wrote, read into endpoint descriptions.

The other direction, for the two cases where the descriptions are not yours to
write first: calling somebody else's API, and building a service against a spec
that was agreed before the code.

```kotlin
pelican {
    endpoints {
        create("orders") {
            document.set(layout.projectDirectory.file("orders.yaml"))
            packageName.set("com.example.orders")
            handlers.set("pekko")            // optional: a stub per operation
        }
    }
}
```

`./gradlew generateOrdersEndpoints` writes `OrdersEndpoints.kt`: the inputs as
values, the payload types as data classes, one `endpoint(...)` per operation,
and an `ordersSpec(schemas)` holding the lot. It is the file you would have
written by hand from that document, and it reads like one:

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

The generated file also carries the document's own schemas, so `ordersSpec()`
takes no arguments and needs no JSON library: reading a document, describing
it, and generating a client for it is a path with no codec on it at all. Point
`generateOrdersClient` at `ordersSpec` for a typed client of somebody else's
API, or bind the endpoints to handlers and serve it. `ordersSpec(JacksonCodecs)`
is the same descriptions with the payload types described from the generated
Kotlin classes instead, which is what a service that has since edited them
wants. `handlers.set("pekko")` writes the second
file — one `TODO()` per operation, in the right binder for each output kind —
and never overwrites it again, because after the first run it is your service
rather than generated code.

JSON or YAML, and 3.2, 3.1, 3.0 and Swagger 2.0 alike: a 2.0 document and its 3.0
twin generate the same descriptions, which is the claim `VersionsTest` makes.
References to other files are followed and the schemas they name keep those
names; references to another *host* are refused, because a build that fetches a
URL to know what to generate cannot be reproduced.

Where the document is published in pieces by somebody else and rewriting their
`$ref`s would fork their spec, the host can be named — and naming it pins what
it served rather than trusting it every morning:

```kotlin
create("orders") {
    document.set(file("orders.yaml"))
    packageName.set("com.example.orders")
    allowRemote("https://schemas.example.com")
}
```

`updateOrdersEndpointsLock` writes `orders.refs.lock` — every URL reached,
transitively, with the SHA-256 of the bytes that came back — and
`orders.refs.lock.d/`, the documents themselves. Commit both and the build
makes no request at all; a document that changes upstream fails the next update
naming both hashes, and `--accept-changes` is what records it. https only
unless `http://` is written out, redirects never followed, and nothing read
that is not in the lockfile. The whole of it is under
[References](reference.md#references).

A `oneOf` with a `discriminator` comes back as a sealed interface and one data
class per branch, annotated so that Jackson or kotlinx.serialization can
actually read it — `codec.set("kotlinx")` chooses which. Branches are named
from the `discriminator.mapping` key, then the referenced component, then
`<Parent>Variant<n>`, all read out of the document so that the same document
generates the same names every time. An `allOf` of several schemas is flattened
into one class, and refused rather than resolved where two of them disagree
about a property.

**The import is strict.** An operation using something Pelican cannot describe
— two media types for one body, a `oneOf` with nothing saying which branch a
payload is, a streamed response beside another 2xx — fails the build naming
the operation, the place in the document and the way out, rather than
generating an endpoint whose type says less than the document does. That is the right default for a document you own, and an
obstacle in one you do not, so operations you have decided to live without are
listed by `operationId`:

```kotlin
create("orders") {
    document.set(file("orders.yaml"))
    packageName.set("com.example.orders")
    exclude("uploadReceipt", "searchAnything")
}
```

Written down in the build, reviewed once, and — this is the point of a list
rather than a switch — the fourth such operation to appear still fails.
Excluding one also excludes the schemas only it reached, so an `anyOf` in a
corner of the document costs that corner and nothing else.

Losing the operation is the blunt way through, and one refusal has a narrower
one. A `oneOf` with no `discriminator` is refused because a decoder that tries
each branch and keeps the first that parsed is wrong, silently, on the first
payload two branches both accept — and that stands. What changes is who says
which branch a payload is. The document did not; a reader who knows can:

```kotlin
create("orders") {
    document.set(file("orders.yaml"))
    packageName.set("com.example.orders")
    discriminator("Payment", property = "kind")
    discriminator("Order/properties/payment", property = "kind")
}
```

Per schema, in the build file, reviewed once — a component name, or a JSON
pointer for a union the document wrote out inline and never named. The
`discriminator` is written into the document before anything reads it, so the
branch names, the codec annotations and the republished `mapping` are all the
ones a document that had stated it would have produced. Each branch's value on
the wire is read, never invented: a `const` it declares for the property, or
the name of the schema it points at, and an inline branch with neither is
refused. A hint that names a property no branch declares, that addresses
something that is not a union, that gives two branches one value — or that
nothing generated needs any more — fails the build saying which. `anyOf` of
several branches stays refused, hint or no hint.

This repository imports its own document on every build: `:example` publishes
`openapi.json` from its endpoint values, generates descriptions back out of it,
compiles them, and compares what those publish against what it started with.
See `ImportedOrdersTest`.
