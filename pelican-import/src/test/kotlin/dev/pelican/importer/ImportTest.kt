package dev.pelican.importer

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What a document becomes, checked as text.
 *
 * Text is all this module can check: whether the result compiles, and whether
 * it describes the same API it came from, is asserted in `:example`, where the
 * document this build generates is imported, compiled, and published again for
 * comparison. See `ImportedOrdersTest`.
 */
class ImportTest {

    private val bookmarks = imported(File("src/test/resources/bookmarks.yaml"), ImportOptions("app", "bookmarks"))

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
        bookmarks shouldContain "fun bookmarksSpec(schemas: SchemaSource): ApiSpec = ApiSpec("
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

    @Test
    fun `handler stubs are generated only when a backend is named`() {
        val document = File("src/test/resources/bookmarks.yaml")
        Import.kotlin(document, ImportOptions("app", "bookmarks")).keys shouldContain "BookmarksEndpoints.kt"

        val withStubs = Import.kotlin(document, ImportOptions("app", "bookmarks", handlers = Backend.KTOR))
        withStubs.keys shouldContain "BookmarksHandlers.kt"
        withStubs.getValue("BookmarksHandlers.kt") shouldContain "import dev.pelican.ktor.*"
        withStubs.getValue("BookmarksHandlers.kt") shouldContain
            """getBookmark handledOrFail { bookmarkId -> TODO("getBookmark") }"""
    }
}
