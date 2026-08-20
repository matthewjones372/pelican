package example.bookmarks

import dev.pelican.*
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.openapi.openApiJson
import dev.pelican.pekko.*
import dev.pelican.pekko.docs.Docs
import dev.pelican.pekko.docs.startWithDocs
import org.apache.pekko.stream.javadsl.Source

// ============================================================ 1. the models
//
// Plain data classes. No annotations: Jackson reads them through
// jackson-module-kotlin, and swagger-core describes them from the same view.

data class Bookmark(
    val id: Long,
    val url: String,
    val title: String,
    val tags: List<String> = emptyList(),
)

data class CreateBookmark(
    val url: String,
    val title: String,
    val tags: List<String> = emptyList(),
)

/**
 * The failure payload, as a type of its own rather than the framework's
 * generic [ApiError]. Because the endpoint declares it, this is both what the
 * document promises and what the handler is compelled to produce.
 */
data class NoSuchBookmark(val id: Long, val message: String)

// ============================================================ 2. the inputs
//
// Declared once, as values. Each one carries its name, its type and its
// documentation, and is reused by the route, the decoder and the spec.

/**
 * A tag as it travels in a URL. The refinement is enforced *and* documented:
 * a request with `?tag=NOPE` is a 400 before any handler runs, and the
 * document carries the `pattern` that says so. Wrapping it in a value class
 * means the type survives being passed around, and `Slug` cannot be confused
 * with any other string the handler holds.
 */
@JvmInline
value class Slug(val value: String)

val slug: PlainCodec<Slug> = StringCodec
    .matching(Regex("[a-z0-9-]{1,40}"), "a slug: lowercase letters, digits and dashes")
    .map(::Slug, Slug::value)
    .describedAs("A URL-safe tag", example = "streams")

val bookmarkId = pathParam<Long>("bookmarkId", description = "The bookmark's id")

// IntCodec.between documents itself as minimum/maximum, so a caller asking for
// 5000 rows is refused by the description rather than by the database.
val limit = queryParam("limit", IntCodec.between(1, 100), description = "How many to return").default(20)
val tag = queryParam("tag", slug, description = "Only bookmarks with this tag").optional()

// The codec carries the documentation, so every endpoint taking an API key
// describes it the same way without repeating itself.
val apiKey = headerParam("X-Api-Key", StringCodec.nonEmpty().describedAs("The caller's API key", example = "let-me-in"))

val newBookmark = jsonBody<CreateBookmark>(description = "The bookmark to save")

/**
 * A declared failure, named so a handler can return it. `json<Bookmark>()
 * orFail bookmarkMissing` puts it in the endpoint's type, and the binder for
 * that type takes a handler returning `Outcome<NoSuchBookmark, Bookmark>` —
 * so forgetting the 404, or answering it with some other payload, is a
 * compile error.
 */
val bookmarkMissing = errorJson<NoSuchBookmark>(404, "No bookmark with that id")

/** The framework's own error shape, for the case where a type of its own buys nothing. */
val badKey = errorJson<ApiError>(401, "Missing or bad API key")

// ========================================================= 3. the endpoints
//
// This section imports `dev.pelican` and nothing else — no Pekko, no Jackson.
// These are descriptions; they do no work and hold no handler.

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
    jsonArray<Bookmark>()          // chunked `[{...},{...}]`, flushed as produced
}

val createBookmark = endpoint(apiKey, newBookmark) {
    post("bookmarks")
    summary = "Save a bookmark"
    tag("bookmarks")
    json<Bookmark>(status = 201) orFail badKey
}

val deleteBookmark = endpoint(bookmarkId, apiKey) {
    delete("bookmarks" / bookmarkId)
    summary = "Forget a bookmark"
    tag("bookmarks")
    empty(status = 204)
}

val allBookmarkEndpoints = listOf(getBookmark, listBookmarks, createBookmark, deleteBookmark)

// ============================================================== 4. the store
//
// Stand-in for a database.

object Bookmarks {
    private val saved = mutableListOf(
        Bookmark(1, "https://kotlinlang.org", "Kotlin", listOf("lang")),
        Bookmark(2, "https://pekko.apache.org", "Pekko", listOf("lang", "streams")),
    )
    private var nextId = 3L

    fun find(id: Long): Bookmark? = saved.firstOrNull { it.id == id }

    fun list(limit: Int, tag: Slug?): List<Bookmark> =
        saved.filter { tag == null || tag.value in it.tags }.take(limit)

    fun add(req: CreateBookmark): Bookmark =
        Bookmark(nextId++, req.url, req.title, req.tags).also { saved += it }

    fun remove(id: Long) { saved.removeIf { it.id == id } }
}

// ============================================================= 5. the server
//
// The only place that knows a stream is a `Source`. Each handler's parameters
// come from the inputs its endpoint lists, already decoded and typed — add an
// input to a description and this file stops compiling until it is accounted
// for.

val bookmarkRoutes: List<ServerEndpoint> = listOf(

    getBookmark handledOrFail { id ->                 // id: Long
        Bookmarks.find(id)?.let { ok(it) }
            ?: bookmarkMissing(NoSuchBookmark(id, "No bookmark $id"))
    },

    listBookmarks streamedNow { (max, tag) ->         // max: Int, tag: Slug?
        Source.from(Bookmarks.list(max, tag))
    },

    createBookmark handledOrFail { (key, req) ->      // key: String, req: CreateBookmark
        if (key != "let-me-in") badKey(ApiError(401, "Bad API key"))
        else ok(Bookmarks.add(req))
    },

    deleteBookmark handledWith { (id, key) ->         // id: Long, key: String
        if (key != "let-me-in") unauthorized("Bad API key")
        Bookmarks.remove(id)
    },
)

fun bookmarksApi(): Api = Api(
    endpoints = bookmarkRoutes,
    codecs = JacksonCodecs,       // the one argument that picks the JSON library
    title = "Bookmarks",
    version = "1.0.0",
    // No `servers` entry on purpose. Swagger UI's "Try it out" calls the URLs
    // listed there, and a hardcoded one pins every call to that exact origin —
    // so opening the page on 127.0.0.1 while the spec says localhost makes each
    // call cross-origin, and the browser blocks it. Left empty, Swagger UI uses
    // the origin the page was loaded from, which is right either way.
)

/** Docs are opt-in, so where they live is stated separately from the API. */
val bookmarksDocs = Docs(docsPath = "/api-docs")

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    val server = bookmarksApi().startWithDocs(port = port, docs = bookmarksDocs)
    println("Listening on ${server.baseUrl} — docs at ${server.baseUrl}/api-docs")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop().toCompletableFuture().join() })
    Thread.currentThread().join()
}

// ======================================================= 6. the docs, alone
//
// Same descriptions, no server, no handlers. This is what the
// `generateOpenApi` Gradle task runs.

fun bookmarksSpec(): ApiSpec = ApiSpec(
    endpoints = allBookmarkEndpoints,
    schemas = JacksonCodecs,      // documentation needs only the schema half
    title = "Bookmarks",
    version = "1.0.0",
    servers = listOf("http://localhost:8080"),
)

fun writeSpec() = println(bookmarksSpec().openApiJson())
