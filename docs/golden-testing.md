# Golden files

A golden test fails when a change to your endpoint descriptions would break
someone already calling the service: a new required field, a deleted
endpoint, a response that stops carrying something they read. A change that
costs callers nothing passes, and the recorded file is updated in place.

## What it is for

Pelican's typed tests build requests from the same descriptions the server
routes on. That is why renaming an input is a compile error rather than a
404, and also why the suite cannot see a contract change: add a required
field to a request body and the test client sends it, the server reads it,
and every test passes. The first thing to notice is a caller deployed last
month that now gets a 400.

A golden test compares what the descriptions publish now against a file
committed earlier, which is the contract callers were given, and fails only
on the differences that would break them.

## Setup

`pelican-test-golden` is in 1.0.0:

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:pelican-test-golden:1.0.0")
    testImplementation("io.github.matthewjones372:pelican-jackson:1.0.0") // the Codecs requestsOnly takes
}
```

It brings `pelican-test` and `pelican-openapi` and no server library, matcher
library or test framework. Failures are plain `AssertionError`s, so JUnit,
Kotest and `kotlin.test` all report them. Recording a response (rather than a
request) needs an in-memory client from `pelican-test-pekko` (in 1.0.0) or
`pelican-test-jetty` (on `main`, not yet released).

## Recording every endpoint

From `example/src/test/kotlin/example/GoldenContractTest.kt`:

```kotlin
import io.github.matthewjones372.pelican.test.golden.Golden
import org.junit.jupiter.api.Test

class GoldenContractTest {

    private val golden = Golden()

    @Test
    fun `every endpoint publishes what it published`() {
        golden.operations(ordersSpec())
    }
}
```

`operations` walks the spec and records one file per endpoint under
`src/test/resources/golden/operations/`, named by `operationId`, or by method
and path when there is none (`get-bookmarks-bookmarkId.json`). Webhooks get
`webhook-<name>.json`. Hidden endpoints are skipped. The example's files:

```
golden/operations/getUser.json
golden/operations/placeOrder.json
golden/operations/streamOrders.json
golden/operations/webhook-orderPlaced.json
...
```

Each file is that endpoint's slice of the OpenAPI document: its path item,
parameters, declared responses and the schemas they reference, plus the API's
security requirements. A new endpoint is recorded by the same line with no new
test. A deleted endpoint is caught because its file is left with nothing to
regenerate it.

`golden.operation(spec, endpoint)` records a single endpoint the same way.

## Running it

Golden tests are ordinary tests: `./gradlew test`. `Golden()` resolves its
directory, `src/test/resources/golden`, against the working directory, which
Gradle sets to the module directory for tests. To keep the files elsewhere,
pass `directory`, as `example/src/test/kotlin/example/bookmarks/BookmarksContractGoldenTest.kt`
does:

```kotlin
private val golden = Golden(directory = Paths.get("src", "test", "resources", "golden", "bookmarks"))
```

Commit the golden files. Each run leaves at most two kinds of file beside
them, and both are for reading, not committing:

| File | Written when | What to do |
|---|---|---|
| `<name>_new.<ext>` | there is no golden yet | read it, then rename it to `<name>.<ext>` and commit it |
| `<name>_changed.<ext>` | the run differs from the golden in a way that fails | diff it against the golden; see [When a golden test fails](#when-a-golden-test-fails) |

The first run of a new golden therefore **fails**: a snapshot accepted by the
run that produced it has not been reviewed. For an API that already exists and
is getting its first golden suite, accepting everything at once is reasonable:

```bash
PELICAN_GOLDEN_UPDATE=true ./gradlew test
```

## Updating goldens

There are two ways to accept what a run produced.

**One file at a time.** Move the `_changed` (or `_new`) file over the golden:

```bash
mv src/test/resources/golden/operations/placeOrder_changed.json \
   src/test/resources/golden/operations/placeOrder.json
```

**Everything the run touches.** Set the update switch. `Golden` reads it when
it is constructed, from either source:

- the environment variable `PELICAN_GOLDEN_UPDATE=true`, which a forked test
  JVM inherits, so it needs no build configuration;
- the system property `pelican.golden.update=true`. Gradle does not pass its
  own `-D` flags to the test JVM, so a build that wants this one forwards it:

  ```kotlin
  tasks.test {
      systemProperty("pelican.golden.update", providers.systemProperty("pelican.golden.update").getOrElse("false"))
  }
  ```

In update mode nothing is compared. Every golden the run touches is
rewritten, breaking changes included, any `_new` and `_changed` files beside
them are deleted, and `operations` deletes the files of endpoints that no
longer exist. Review the resulting `git diff` before committing: the commit
that carries a changed golden is the record that someone decided the change
was acceptable.

## What fails and what passes

Contract goldens (`operations`, `operation`, `document`) are compared as
OpenAPI documents, not as text. Every difference is classified by what it
does to a caller, as one of three `Compatibility` values from
`pelican-openapi`: `BREAKING`, `COMPATIBLE` or `COSMETIC`.

**Breaking changes fail the test**, because a caller written against the
recorded contract stops working:

| Change | What it does to callers |
|---|---|
| endpoint deleted | 404 for everyone still calling it |
| required parameter or body field added | 400 for everyone not sending it |
| optional parameter or field made required | the same 400 |
| parameter, field or media type removed | what they send is no longer read |
| declared status removed | they handle a response the service says it never sends |
| response field or header removed | they read it and it is not there |
| response field or header no longer always present (not required, or `null` added to its type) | they were promised it |
| response enum value added | a generated client's `when` has never seen it |
| response `oneOf` shape added, or its tag field renamed | a client switching on the tag meets one it does not know |
| request `oneOf` shape or enum value removed | what they send is no longer accepted |
| `operationId` renamed | every generated client renames a method |
| security requirement added | 401 for everyone not sending the credential |
| request constraint tightened (`minLength`, `maximum`, `pattern`, and so on) | payloads that were accepted are refused |

**Compatible and cosmetic changes pass**, and the golden is rewritten in
place: a new endpoint, parameter, optional field, response field, status or
media type; a field that became optional; a request type widened or a
constraint loosened; a deprecation; a changed summary or description. The
rewritten file shows up in `git diff`, so it is reviewed with the change that
caused it. The test stays green because a check that fails on changes nobody
has to act on gets accepted without being read.

Webhooks use the same rules in the other direction. The service sends a
webhook's request, so the subscriber is the reader: removing a field from a
webhook body breaks them, adding a required one does not.

A schema that becomes a `oneOf`, as a status does when its failure is split
into [tagged failures](reference.md#declared-failures), is compared against
each shape in turn. A field every shape still has is not reported; a field one
shape dropped is a break naming that shape.

To fail on every difference instead, for a document whose every line is
reviewed, pass `strict = true`:

```kotlin
private val golden = Golden(strict = true)
```

## When a golden test fails

The failure message is a report, printed in this order:

1. A headline with the number of breaking changes, headed by the API's title
   and version (for `operations`) or the golden's file name (for `document`).
2. Each affected operation (`POST /users/{userId}/orders`), and under it one
   line per breaking change, with what it does to a caller indented below:

   ```
     POST /users/{userId}/orders
       ✖ `currency` in the request body (application/json) is new and required
           every caller that is not sending it is refused
       ✖ `quantity` in the request body (application/json) is required now
           a caller leaving it out is refused
   ```

3. A count of the non-breaking changes in the same run, not listed.
4. Where the `_changed` files were written and how to accept them.

`operations` compares every endpoint before throwing, so one run names every
endpoint that broke. The marks are `✖` and `✔` where `file.encoding` is UTF-8
and `x` and `+` otherwise. Colour is used only with an interactive console or
`FORCE_COLOR`, never with `NO_COLOR`. A Gradle test JVM has no console, so
unless `FORCE_COLOR` is set the test report gets plain text.

Then decide which of these it is:

- **A regression.** You did not mean to change the contract: a refactor
  renamed an `operationId`, a schema change made a field required, a codec
  started omitting a field. Fix the code and rerun; the `_changed` file is
  deleted when the run matches again.
- **An intended break.** You meant it and the callers have been told (a new
  major version, a deprecation period that has ended). Accept it with `mv` or
  `PELICAN_GOLDEN_UPDATE=true`, and commit the new golden with the change.
  Pelican does not know your versioning: a break is reported whether or not
  it ships under a new major version, and accepting the golden is how you say
  which it is.
- **A break you can avoid.** Often the intended change has a compatible form:
  add the field as optional with a default, add a new endpoint beside the old
  one and deprecate the old one. The report says exactly which part breaks, so
  rework that part and rerun.

A deleted endpoint is reported with the path of its golden file. If the
deletion is intended, delete that file (or run in update mode).

## The whole document

`document` records the whole OpenAPI document in one file and compares it by
the same rules. From `GoldenContractTest`:

```kotlin
@Test
fun `the published document is the one that was reviewed`() {
    golden.document(ordersSpec())            // golden/openapi.json
}
```

Use `document` when the document is published in its own right (a portal, a
repository another team generates clients from), and `operations` when you
want failures and diffs to name the endpoint. The example uses both.

## Recording the call itself

The document says what a caller should send; a wire recording pins what a
request actually looks like. It is compared as text, and any difference fails,
because there is no compatible change to an address a caller has to type. From
`BookmarksContractGoldenTest`:

```kotlin
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.golden.Golden
import io.github.matthewjones372.pelican.test.golden.requestsOnly
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class BookmarksContractGoldenTest {

    private val golden = Golden(directory = Paths.get("src", "test", "resources", "golden", "bookmarks"))
    private val calls = requestsOnly(JacksonCodecs)

    @Test
    fun `saving a bookmark builds the call its callers hold`() {
        val bookmark = CreateBookmark("https://pekko.apache.org", "Pekko", listOf("streams", "jvm"))
        golden.request("create", calls.request(createBookmark, In2("let-me-in", bookmark)))
    }
}
```

`golden/bookmarks/create.http`:

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

`requestsOnly` builds requests and never sends them, so these tests need no
server and no port; sending through it throws `UnsupportedOperationException`.
JSON bodies are re-rendered one field per line, so a field a codec spells
differently is a one-line diff. A form body, a rendered page or a stream is
recorded as it travelled. A mismatch prints the first three differing lines
with their line numbers.

`golden.response(name, response)` records a response, and `golden.exchange`
sends a call and records both halves in one file. Both need a client that
sends, such as an in-memory one:

```kotlin
import io.github.matthewjones372.pelican.test.pekko.inMemory      // pelican-test-pekko

private val app = ordersApi().inMemory()

@Test
fun `fetching a user answers what it answered`() {
    golden.exchange("get-user", app, getUser, 1L)
}
```

Headers that differ between two runs of the same test (`Date`, `Server`,
`Connection`, `Keep-Alive`, the `VOLATILE_HEADERS` set) are left out of
recorded responses. If your service adds its own, such as a request id, pass
the set:

```kotlin
Golden(ignoringHeaders = VOLATILE_HEADERS + "X-Request-Id")
```

`golden.text(name, extension, actual)` gives any other derived text the same
workflow (`_new`, `_changed`, update mode), compared as text.

## From Gradle, without a test

The Gradle plugin runs the same classification as a build task. Give a
`documents` entry a `baseline`, the committed document callers hold, and the
plugin wires `check<Name>Document` into `check`. From `example/build.gradle.kts`:

```kotlin
pelican {
    documents {
        create("orders") {
            specClass.set("example.GenerateOpenApiKt")
            specFunction.set("ordersSpec")
            outputFile.set(layout.buildDirectory.file("openapi.json"))
            baseline.set(layout.projectDirectory.file("src/test/resources/golden/openapi.json"))
        }
    }
}
```

The task generates the document from the descriptions, at the entry's
`openApiVersion`, compares it with the baseline and prints the same report.
It fails the build when any change is breaking, naming the count and the
baseline; otherwise it lists every change and the build continues, which also
makes it a quick answer to "what does this change for callers?". The baseline
must be a JSON document. The task needs `pelican-openapi` on the entry's
classpath (by default `main`'s runtime classpath).

The task never writes the baseline. To accept an intended break, replace the
committed file with the new document: run `generate<Name>Document` and copy
its `outputFile` over the baseline, or, where the baseline is also a
`golden.document(...)` file as in the example, run the tests with
`PELICAN_GOLDEN_UPDATE=true`. Pointing both at one file keeps a single
committed contract, checked by the test suite and by `./gradlew check`.

## The comparison on its own

The classification lives in `pelican-openapi` and works on any two
documents, for example the one a deployed service is serving:

```kotlin
import io.github.matthewjones372.pelican.openapi.Compatibility
import io.github.matthewjones372.pelican.openapi.apiChanges
import io.github.matthewjones372.pelican.openapi.documentOf
import io.github.matthewjones372.pelican.openapi.openApi
import io.github.matthewjones372.pelican.openapi.report
import java.io.File

fun main() {
    val published = documentOf(File("openapi.json").readText())
    val changes = apiChanges(published, ordersSpec().openApi())

    println(changes.report("Orders"))
    val breaking = changes.count { it.compatibility == Compatibility.BREAKING }
    if (breaking > 0) System.exit(1)
}
```

Each `ApiChange` has `compatibility`, `where` (`POST /users/{userId}/orders`),
`what` and `consequence`.

## What it does not do

- **It does not generate sample payloads.** A recorded request is one you
  wrote the input for. `operations` needs no input at all, which is why it is
  the one that covers a whole API.
- **It does not replace behaviour tests.** Behaviour tests should survive a
  rename; golden tests should not. In the example, `BookmarksContractTest`
  checks what the service does and `BookmarksContractGoldenTest` records what
  it promises.
