# The same endpoints, by hand

Linked from the [README](../README.md). Two endpoints written twice — once
described with Pelican, once directly against Pekko HTTP with the document
produced the usual way — so the difference is a thing you can read rather
than a claim you have to take.

Two endpoints. `GET /bookmarks/{bookmarkId}` answers with a `Bookmark` or a 404
carrying a `NoSuchBookmark`. `GET /bookmarks` takes a `limit` that defaults to
20 and has to be between 1 and 100, an optional `tag` that has to look like a
slug, and streams the matches as a JSON array. Both appear in an OpenAPI
document.

Written directly against Pekko HTTP, with the document produced the usual way:
swagger annotations, scanned at startup.

```kotlin
data class Bookmark(val id: Long, val url: String, val title: String, val tags: List<String> = emptyList())
data class NoSuchBookmark(val id: Long, val message: String)
data class ApiError(val status: Int, val error: String)

private val slug = Regex("[a-z0-9-]{1,40}")

@Path("/bookmarks")                            // read by the scanner; serves nothing
class BookmarkRoutes : AllDirectives() {

    @GET
    @Path("/{bookmarkId}")
    @Operation(
        summary = "Fetch one bookmark",
        tags = ["bookmarks"],
        parameters = [
            Parameter(
                name = "bookmarkId",
                `in` = ParameterIn.PATH,
                required = true,
                description = "The bookmark's id",
                schema = Schema(implementation = Long::class),
            ),
        ],
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The bookmark",
                content = [Content(schema = Schema(implementation = Bookmark::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "No bookmark with that id",
                content = [Content(schema = Schema(implementation = NoSuchBookmark::class))],
            ),
        ],
    )
    fun getBookmark(): Route =
        path(longSegment()) { id ->
            get {
                val found = Bookmarks.find(id)
                if (found != null) complete(StatusCodes.OK, found, Jackson.marshaller())
                else complete(
                    StatusCodes.NOT_FOUND,
                    NoSuchBookmark(id, "No bookmark $id"),
                    Jackson.marshaller(),
                )
            }
        }

    @GET
    @Operation(
        summary = "List bookmarks, newest first",
        tags = ["bookmarks"],
        parameters = [
            Parameter(
                name = "limit",
                `in` = ParameterIn.QUERY,
                required = false,
                description = "How many to return",
                schema = Schema(
                    implementation = Int::class,
                    minimum = "1",
                    maximum = "100",
                    defaultValue = "20",
                ),
            ),
            Parameter(
                name = "tag",
                `in` = ParameterIn.QUERY,
                required = false,
                description = "Only bookmarks with this tag",
                schema = Schema(
                    implementation = String::class,
                    pattern = "[a-z0-9-]{1,40}",
                    example = "streams",
                ),
            ),
        ],
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The bookmarks",
                content = [Content(array = ArraySchema(schema = Schema(implementation = Bookmark::class)))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "A parameter was outside its range or pattern",
                content = [Content(schema = Schema(implementation = ApiError::class))],
            ),
        ],
    )
    fun listBookmarks(): Route =
        pathEndOrSingleSlash {
            get {
                // Optional means java.util.Optional here, and "absent" and
                // "present but not a number" are the same value until you take
                // them apart. The default, the range and the pattern are all
                // enforced below and *described* in the annotation above;
                // nothing checks that the two agree.
                parameterOptional("limit") { limitParam ->
                    parameterOptional("tag") { tagParam ->
                        val limit = limitParam.orElse("20").toIntOrNull()
                        val tag = tagParam.orElse(null)

                        when {
                            limit == null || limit !in 1..100 -> complete(
                                StatusCodes.BAD_REQUEST,
                                ApiError(400, "limit must be a value between 1 and 100"),
                                Jackson.marshaller(),
                            )

                            tag != null && !slug.matches(tag) -> complete(
                                StatusCodes.BAD_REQUEST,
                                ApiError(400, "tag must be a slug: lowercase letters, digits and dashes"),
                                Jackson.marshaller(),
                            )

                            else -> completeOKWithSource(
                                Source.from(Bookmarks.list(limit, tag)),
                                Jackson.marshaller(),
                                EntityStreamingSupport.json(),
                            )
                        }
                    }
                }
            }
        }
}

fun main() {
    val system = ActorSystem.create("bookmarks")
    val routes = BookmarkRoutes()

    // The document is another route, served by swagger-pekko-http's
    // `SwaggerHttpService` configured with `apiClasses = setOf(BookmarkRoutes::class.java)`.
    // That list is maintained by hand: add a route and forget to add it here
    // and the endpoint is simply undocumented. Nothing fails.
    val all = Directives.concat(
        pathPrefix("bookmarks") { Directives.concat(routes.getBookmark(), routes.listBookmarks()) },
        apiDocsRoute,
    )

    Http.get(system).newServerAt("localhost", 8080).bind(all)
}
```

The same two endpoints with Pelican:

```kotlin
data class Bookmark(val id: Long, val url: String, val title: String, val tags: List<String> = emptyList())
data class NoSuchBookmark(val id: Long, val message: String)

@JvmInline value class Slug(val value: String)

val slug = StringCodec
    .matching(Regex("[a-z0-9-]{1,40}"), "a slug: lowercase letters, digits and dashes")
    .map(::Slug, Slug::value)
    .describedAs("A URL-safe tag", example = "streams")

val bookmarkId = pathParam<Long>("bookmarkId", description = "The bookmark's id")
val limit      = queryParam("limit", IntCodec.between(1, 100), description = "How many to return").default(20)
val tag        = queryParam("tag", slug, description = "Only bookmarks with this tag").optional()

val bookmarkMissing = errorJson<NoSuchBookmark>(404, "No bookmark with that id")

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
    jsonArray<Bookmark>()                                      // chunked `[{...},{...}]`, flushed as produced
}

val routes = listOf(
    getBookmark handledOrFail { id ->                          // id: Long
        Bookmarks.find(id)?.let { ok(it) }
            ?: bookmarkMissing(NoSuchBookmark(id, "No bookmark $id"))
    },

    listBookmarks streamedNow { (max, tag) ->                  // max: Int, tag: Slug?
        Source.from(Bookmarks.list(max, tag))
    },
)

fun main() {
    val api = api(routes, codecs = JacksonCodecs) {
        title = "Bookmarks"
        version = "1.0.0"
    }
    api.startWithDocs(port = 8080, docs = docs { docsPath = "/api-docs" })
}
```

### What the second version does not have to keep in step

The length is the least of it. What the first version costs is the number of
places that say the same thing and are never compared:

**The path is written twice.** `@Path("/bookmarks")` plus `@Path("/{bookmarkId}")`
for the scanner, `pathPrefix("bookmarks")` plus `path(longSegment())` for the
server. Change one and the other keeps its old answer — which is exactly the
drift a document is supposed to protect you from. In the second version there is
one `get("bookmarks" / bookmarkId)`, and both the route and the document are
read from it.

**The parameter's name exists only in the annotation.** `longSegment()` is
positional; nothing connects it to the string `"bookmarkId"`. The document can
name a parameter the server has never heard of and no build fails.

**The type is declared twice, in two languages.** `Schema(implementation = Long::class)`
in the annotation, `longSegment()` in the matcher, and the handler's `id` gets
its type from the second. `pathParam<Long>("bookmarkId")` is all three at once.

**The constraints are decoration.** `minimum = "1"`, `maximum = "100"` and
`pattern = "[a-z0-9-]{1,40}"` do nothing at runtime. The `when` block underneath
is what actually refuses a bad request, and the two are related only by whoever
last edited both. Get it wrong in one direction and Swagger UI refuses a call
the server would have served; get it wrong in the other and it sends `limit=5000`
to a service that will run the query. `IntCodec.between(1, 100)` is the check
*and* the schema, so the same regex that validates a request is the `pattern` a
caller is shown.

**The default is written twice.** `orElse("20")` in the route and
`defaultValue = "20"` in the annotation, with nothing to notice when one of them
becomes 50. `.default(20)` is one value, read by the decoder and by the document.

**Optionality is three separate facts.** `required = false` in the annotation, a
`java.util.Optional` in the route, and a nullable variable after `orElse(null)` —
and "absent" and "present but malformed" arrive as the same `Optional` until you
take them apart by hand. `.optional()` makes the handler's argument a `Slug?`
and the document's parameter `required: false`, from one call.

**The streamed response is described twice.** Pekko does the framing well —
`completeOKWithSource` with `EntityStreamingSupport.json()` is the whole of it —
but the *shape* of what comes out is then restated as an `ArraySchema` in the
annotation, and a handler that starts streaming something else stays green.
`jsonArray<Bookmark>()` is the framing and the schema, and the binder will not
accept a `Source` of anything but `Bookmark`.

**The 404 is a status in one place and a payload in another.** The annotation
promises a `NoSuchBookmark`; the route is free to complete with anything at all,
including a bare string or a 500. `orFail bookmarkMissing` puts the failure in
the endpoint's type, so a handler that does not produce it does not compile —
and the generated client gets a sealed type to match on.

**Two 404s that mean different things.** `/bookmarks/abc` does not match
`longSegment()`, so the route is rejected and the caller is told 404 — the same
answer as a bookmark that does not exist. Pelican decodes against the declared
codec and answers 400, because "not a number" and "no such bookmark" are not the
same problem.

**The scanner's class list.** One more hand-maintained list, silent when wrong.
The second version has one list — the routes you pass to `Api` — and it is the
list both the server and the document are built from.

None of this is an argument against Pekko HTTP. Pelican runs on it, and the
route the interpreter builds is the route above with the repetition removed
rather than replaced. It is an argument about how many copies of one endpoint a
service keeps, and which of them a compiler is allowed to check.

The Pekko block is illustrative and not part of the build, unlike
[`ReadmeExample.kt`](../example/src/main/kotlin/example/readme/ReadmeExample.kt).
The Pelican half is that file, two endpoints of it.
