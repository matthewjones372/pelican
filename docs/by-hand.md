# The same endpoints, by hand

Linked from the [README](../README.md). Two endpoints written twice, once with
Pelican and once directly against Pekko HTTP with swagger-core annotations, so
the differences can be read in code rather than taken on trust. Both versions
run on Pekko HTTP, which is the backend in the 1.0.0 release.

The endpoints:

- `GET /bookmarks/{bookmarkId}` answers with a `Bookmark`, or a 404 carrying a
  `NoSuchBookmark`.
- `GET /bookmarks` takes a `limit` that defaults to 20 and must be between 1
  and 100, and an optional `tag` that must be a slug, and streams the matches
  as a JSON array.

Both appear in an OpenAPI document served by the same process.

## Contents

- [By hand: Pekko HTTP and swagger-core](#by-hand-pekko-http-and-swagger-core)
- [With Pelican, on Pekko HTTP](#with-pelican-on-pekko-http)
- [What has to be kept in step](#what-has-to-be-kept-in-step)
- [What each version produces](#what-each-version-produces)
- [Where the hand-written version is better](#where-the-hand-written-version-is-better)
- [Size](#size)
- [Provenance of the two blocks](#provenance-of-the-two-blocks)

## By hand: Pekko HTTP and swagger-core

The routes use Pekko HTTP's Java DSL, which is the one a Kotlin service calls.
The document comes from swagger-core's JAX-RS reader (`swagger-jaxrs2`, the
same reader `swagger-pekko-http` wraps), which scans the `@Path`, `@GET` and
`@Operation` annotations at startup. The JAX-RS annotations are there only for
the scanner; they route nothing.

```kotlin
// dependencies {
//     implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
//     implementation("org.apache.pekko:pekko-actor-typed_2.13")
//     implementation("org.apache.pekko:pekko-stream_2.13")
//     implementation("org.apache.pekko:pekko-http_2.13:1.4.1")
//     implementation("org.apache.pekko:pekko-http-jackson_2.13:1.4.1")
//     implementation("io.swagger.core.v3:swagger-jaxrs2-jakarta:2.2.55")
//     implementation("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")
// }

import io.swagger.v3.core.util.Json
import io.swagger.v3.jaxrs2.Reader
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.integration.SwaggerConfiguration
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.common.EntityStreamingSupport
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson
import org.apache.pekko.http.javadsl.model.ContentTypes
import org.apache.pekko.http.javadsl.model.HttpEntities
import org.apache.pekko.http.javadsl.model.StatusCodes
import org.apache.pekko.http.javadsl.server.AllDirectives
import org.apache.pekko.http.javadsl.server.PathMatchers.longSegment
import org.apache.pekko.http.javadsl.server.Route
import org.apache.pekko.stream.javadsl.Source

data class Bookmark(val id: Long, val url: String, val title: String, val tags: List<String> = emptyList())
data class NoSuchBookmark(val id: Long, val message: String)
data class ApiError(val status: Int, val error: String, val detail: String)

private val slug = Regex("[a-z0-9-]{1,40}")

@Path("/bookmarks")
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
                else complete(StatusCodes.NOT_FOUND, NoSuchBookmark(id, "No bookmark $id"), Jackson.marshaller())
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
                description = "How many to return",
                schema = Schema(implementation = Int::class, minimum = "1", maximum = "100", defaultValue = "20"),
            ),
            Parameter(
                name = "tag",
                `in` = ParameterIn.QUERY,
                description = "Only bookmarks with this tag",
                schema = Schema(implementation = String::class, pattern = "[a-z0-9-]{1,40}", example = "streams"),
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
                parameterOptional("limit") { limitParam ->
                    parameterOptional("tag") { tagParam ->
                        val limit = limitParam.map { it.toIntOrNull() ?: -1 }.orElse(20)
                        val tag = tagParam.orElse(null)
                        when {
                            limit !in 1..100 -> badRequest("limit must be a number between 1 and 100")
                            tag != null && !slug.matches(tag) ->
                                badRequest("tag must be a slug: lowercase letters, digits and dashes")
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

    private fun badRequest(detail: String): Route =
        complete(StatusCodes.BAD_REQUEST, ApiError(400, "Bad request", detail), Jackson.marshaller())
}

// Read once at startup. Every class that carries routes has to be listed here.
private val openApiJson: String = Json.pretty(
    Reader(SwaggerConfiguration().openAPI(OpenAPI().info(Info().title("Bookmarks").version("1.0.0"))))
        .read(setOf(BookmarkRoutes::class.java)),
)

fun main() {
    val system = ActorSystem.create("bookmarks")
    val routes = BookmarkRoutes()
    val all = routes.concat(
        routes.pathPrefix("bookmarks") { routes.concat(routes.getBookmark(), routes.listBookmarks()) },
        routes.path("openapi.json") {
            routes.get { routes.complete(HttpEntities.create(ContentTypes.APPLICATION_JSON, openApiJson)) }
        },
    )
    Http.get(system).newServerAt("localhost", 8080).bind(all)
}
```

`Bookmarks` is the same in-memory store as in
[`ReadmeExample.kt`](../example/src/main/kotlin/example/readme/ReadmeExample.kt),
with `list` taking a `String?` tag here instead of a `Slug?`. Swagger UI is not
served in this version; adding it is another route over a Swagger UI bundle
(for example from a webjar), or `swagger-pekko-http`'s `SwaggerHttpService`
in place of the `Reader` call.

## With Pelican, on Pekko HTTP

```kotlin
// dependencies {
//     implementation("io.github.matthewjones372:pelican-pekko:1.0.0")
//     implementation("io.github.matthewjones372:pelican-jackson:1.0.0")
//     implementation("io.github.matthewjones372:pelican-pekko-docs:1.0.0")
//     // Pekko is compileOnly in Pelican, so the service names its own
//     implementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
//     implementation("org.apache.pekko:pekko-actor-typed_2.13")
//     implementation("org.apache.pekko:pekko-stream_2.13")
//     implementation("org.apache.pekko:pekko-http_2.13:1.4.1")
// }

import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.between
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.describedAs
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.map
import io.github.matthewjones372.pelican.matching
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.pekko.streamedNow
import io.github.matthewjones372.pelican.queryParam
import org.apache.pekko.stream.javadsl.Source

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

This serves the routes, the document at `/openapi.json` and Swagger UI at
`/api-docs`. To mount the same endpoints beside routes a service already has,
`api.toRoute(system)` returns a Pekko `Route` to `concat` with them; see
[The backend](reference.md#the-backend).

## What has to be kept in step

In the hand-written version, each fact about an endpoint is written in more
than one place, and nothing compares the copies. In the Pelican version each
fact is written once and read by both the route and the document.

| Fact | By hand | With Pelican |
|---|---|---|
| Path | `@Path("/bookmarks")` + `@Path("/{bookmarkId}")` for the scanner; `pathPrefix("bookmarks")` + `path(longSegment())` for the server | `get("bookmarks" / bookmarkId)` |
| Path parameter's name | Only in the `@Parameter` annotation; `longSegment()` is positional | `pathParam<Long>("bookmarkId")` |
| Parameter types | `Schema(implementation = Long::class)` in the annotation; `longSegment()` and `toIntOrNull()` in the route | The codec on the input |
| `limit` range, `tag` pattern | `minimum`, `maximum`, `pattern` strings in the annotation; the `when` block in the route | `IntCodec.between(1, 100)`, `StringCodec.matching(...)`: the same value validates and documents |
| `limit` default | `defaultValue = "20"` and `orElse(20)` | `.default(20)` |
| Optionality | Omitted `required` in the annotation; `parameterOptional` and `orElse(null)` in the route | `.optional()`, which also makes the handler's argument `Slug?` |
| Streamed response shape | `ArraySchema(...)` in the annotation; whatever `Source` the route completes with | `jsonArray<Bookmark>()`; `streamedNow` requires a `Source<Bookmark, NotUsed>` |
| The 404 payload | `NoSuchBookmark` in the annotation; the route can complete with anything | `orFail bookmarkMissing` is in the endpoint's type, so the handler must return `Outcome<NoSuchBookmark, Bookmark>` or it does not compile |
| Which endpoints are documented | The `setOf(BookmarkRoutes::class.java)` passed to the reader | The list passed to `api(...)` serves and documents the same endpoints |

Every one of those copies can drift without a build failure. Changing the
range in the `when` block but not in the annotation, for example, leaves
Swagger UI and generated clients describing a contract the server no longer
enforces.

There are also behavioural differences that follow from how each version is
built:

- **A malformed path parameter.** `/bookmarks/abc` does not match
  `longSegment()`, so the hand-written route rejects it and Pekko answers 404,
  the same as a missing bookmark. Pelican decodes the capture against its
  codec and answers 400 on both backends (`AllBackendsTest`, "a path parameter
  that does not decode is a 400").
- **Error bodies.** The hand-written `ApiError` is written only where the
  route writes it. Pelican writes every refusal (bad parameter, unsupported
  `Accept`, body too large) in one envelope, `{"status", "error", "detail"}` by
  default, and documents it as each operation's `default` response; see
  [Errors, and what a caller is told](reference.md#errors-and-what-a-caller-is-told).
- **Document version.** swagger-core writes OpenAPI 3.0 unless configured for
  3.1. Pelican writes 3.1.0 by default and 3.2.0 on request; it does not write
  3.0. See
  [Which version the document says](reference.md#which-version-the-document-says-and-how-to-choose).

## What each version produces

From the hand-written version: the routes and an OpenAPI document. A client or
contract tests come from separate tools reading that document (for example
`openapi-generator`), and they are only as accurate as the annotations.

From the Pelican version, the same endpoint values also give:

- a typed test client in `pelican-test` (`app.request(getBookmark, 1L)`), and
  an in-memory Pekko transport in `pelican-test-pekko`;
- a generated Kotlin client from the Gradle plugin, with a sealed failure type
  for the 404; see [The generated client](generated-client.md);
- golden files that fail the build when the wire contract changes in a way that
  breaks existing callers; see [Golden files](golden-testing.md);
- optionally, MCP tools over the same endpoints; see [MCP](mcp.md).

## Where the hand-written version is better

- **No new library or DSL.** Everyone who knows Pekko HTTP can read and change
  it. The Pelican version needs the team to learn Pelican's inputs, outputs and
  binders.
- **Full control of the route.** Any directive, any rejection handling, any
  response. Pelican covers what its descriptions can say; past that the escape
  hatch is `params.request` (Pekko's `HttpRequest`) or an ordinary Pekko route
  mounted beside `toRoute(system)`.
- **No interpreter.** On Pekko, with the handler on the dispatcher, Pelican
  measured a few hundred nanoseconds and under a kilobyte per request behind
  the fastest hand-written route, and faster than an idiomatic one. Running
  synchronous handlers on a virtual thread, its default on JDK 24 and newer,
  costs considerably more and can be turned off with `Handlers.onDispatcher`. See
  [What it costs](what-it-costs.md).
- **No runtime dependency beyond Pekko and swagger-core.** Pelican adds
  `pelican-core` (which depends on `jackson-core`), its Jackson module and the
  docs module.

## Size

Counting the two code blocks above, excluding blank lines, comment lines and
imports: the hand-written version is 111 lines and the Pelican version 39.
Neither count includes the shared `Bookmarks` store. Most of the difference is the `@Operation` annotations,
which restate what the routes already say. Line count is the smaller
difference; the table above is the larger one.

## Provenance of the two blocks

The Pelican block is the `getBookmark` and `listBookmarks` half of
[`ReadmeExample.kt`](../example/src/main/kotlin/example/readme/ReadmeExample.kt),
which CI compiles, with the binder and docs imports moved from `pelican-jetty`
to `pelican-pekko` and the stream returned as a Pekko `Source` instead of a
Kotlin `Sequence`. Every binder has the same name on both backends; only the
stream type differs. The fuller service is
[`example/bookmarks/Bookmarks.kt`](../example/src/main/kotlin/example/bookmarks/Bookmarks.kt).

The hand-written block is not part of the build. It was written against Pekko
HTTP's Java DSL and swagger-core 2.2 and has not been compiled; treat it as an
illustration of the approach rather than a tested sample.
