# A whole service, in one file

One service with everything in it: models, inputs, endpoints, handlers, a
store, the server and its docs. It is the file to read when you want to see how
the pieces fit together rather than one feature at a time; the
[cookbook](cookbook.md) has the features one at a time.

The code below is
[`ReadmeExample.kt`](../example/src/main/kotlin/example/readme/ReadmeExample.kt),
which the example module compiles on every build. Run it from a clone with:

```bash
./gradlew :example:runReadmeExample
```

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("io.github.matthewjones372:pelican-bom:$pelicanVersion"))
    implementation("io.github.matthewjones372:pelican-jetty")
    implementation("io.github.matthewjones372:pelican-jackson")
    implementation("io.github.matthewjones372:pelican-jetty-docs")
}
```

`pelicanVersion` is the release you pin, `1.2.0`. To run on Pekko HTTP, use
the Pekko dependencies from [Getting started](../README.md#getting-started);
the service changes in three places, listed [after the code](#on-pekko-instead).

```kotlin
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.between
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.describedAs
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.docs.startWithDocs
import io.github.matthewjones372.pelican.jetty.handledOrFail
import io.github.matthewjones372.pelican.jetty.streamedNow
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.map
import io.github.matthewjones372.pelican.matching
import io.github.matthewjones372.pelican.nonEmpty
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.queryParam

// ---------------------------------------------------------------- 1. models

data class Bookmark(val id: Long, val url: String, val title: String, val tags: List<String> = emptyList())
data class CreateBookmark(val url: String, val title: String, val tags: List<String> = emptyList())
data class NoSuchBookmark(val id: Long, val message: String)

// ---------------------------------------------------------------- 2. inputs
//
// Declared once as values, and reused by the route, the decoder, the document
// and the test client. Refinements are enforced *and* documented.

@JvmInline value class Slug(val value: String)

val slug = StringCodec
    .matching(Regex("[a-z0-9-]{1,40}"), "a slug: lowercase letters, digits and dashes")
    .map(::Slug, Slug::value)
    .describedAs("A URL-safe tag", example = "streams")

val bookmarkId  = pathParam<Long>("bookmarkId", description = "The bookmark's id")
val limit       = queryParam("limit", IntCodec.between(1, 100), description = "How many to return").default(20)
val tag         = queryParam("tag", slug, description = "Only bookmarks with this tag").optional()
val apiKey      =
    headerParam("X-Api-Key", StringCodec.nonEmpty().describedAs("The caller's API key", example = "let-me-in"))
val newBookmark = jsonBody<CreateBookmark>(description = "The bookmark to save")

val bookmarkMissing = errorJson<NoSuchBookmark>(404, "No bookmark with that id")
val badKey          = errorJson<ApiError>(401, "Missing or bad API key")

// ------------------------------------------------------------- 3. endpoints
//
// This section imports `io.github.matthewjones372.pelican` and nothing else. No server library, no Jackson.
// These are descriptions: they do no work and hold no handler.

val getBookmark = endpoint(bookmarkId) {
    get("bookmarks" / bookmarkId)
    summary = "Fetch one bookmark"
    tag("bookmarks")
    json<Bookmark>() orFail bookmarkMissing
}

val listBookmarks = endpoint(limit, tag) {
    get("bookmarks")
    summary = "List bookmarks, newest first"
    tag("bookmarks")
    jsonArray<Bookmark>()            // chunked `[{...},{...}]`, flushed as produced
}

val createBookmark = endpoint(apiKey, newBookmark) {
    post("bookmarks")
    summary = "Save a bookmark"
    tag("bookmarks")
    json<Bookmark>(status = 201) orFail badKey
}

// ----------------------------------------------------------------- 4. store

object Bookmarks {
    private val saved = mutableListOf(Bookmark(1, "https://pekko.apache.org", "Pekko", listOf("streams")))
    private var nextId = 2L

    fun find(id: Long) = saved.firstOrNull { it.id == id }
    fun list(limit: Int, tag: Slug?) = saved.filter { tag == null || tag.value in it.tags }.take(limit)
    fun add(req: CreateBookmark) = Bookmark(nextId++, req.url, req.title, req.tags).also { saved += it }
}

// -------------------------------------------------------------- 5. handlers
//
// The only place that knows a stream is a `Sequence`. Every parameter arrives
// decoded and typed, in the order the endpoint declared it.

val routes = listOf(
    getBookmark handledOrFail { id ->                       // id: Long
        Bookmarks.find(id)?.let { ok(it) }
            ?: bookmarkMissing(NoSuchBookmark(id, "No bookmark $id"))
    },

    listBookmarks streamedNow { (max, tag) ->               // max: Int, tag: Slug?
        Bookmarks.list(max, tag).asSequence()
    },

    createBookmark handledOrFail { (key, req) ->            // key: String, req: CreateBookmark
        if (key != "let-me-in") badKey(ApiError(401, "Bad API key"))
        else ok(Bookmarks.add(req))
    },
)

// ---------------------------------------------------------------- 6. server

fun main() {
    val api = api(routes, codecs = JacksonCodecs) {
        title = "Bookmarks"
        version = "1.0.0"
    }
    val server = api.startWithDocs(port = 8080, docs = docs { docsPath = "/api-docs" })
    println("Listening on ${server.baseUrl}, docs at ${server.baseUrl}/api-docs")
}
```

Swagger UI is at `/api-docs` and the document at `/openapi.json`. Both are
generated from the values above.

```bash
curl 127.0.0.1:8080/bookmarks/1          # {"id":1,"url":"https://pekko.apache.org",...}
curl 127.0.0.1:8080/bookmarks/42         # 404 {"id":42,"message":"No bookmark 42"}
curl '127.0.0.1:8080/bookmarks?limit=0'  # 400, naming the constraint it broke
open http://127.0.0.1:8080/api-docs      # Swagger UI
```

## How it is laid out

The six numbered sections are in the order the service depends on them, and
each decision below is one the rest of the docs assume.

**Inputs are declared once, as values.** `bookmarkId`, `limit`, `tag` and the
rest are ordinary `val`s, not annotations or strings inside a route. The same
value routes the request, decodes the parameter, writes it into the OpenAPI
document and builds the request in the typed test client, so a rename or a
type change reaches all four at once. A refinement such as
`IntCodec.between(1, 100)` is enforced before the handler runs and appears in
the schema, which is why `?limit=0` is a 400 the document already predicted.
`Slug` is a value class over `String`, so a tag cannot be passed where some
other string was meant.

**Failures are values too.** `bookmarkMissing` and `badKey` are listed on their
endpoints with `orFail`, which puts them in the endpoint's type: the handler
returns `ok(...)` or one of them, and the document lists exactly those
statuses.

**The descriptions import nothing but core.** Section 3 uses only
`io.github.matthewjones372.pelican`. An endpoint description does no work and
holds no handler, so the OpenAPI generator, the test client and the client
generator read it without a server, and either backend can bind it.

**Handlers live in one list, apart from the descriptions.** Section 5 pairs
each description with its implementation through a backend's binder
(`handledOrFail`, `streamedNow`). It is the only section that knows which
server runs the service, and the destructured parameters arrive decoded and
typed in the order the endpoint declared them. `routes` is the list `api(...)`
serves; a larger service keeps one such list per area and concatenates them.

**The store is a stand-in.** `Bookmarks` is an in-memory object so the file
runs on its own; a real service puts its own data access there.

## On Pekko instead

On a service that runs Pekko HTTP, three things change:

- The binder imports: `io.github.matthewjones372.pelican.pekko.handledOrFail`
  and `io.github.matthewjones372.pelican.pekko.streamedNow` in place of the
  `jetty` ones. The names are the same.
- `listBookmarks`' handler returns a Pekko `Source` instead of a `Sequence`:
  `Source.from(Bookmarks.list(max, tag))`, importing
  `org.apache.pekko.stream.javadsl.Source`.
- `startWithDocs` comes from `io.github.matthewjones372.pelican.pekko.docs`.

Sections 1 to 4 and 6 are unchanged.
[On Pekko](cookbook.md#on-pekko) in the cookbook shows the same switch on the
one-endpoint example.
