# Golden files

Linked from the [README](../README.md). A golden test fails when a change to
your endpoint descriptions would break the people already calling your
service — a new required field, a deleted endpoint, a response that no longer
carries something they read. When a change costs callers nothing, the test
stays quiet.

---

## What it is for

Here is the problem. A developer adds a required field to a request body and
runs the test suite. Every test passes — and that is exactly the trap. The
tests are typed: the test client builds its requests from the same
descriptions the server routes on, so both sides already agree about the new
field and nothing notices. The first thing that notices is a caller who
deployed last month and suddenly starts getting a 400.

That is the failure golden tests catch. The question is never "did the
document change?" — documents change constantly, and most changes are
harmless. The question is "does this change cost somebody who is already
calling us?"

## Install

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:pelican-test-golden:1.0.0-RC1")
}
```

This brings in `pelican-test` and `pelican-openapi`, and nothing else — no
server library, no matcher library, no test framework. The assertions throw
plain `AssertionError`, so JUnit, kotest and `kotlin.test` all report them.

You only need an in-memory transport if you record a response;
`pelican-test-pekko` stays optional.

## One line covers every endpoint

Your endpoints are a list of values, so the tests can be derived from the
spec instead of written out one per endpoint:

```kotlin
class OrdersGoldenTest {

    private val golden = Golden()

    @Test
    fun `every endpoint publishes what it published`() {
        golden.operations(ordersSpec())
    }
}
```

This records one file per operation under
`src/test/resources/golden/operations/`. Files are named by `operationId`, or
by method and path when an endpoint has no `operationId`:

```
operations/getUser.json
operations/placeOrder.json
operations/streamOrders.json
operations/webhook-orderPlaced.json
```

Each file holds what that endpoint publishes: its path, its parameters, the
statuses it declares, and the schemas they carry. Commit these files. They
are not a formality — the committed file *is* the contract your callers were
given, and it is what the next change gets measured against.

Add an endpoint tomorrow and the same one line records it; there is no new
test to write. Delete an endpoint and the same line catches it, because the
endpoint's golden file is left behind with nothing to regenerate it.

## What fails, and what does not

On the next run, the test compares two OpenAPI documents as structured
documents, not as text. It classifies every difference by one rule: what does
this do to a caller on the other side of the wire?

**These fail the test**, because a caller written against the recorded
contract stops working:

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

**These update the golden file and pass**, because nobody has to do
anything: a new endpoint, a new optional parameter or field, a field that
became optional, a new declared status, a new response field, a request type
widened or a constraint loosened, a new media type, a deprecation, or a
rewritten summary, description or tag.

Rewriting the golden file on a harmless change is deliberate, not a shortcut.
The file's job is to be the contract the *next* change is measured against.
A check that goes red for changes nobody must act on trains its owner to
accept its output without reading it — and that reflex is exactly how a real
break gets waved through. The rewritten file still appears in your git diff,
so nothing changes silently.

Webhooks follow the same rules with the direction reversed. A webhook's
request is what this service *sends*, so the subscriber is the one reading
it: removing a field from a webhook body breaks them, while adding a required
field does not.

### When a break is deliberate

Breaking changes to *your* contract are allowed — but they are announced. A
failing golden test means this break is so far announced to nobody. When the
break is intentional, accept it the same way you accept any change:

```bash
mv src/test/resources/golden/operations/placeOrder_changed.json \
   src/test/resources/golden/operations/placeOrder.json
```

or, to accept a whole batch:

```bash
PELICAN_GOLDEN_UPDATE=true ./gradlew test
```

The batch form also deletes the golden files of endpoints that are gone. The
commit that carries the new golden file is the record that somebody decided
this break was acceptable.

Why an environment variable? A test JVM inherits the environment, but Gradle
does not pass its own `-D` flags through to test JVMs. The system property
`pelican.golden.update` is also read, for builds that forward it explicitly:

```kotlin
tasks.test {
    systemProperty("pelican.golden.update", providers.systemProperty("pelican.golden.update").getOrElse("false"))
}
```

### Reading a failure

The report puts the count first, because the count is the decision. Then it
lists the affected operations, and under each one, what the change does to a
caller. Harmless changes are counted at the end but not listed, so one real
break cannot hide under nine harmless entries:

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

`operations` compares every endpoint before it throws, so one run names
every endpoint that broke — not just the first one it found.

Colour appears only where something has said it can be rendered: an
interactive console, or `FORCE_COLOR` in a CI system that renders escape
codes. It never appears when `NO_COLOR` is set. A Gradle test report gets the
same text without the escapes — the layout carries the meaning, and colour
only underlines it.

## The whole document

`document` is the other view: one file holding the whole OpenAPI document —
the artifact a consumer generates their client from — compared by the same
rules.

```kotlin
@Test
fun `the published document is the one that was reviewed`() {
    golden.document(ordersSpec())            // golden/openapi.json
}
```

Use `document` when the document is published somewhere in its own right — a
portal, a repository another team reads. Use `operations` when the question
is *which endpoint* changed. Using both is reasonable and costs one more
file.

For a document where the reviewed artifact is the file itself, `strict =
true` turns the classification off and fails on every difference:

```kotlin
private val golden = Golden(strict = true)
```

## The call itself

The document says what a caller *should* send. The other half of the
contract is what the client actually puts on the wire. That recording is
plain text, because those bytes *are* the contract — there is no safe way to
change an address somebody has to type.

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

`requestsOnly` builds requests and never sends them, so a suite of these
needs no server and no port. Trying to send through it fails with an error
naming the transports that do send. JSON bodies are re-rendered one field per
line, so when a codec starts spelling a field differently, the diff is one
changed line. A form body, a rendered page or a stream is recorded exactly as
it travelled.

With a server running, `exchange` records both halves of the conversation in
one file:

```kotlin
private val app = ordersApi().inMemory()

@Test
fun `fetching a user answers what it answered`() {
    golden.exchange("get-user", app, getUser, 1L)
}
```

Some response headers differ between two runs of the same test, and
recording them would fail the test for saying so. `Date`, `Server`,
`Connection` and `Keep-Alive` are therefore left out. If your service stamps
its own volatile header — a request id, say — pass your own set:

```kotlin
Golden(ignoringHeaders = VOLATILE_HEADERS + "X-Request-Id")
```

## The first run

On the first run there is nothing to compare against, so the test writes
`<name>_new.<ext>` and **fails**. This is on purpose: a snapshot accepted by
the same run that produced it has not been reviewed by anyone. Read the file,
rename it, commit it.

```bash
mv src/test/resources/golden/openapi_new.json src/test/resources/golden/openapi.json
```

`PELICAN_GOLDEN_UPDATE=true` on the first run does the renaming for you.
That is a reasonable shortcut when the API already exists and you are adding
the suite today.

## From Gradle, without a test

The same check can run as a build task. Give a `documents` entry a
`baseline` — the committed document your callers hold — and the plugin
registers `check<Name>Document` and wires it into `check`:

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

When nothing breaks, the task prints `openapi.json — 4 changes, none of them
breaking.` and the build carries on. That also makes the task the short
answer to "what does this release change for callers?"

The tidiest arrangement is to point the task and `golden.document(...)` at
the same committed file: one contract, checked both by the test suite and by
`./gradlew check` without one. That is what `example` does.

## The comparison on its own

The classification logic is not locked inside the test module. It lives in
`pelican-openapi` and works on any two documents:

```kotlin
val published = parseJson(File("openapi.json").readText()) as JsonObj

apiChanges(published, ordersSpec().openApi())
    .filter { it.compatibility == Compatibility.BREAKING }
    .forEach { println(it) }                 // POST /orders — `currency` … is new and required
```

This is the same call the golden files make, exposed directly. Use it in a
CI step that compares against the document a deployed service is actually
serving, or to build a release note listing what changed for callers.

## Where the files live

`Golden()` writes to `src/test/resources/golden`, resolved against the
module directory — which is a test's working directory under both Gradle and
Maven. If you would rather not ship the files in the jar's resources, point
it elsewhere:

```kotlin
Golden(directory = Paths.get("src", "test", "golden"))
```

## What this does not do

**It does not generate sample payloads.** A recorded request is one you
wrote the input for. A synthesised `CreateOrder` would pin down the
generator's imagination rather than your contract. This is also why
`operations` is the variant that scales to a whole API: it needs no input at
all.

**It does not know about your versioning.** A break is reported as a break
whether or not it ships under a new major version. Accepting the golden is
how you say which one it is.

**It does not replace the typed tests.** Behaviour tests should *not* break
on a rename; golden tests should. `BookmarksContractTest` exercises what the
service does. The goldens beside it record what the service promises.
