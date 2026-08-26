package io.github.matthewjones372.pelican.importer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

class ImportTest {

    private val bookmarks = imported(File("src/test/resources/bookmarks.yaml"), importOptions("app", "bookmarks"))

    @Test
    fun `an input is declared once, as a value, and named after itself`() {
        bookmarks shouldContain """val bookmarkId = pathParam<Long>("bookmarkId")"""
    }

    @Test
    fun `a constraint becomes a refinement, which enforces it as well as documenting it`() {
        bookmarks shouldContain """queryParam("limit", IntCodec.atLeast(1).atMost(100), "How many to return")""" +
            """.default(20)"""
        bookmarks shouldContain """val tag = queryParam("tag", StringCodec.minLength(1)).optional()"""
    }

    @Test
    fun `a route with a capture is built from the input that decodes it`() {
        bookmarks shouldContain """get("bookmarks" / bookmarkId)"""
        bookmarks shouldContain """get("/bookmarks")"""
    }

    @Test
    fun `a documented failure with a payload becomes one the handler has to return`() {
        bookmarks shouldContain """val problemNotFound = errorJson<Problem>(404, "No bookmark with that id")"""
        bookmarks shouldContain "json<Bookmark>() orFail problemNotFound"
    }

    @Test
    fun `a documented failure without a payload is documented and nothing more`() {
        bookmarks shouldContain """errorResponse(429, "Slow down", retryAfter)"""
        bookmarks shouldNotContain "errorJson<Any"
    }

    @Test
    fun `a failure carrying both a body and a header is one declared failure`() {
        val throttling = imported(
            document(
                """
                /orders:
                  get:
                    operationId: listOrders
                    responses:
                      "200":
                        description: ok
                        content:
                          application/json:
                            schema: { type: object, properties: { id: { type: integer } } }
                      "429":
                        description: Slow down
                        headers:
                          Retry-After:
                            required: true
                            schema: { type: integer, format: int64 }
                        content:
                          application/json:
                            schema: { type: object, properties: { message: { type: string } } }
                """,
            ),
        )

        throttling shouldContain """val retryAfter = responseHeader<Long>("Retry-After")"""
        throttling shouldContain """errorJson<ListOrdersFailure>(429, "Slow down", retryAfter)"""
        throttling shouldContain "orFail listOrdersFailureTooManyRequests"
    }

    @Test
    fun `a default response is documentation, payload and all, and no handler returns it`() {
        val yaml = document(
            """
            /orders:
              get:
                operationId: listOrders
                responses:
                  "200":
                    description: ok
                    content:
                      application/json:
                        schema: { type: object, properties: { id: { type: integer } } }
                  default:
                    description: Any other failure
                    content:
                      application/json:
                        schema: { type: object, properties: { message: { type: string } } }
            """,
        )
        val options = importOptions("app", "orders") { handlers = Backend.PEKKO }
        val generated = Import.kotlin(documentOf("openapi.yaml" to yaml), options)

        val endpoints = generated.getValue("OrdersEndpoints.kt")
        endpoints shouldContain """defaultJson<ListOrdersFailure>("Any other failure")"""
        endpoints shouldContain "data class ListOrdersFailure("
        endpoints shouldNotContain "orFail"

        generated.getValue("OrdersHandlers.kt") shouldContain
            """listOrders handledNow { TODO("listOrders") }"""
    }

    @Test
    fun `two successful responses become two declared responses, each with its own headers`() {
        val submitting = imported(
            document(
                """
                /orders:
                  post:
                    operationId: submitOrder
                    responses:
                      "201":
                        description: Placed
                        headers:
                          Location:
                            required: true
                            schema: { type: string }
                        content:
                          application/json:
                            schema: { type: object, properties: { id: { type: integer } } }
                      "202":
                        description: Queued
                        content:
                          application/json:
                            schema: { type: object, properties: { ticket: { type: string } } }
                      "401":
                        description: Bad API key
                        content:
                          application/json:
                            schema: { type: object, properties: { message: { type: string } } }
                """,
            ),
        )

        submitting shouldContain """val location = responseHeader<String>("Location")"""
        submitting shouldContain "json<SubmitOrderResponse>(status = 201, location) or " +
            "json<SubmitOrderResponse2>(status = 202) orFail submitOrderFailureUnauthorized"
        // The endpoint's own list stays empty: neither header is a promise the
        // whole operation makes.
        submitting shouldNotContain "emits("
    }

    @Test
    fun `a response header is a value the handler may set`() {
        bookmarks shouldContain """val location = responseHeader<String>("Location", "Where the new bookmark lives")"""
        bookmarks shouldContain """val retryAfter = responseHeader<Int>("Retry-After").optional()"""
        bookmarks shouldContain "emits(location)"
    }

    @Test
    fun `an enum keeps the constants the wire uses, in backticks where it has to`() {
        bookmarks shouldContain "enum class BookmarkKind { link, note, `in-progress` }"
    }

    @Test
    fun `nullability and optionality are both read, and they are not the same thing`() {
        // `title` is nullable and not required; `tags` is required and not
        // nullable. A property that may be left out gets a default so it can
        // be, which is the only way Kotlin has to say it.
        bookmarks shouldContain "val title: String? = null"
        bookmarks shouldContain "val tags: List<String>,"
    }

    @Test
    fun `a scheme becomes a value, and an endpoint that opts out says so`() {
        bookmarks shouldContain """apiKeyHeader("X-Api-Key", name = "apiKey", """ +
            """description = "The key you were issued.")"""
        bookmarks shouldContain "security = listOf(apiKey.requires())"
        bookmarks shouldContain "noSecurity()"
    }

    @Test
    fun `a templated server url is read as the defaults it declares`() {
        bookmarks shouldContain """servers = listOf("https://bookmarks.example.com/v1")"""
    }

    @Test
    fun `an operation served from somewhere else says where`() {
        val elsewhere = imported(
            """
            openapi: 3.1.0
            info: { title: Test, version: "1.0.0" }
            servers:
              - url: https://orders.example.com
            paths:
              /orders/import:
                post:
                  operationId: importOrders
                  servers:
                    - url: https://uploads.{region}.example.com
                      variables:
                        region: { default: eu }
                  responses:
                    "204": { description: ok }
            """,
        )

        elsewhere shouldContain """servers("https://uploads.eu.example.com")"""
        elsewhere shouldContain """servers = listOf("https://orders.example.com")"""
    }

    @Test
    fun `the description a document gives an operation becomes the KDoc above it`() {
        bookmarks shouldContain "The id is the one the create call handed back."
    }

    @Test
    fun `the document's own schemas come with it, so no codec is needed to publish them`() {
        bookmarks shouldContain "object BookmarksSchemas : SchemaSource {"
        // The names the document used, and the Kotlin types they became.
        bookmarks shouldContain """\"names\":{\"Bookmark\":\"Bookmark\""""
        // And the no-argument spec is what a Gradle task can call.
        bookmarks shouldContain "fun bookmarksSpec(): ApiSpec = bookmarksSpec(BookmarksSchemas)"
        bookmarks shouldContain
            "fun bookmarksSpec(schemas: SchemaSource): ApiSpec = apiSpec(bookmarksEndpoints, schemas) {"
    }

    @Test
    fun `a schema the document wrote inline is carried under the name it was given`() {
        // No `components` entry to look up, so the schema source needs the
        // schema itself — under `GetJobResponse`, which is what the type
        // generator called the class it hoisted out of the response.
        val generated = imported(
            document(
                """
                /jobs:
                  get:
                    operationId: getJob
                    responses:
                      "200":
                        description: ok
                        content:
                          application/json:
                            schema:
                              type: object
                              required: [state]
                              properties:
                                state: { type: string }
                """,
            ),
        )
        generated shouldContain "json<GetJobResponse>()"
        generated shouldContain """\"inline\":{\"GetJobResponse\""""
    }

    @Test
    fun `past six inputs the lens form takes over, and the inputs move into the block`() {
        val declared = (1..7).joinToString("\n") { "      - { name: q$it, in: query, schema: { type: string } }" }
        val generated = imported(
            document(
                "/search:\n  get:\n    operationId: search\n    parameters:\n" + declared +
                    "\n    responses:\n      \"204\": { description: ok }",
            ),
        )
        // `endpoint(a..f)` is the largest tuple overload core has, so seven
        // inputs are declared inside the block and read from the bag.
        generated shouldContain "val search = endpoint {"
        generated shouldContain "query(q1, q2, q3, q4, q5, q6, q7)"
    }

    @Test
    fun `a method with no builder of its own is routed by name`() {
        val generated = imported(
            document(
                """
                /health:
                  head:
                    operationId: pingHealth
                    responses:
                      "204": { description: ok }
                """,
            ),
        )
        generated shouldContain """route(Method.HEAD, path("/health"))"""
    }

    // -------------------------------------------------- more than one value

    private val lists = imported(
        document(
            """
            /orders:
              get:
                operationId: listOrders
                parameters:
                  - name: tag
                    in: query
                    schema: { type: array, items: { type: string } }
                  - name: ids
                    in: query
                    explode: false
                    schema: { type: array, items: { type: integer, minimum: 1 } }
                  - name: fields
                    in: query
                    style: spaceDelimited
                    schema: { type: array, items: { type: string } }
                  - name: sort
                    in: query
                    style: pipeDelimited
                    schema: { type: array, default: ["id"], items: { type: string } }
                  - name: X-Feature
                    in: header
                    required: true
                    schema: { type: array, items: { type: string, example: beta } }
                  - name: seen
                    in: cookie
                    schema: { type: array, items: { type: string } }
                responses:
                  "204": { description: ok }
            """,
        ),
    )

    @Test
    fun `a repeated query parameter is the default encoding, and reads as one`() {
        lists shouldContain """val tag = queryParam<String>("tag").repeated().optional()"""
    }

    @Test
    fun `explode false is the comma, and a constraint on the element still refines it`() {
        lists shouldContain """val ids = queryParam("ids", IntCodec.atLeast(1)).commaSeparated().optional()"""
    }

    @Test
    fun `the delimited styles keep their separators`() {
        lists shouldContain """val fields = queryParam<String>("fields").spaceSeparated().optional()"""
        lists shouldContain """val sort = queryParam<String>("sort").pipeSeparated().default(listOf("id"))"""
    }

    @Test
    fun `a header list is comma-separated, and a required one says so by having no modifier`() {
        lists shouldContain """val xFeature = headerParam("X-Feature", """ +
            """StringCodec.describedAs(example = "beta")).commaSeparated()"""
    }

    @Test
    fun `a cookie list is several pairs, which is the only encoding a cookie has`() {
        lists shouldContain """val seen = cookieParam<String>("seen").repeated().optional()"""
    }

    @Test
    fun `handler stubs are generated only when a backend is named`() {
        val document = File("src/test/resources/bookmarks.yaml")
        Import.kotlin(document, importOptions("app", "bookmarks")).keys shouldContain "BookmarksEndpoints.kt"

        val withStubs = Import.kotlin(document, importOptions("app", "bookmarks") { handlers = Backend.PEKKO })
        withStubs.keys shouldContain "BookmarksHandlers.kt"
        withStubs.getValue("BookmarksHandlers.kt") shouldContain "import io.github.matthewjones372.pelican.pekko.*"
        withStubs.getValue("BookmarksHandlers.kt") shouldContain
            """getBookmark handledOrFail { bookmarkId -> TODO("getBookmark") }"""
    }

    @ParameterizedTest
    @EnumSource(Backend::class, names = ["HTTP4K", "KTOR"])
    fun `a backend this release does not ship is refused rather than generated for`(backend: Backend) {
        val document = File("src/test/resources/bookmarks.yaml")

        val refusal = shouldThrow<ImportFailure> {
            Import.kotlin(document, importOptions("app", "bookmarks") { handlers = backend })
        }.message.orEmpty()

        refusal shouldContain "Handler stubs for $backend"
        refusal shouldContain "which this release does not ship"
        refusal shouldContain "lives on the multi-backend branch"
        refusal shouldContain "Generate for PEKKO"
    }
}
