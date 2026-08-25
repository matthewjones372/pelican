# Golden files

Linked from the [README](../README.md). A test that fails when a change to the
descriptions would break the people already calling the service — a new required
field, a deleted endpoint, a response that stopped carrying something — and
stays quiet when it would not.

---

## What it is for

A developer adds a required field to a request body, runs the suite, and every
test passes. They are all typed tests: the client builds its request from the
same description the server routes on, so the new field is supplied on both
sides and nothing notices. The first thing that notices is the caller who
deployed last month and starts getting a 400.

That is the failure this catches. Not "the document changed" — documents change
constantly and most of it is harmless — but "this change costs somebody who is
already calling us".

## Install

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:pelican-test-golden:0.1.0")
}
```

It brings `pelican-test` and `pelican-openapi` with it, and nothing else — no
server library, no matcher library, no test framework. The assertions throw
plain `AssertionError`, so JUnit, kotest and `kotlin.test` all report them.

Nothing here needs an in-memory transport unless you record a response;
`pelican-test-pekko` and `pelican-test-http4k` stay optional.

## One line covers every endpoint

The endpoints are a list of values, so the tests are derived from the spec
rather than written out one per endpoint:

```kotlin
class OrdersGoldenTest {

    private val golden = Golden()

    @Test
    fun `every endpoint publishes what it published`() {
        golden.operations(ordersSpec())
    }
}
```

That records one file per operation under `src/test/resources/golden/operations/`,
named by `operationId` — or by method and path where an endpoint has none:

```
operations/getUser.json
operations/placeOrder.json
operations/streamOrders.json
operations/webhook-orderPlaced.json
```

Each file holds what that endpoint publishes: its path, its parameters, the
statuses it declares, the schemas they carry. Commit them. They are not a
formality — the committed file is *the contract callers were given*, and it is
the thing the next change is measured against.

An endpoint added tomorrow is recorded by the same line, with no test to write.
An endpoint deleted is caught by the same line, because its golden is left with
nothing to regenerate it.

## What fails, and what does not

The next run compares two OpenAPI documents as documents, not as text, and
classifies every difference from the caller's side of the wire.

**Fails the test.** A caller written against the recorded contract stops
working:

| Change | What it does to them |
|---|---|
| endpoint deleted | 404 for everyone still calling it |
| required parameter or body field added | 400 for everyone not sending it |
| optional parameter or field made required | the same 400 |
| parameter, field or media type removed | what they were sending is no longer read |
| declared status removed | they are handling a response this service says it never sends |
| response field removed, or made nullable | they read it and it is not there |
| response enum value added | a generated client's `when` has never heard of it |
| `operationId` renamed | every generated client renames a method |
| security requirement added | 401 for everyone not sending the credential |
| request constraint tightened (`minLength`, `maximum`, `pattern`, enum narrowed) | payloads that were accepted are refused |

**Updates the golden and passes.** Nobody has to do anything:

new endpoint · new optional parameter or field · a field that became optional ·
a new declared status · a new response field · a request type widened or
constraint loosened · a new media type · deprecation · a rewritten summary,
description or tag.

The passing case rewrites the golden in place, and that is the design rather
than a shortcut. The file's job is to be the contract the *next* change is
measured against; a check that goes red for things nobody must act on is one
whose author learns to accept its output without reading, and that reflex is
exactly how a real break gets waved through. The rewritten file still shows up
in the diff.

A webhook is the same rules with the arrows reversed. Its request is what this
service *sends*, so the subscriber is the one reading it: a field removed from a
webhook body breaks them, and a required field added does not.

### When a break is deliberate

Breaks are allowed — they are announced. The test is telling you that this one
is announced to nobody yet. When it is intentional, accept it the way you accept
any other:

```bash
mv src/test/resources/golden/operations/placeOrder_changed.json \
   src/test/resources/golden/operations/placeOrder.json
```

or, for a batch:

```bash
PELICAN_GOLDEN_UPDATE=true ./gradlew test
```

which also deletes the goldens of endpoints that are gone. The commit that
carries the new golden is the record that somebody decided.

An environment variable because a test JVM inherits the environment and Gradle
does not hand its own `-D` flags to one. The system property `pelican.golden.update`
is read as well, for a build that passes it on:

```kotlin
tasks.test {
    systemProperty("pelican.golden.update", providers.systemProperty("pelican.golden.update").getOrElse("false"))
}
```

### The failure

The count first, because that is the decision; then the operations; then what
each change does to somebody. The changes nobody has to act on are counted at
the end rather than listed, so one break cannot hide under nine harmless ones:

```
Orders 1.0.0 — 2 changes break callers.

  POST /users/{userId}/orders
    ✖ `currency` in the request body (application/json) is new and required
        every caller that is not sending it is refused
    ✖ `quantity` in the request body (application/json) is required now
        a caller leaving it out is refused

  GET /users/{userId}/orders/legacy
    ✖ the endpoint is gone from the descriptions
        every caller still holding it gets a 404 — delete …/legacyOrders.json to retire it

  and 3 changes nothing has to act on.

  What this run produced is beside each golden, as `*_changed.json`.
  If the changes are meant, `mv` each over its golden, or rerun with PELICAN_GOLDEN_UPDATE=true.
```

`operations` compares every endpoint before it throws, so one run names every
endpoint that broke rather than the first of them.

Colour is used where something has said it can be read — an interactive
console, or `FORCE_COLOR` in a CI that renders escapes — and never when
`NO_COLOR` is set. A Gradle test report gets the same text without the escapes,
which is why the layout carries the meaning and colour only underlines it.

## The whole document

`document` is the other reading: one file, the artifact a consumer generates
their client from, compared by the same rules.

```kotlin
@Test
fun `the published document is the one that was reviewed`() {
    golden.document(ordersSpec())            // golden/openapi.json
}
```

Take it when the document is published somewhere in its own right — a portal, a
repository another team reads. Take `operations` when the question is which
endpoint changed. Both is reasonable and costs one more file.

`strict = true` turns off the classification for a `Golden` and fails on every
difference, for a document where the reviewed artifact is the file itself:

```kotlin
private val golden = Golden(strict = true)
```

## The call itself

The document says what a caller should send. The other half is what the client
actually puts on the wire, and there the recording is text: those bytes *are*
the contract, and there is no safe change to the address somebody has to type.

```kotlin
private val calls = requestsOnly(JacksonCodecs)      // builds calls; never sends one

@Test
fun `saving a bookmark builds the call its callers hold`() {
    val bookmark = CreateBookmark("https://pekko.apache.org", "Pekko", listOf("streams", "jvm"))
    golden.request("create", calls.request(createBookmark, In2("let-me-in", bookmark)))
}
```

`golden/create.http` is the request itself:

```http
POST /bookmarks
X-Api-Key: let-me-in

{
  "url": "https://pekko.apache.org",
  "title": "Pekko",
  "tags": [
    "streams",
    "jvm"
  ]
}
```

`requestsOnly` builds and never sends, so a suite of these costs no server and
no port; sending through it fails, naming the transports that do. JSON bodies
are re-rendered one field per line, so a codec that starts spelling a field
differently is one changed line. A form body, a rendered page or a stream is
recorded exactly as it travelled.

With a server running, `exchange` records both halves in one file:

```kotlin
private val app = ordersApi().inMemory()

@Test
fun `fetching a user answers what it answered`() {
    golden.exchange("get-user", app, getUser, 1L)
}
```

Response headers that differ between two runs of the same test would fail it for
saying so, so `Date`, `Server`, `Connection` and `Keep-Alive` are left out. A
service whose responses carry a request id of its own passes its own set:

```kotlin
Golden(ignoringHeaders = VOLATILE_HEADERS + "X-Request-Id")
```

## The first run

There is nothing to compare against, so the run writes `<name>_new.<ext>` and
**fails**. A snapshot accepted by the run that produced it is not a review: read
it, rename it, commit it.

```bash
mv src/test/resources/golden/openapi_new.json src/test/resources/golden/openapi.json
```

`PELICAN_GOLDEN_UPDATE=true` on the first run does the renaming for you, which is
the reasonable thing to do for an API that already exists and a suite you are
adding today.

## From Gradle, without a test

The same check runs as a build task. Give a `documents` entry a `baseline` — the
document your callers hold, committed — and the plugin registers
`check<Name>Document` and wires it into `check`:

```kotlin
pelican {
    documents {
        create("orders") {
            specClass.set("com.example.GenerateOpenApiKt")
            outputFile.set(layout.buildDirectory.file("openapi.json"))
            baseline.set(layout.projectDirectory.file("src/test/resources/golden/openapi.json"))
        }
    }
}
```

```
> Task :example:checkOrdersDocument FAILED
openapi.json — 2 changes break callers.

  GET /users/{userId}
    ✖ `nickname` in the 200 response (application/json) is gone
        a caller reading it gets nothing

  GET /users/{userId}/orders/legacy
    ✖ the operation is gone
        every caller still holding it gets a 404
```

Nothing breaking prints `openapi.json — 4 changes, none of them breaking.` and
the build carries on, which also makes the task the short answer to "what does
this release change for callers".

Pointing the task and `golden.document(...)` at the same committed file is the
tidiest arrangement: one contract, checked by the suite and by `./gradlew check`
without a suite. That is what `example` does.

## The comparison on its own

The classification is not part of the test module. It is
`pelican-openapi`, over two documents:

```kotlin
val published = parseJson(File("openapi.json").readText()) as JsonObj

apiChanges(published, ordersSpec().openApi())
    .filter { it.compatibility == Compatibility.BREAKING }
    .forEach { println(it) }                 // POST /orders — `currency` … is new and required
```

That is the same call the golden files make, exposed for a CI step that compares
against the document a deployed service is serving, or a release note that lists
what changed for callers.

## Where the files live

`Golden()` writes to `src/test/resources/golden`, resolved against the module
directory — a test's working directory under Gradle and under Maven. Point it
elsewhere if you would rather not ship them in the jar's resources:

```kotlin
Golden(directory = Paths.get("src", "test", "golden"))
```

## What this does not do

It does not generate sample payloads. A recorded request is one you wrote the
input for, because a synthesised `CreateOrder` would pin the generator's
imagination rather than your contract; `operations` needs no input at all, which
is why it is the one that scales to a whole API.

It does not know about your versioning. A break is reported as a break whether
or not it is going out under a new major version — accepting it is how you say
which it is.

It does not replace the typed tests. Behaviour tests should *not* break on a
rename, and these should. `BookmarksContractTest` exercises what the service
does; the goldens beside it record what it promises.
