# A whole service, in one file

Linked from the [README](../README.md). Models, inputs, endpoints, handlers,
store, server and docs, in one file — too long for a front page, and the thing
to read once you want to see all of it at once.

Models, inputs, endpoints, handlers, store, server and docs. This block lives in
the repo as [`ReadmeExample.kt`](../example/src/main/kotlin/example/readme/ReadmeExample.kt),
so it compiles on every build. Run it with `./gradlew :example:runReadmeExample`.

```kotlin
import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pekko.*
import io.github.matthewjones372.pelican.pekko.docs.Docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import org.apache.pekko.stream.javadsl.Source

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
// This section imports `io.github.matthewjones372.pelican` and nothing else. No Pekko, no Jackson.
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
// The only place that knows a stream is a `Source`. Every parameter arrives
// decoded and typed, in the order the endpoint declared it.

val routes = listOf(
    getBookmark handledOrFail { id ->                       // id: Long
        Bookmarks.find(id)?.let { ok(it) }
            ?: bookmarkMissing(NoSuchBookmark(id, "No bookmark $id"))
    },

    listBookmarks streamedNow { (max, tag) ->               // max: Int, tag: Slug?
        Source.from(Bookmarks.list(max, tag))
    },

    createBookmark handledOrFail { (key, req) ->            // key: String, req: CreateBookmark
        if (key != "let-me-in") badKey(ApiError(401, "Bad API key"))
        else ok(Bookmarks.add(req))
    },
)

// ---------------------------------------------------------------- 6. server

fun main() {
    val api = Api(routes, codecs = JacksonCodecs, title = "Bookmarks", version = "1.0.0")
    val server = api.startWithDocs(port = 8080, docs = Docs(docsPath = "/api-docs"))
    println("Listening on ${server.baseUrl} — docs at ${server.baseUrl}/api-docs")
}
```

Swagger UI is at `/api-docs` and the document at `/openapi.json`. Both are
generated from the values above.

```bash
curl localhost:8080/bookmarks/1          # {"id":1,"url":"https://pekko.apache.org",...}
curl localhost:8080/bookmarks/42         # 404 {"id":42,"message":"No bookmark 42"}
curl 'localhost:8080/bookmarks?limit=0'  # 400, naming the constraint it broke
open localhost:8080/api-docs             # Swagger UI
```
