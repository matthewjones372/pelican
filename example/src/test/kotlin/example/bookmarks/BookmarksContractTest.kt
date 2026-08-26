package example.bookmarks

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.start
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.pekko.client
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.shouldBeApiError
import io.github.matthewjones372.pelican.test.shouldBeError
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.pelican.test.shouldBuild
import io.github.matthewjones372.pelican.test.shouldHaveContentType
import io.github.matthewjones372.pelican.test.shouldHaveNoBody
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BookmarksContractTest {

    protected lateinit var app: ApiClient

    protected abstract fun open(): ApiClient
    protected open fun shutDown() = app.close()

    @BeforeAll fun setUp() { app = open() }

    @AfterAll fun tearDown() = shutDown()

    private val key = "let-me-in"

    private fun save(
        url: String = "https://example.com",
        title: String = "Example",
        tags: List<String> = emptyList(),
    ): Bookmark = app.call(createBookmark, In2(key, CreateBookmark(url, title, tags)))

    // -------------------------------------------------------- the wire contract
    //
    // Everything below names an endpoint value and never a URL, which is what
    // keeps it honest about behaviour: rename `bookmarkId` and those tests stop
    // compiling instead of starting to 404.
    //
    // It is also why not one of them would notice `"bookmarks"` becoming
    // `"books"`. The client builds its request from the same description the
    // server routes on, so a rename moves both ends at once and the suite stays
    // green while every caller already deployed against the old path gets a
    // 404. The URL and the parameter names are the contract those callers hold,
    // so they are pinned here, once, against literals — the only place in this
    // file that deliberately repeats what a description says.

    @Test
    fun `the endpoints are served at the URLs their callers were given`() {
        app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"
        app.request(listBookmarks, In2(20, Slug("streams"))) shouldBuild "GET /bookmarks?limit=20&tag=streams"
        app.request(deleteBookmark, In2(1L, key)) shouldBuild "DELETE /bookmarks/1"

        val created = CreateBookmark("https://example.com", "Example", emptyList())
        app.request(createBookmark, In2(key, created)) shouldBuild "POST /bookmarks"
    }

    // ---------------------------------------------------------------- reads

    @Test
    fun `fetches one bookmark, typed`() {
        app.call(getBookmark, 1L) shouldBe Bookmark(1, "https://kotlinlang.org", "Kotlin", listOf("lang"))
    }

    @Test
    fun `an unknown id is the declared 404`() {
        app.response(getBookmark, 9_999L) shouldHaveStatus 404
    }

    @Test
    fun `the error body is the type the endpoint declared`() {
        // Not a string to grep and not the framework's generic ApiError: the
        // 404 carries NoSuchBookmark because that is what `orFail` declared,
        // and the same declaration is what the handler had to return.
        app.outcome(getBookmark, 9_999L) shouldBeError NoSuchBookmark(9_999L, "No bookmark 9999")

        app.outcome(getBookmark, 9_999L).shouldBeError()
            .shouldBeInstanceOf<NoSuchBookmark>()
            .id shouldBe 9_999L

        app.response(getBookmark, 9_999L) shouldHaveContentType "application/json"
    }

    @Test
    fun `the declared failure is documented with its own schema`() {
        val responses = Json.parseToJsonElement(bookmarksSpec().openApiJson())
            .jsonObject["paths"]!!.jsonObject["/bookmarks/{bookmarkId}"]!!
            .jsonObject["get"]!!.jsonObject["responses"]!!.jsonObject

        responses["404"]!!.jsonObject["content"]!!.jsonObject["application/json"]!!
            .jsonObject["schema"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content shouldBe
            "#/components/schemas/NoSuchBookmark"
    }

    @Test
    fun `an undecodable path parameter is a 400, not a 500`() {
        // `bookmarkId` is a Long, so the typed form cannot produce this: build
        // the request from the description, then corrupt the one segment.
        val response = app.transport.send(app.request(getBookmark, 1L).withPath("/bookmarks/not-a-number"))

        response shouldHaveStatus 400
        response.body shouldContain "Invalid parameter"
    }

    @Test
    fun `an unknown path is a 404`() {
        app.transport.send(app.request(getBookmark, 1L).withPath("/nope")) shouldHaveStatus 404
    }

    // ------------------------------------------------------------- listing

    @Test
    fun `lists bookmarks as a json array`() {
        app.collect(listBookmarks, In2(20, null)).map { it.id } shouldContainAll listOf(1L, 2L)
        app.response(listBookmarks, In2(20, null)) shouldHaveContentType "application/json"
    }

    @Test
    fun `the array response is one well-formed array, not one document per line`() {
        val body = app.response(listBookmarks, In2(2, null)).body
        body shouldStartWith "["
        body shouldEndWith "]"
    }

    @Test
    fun `filters by tag`() {
        val tagged = app.collect(listBookmarks, In2(20, Slug("streams")))

        tagged.shouldHaveSize(1)
        tagged.single().title shouldBe "Pekko"
    }

    @Test
    fun `an unmatched tag yields an empty array`() {
        app.collect(listBookmarks, In2(20, Slug("no-such-tag"))).shouldBeEmpty()
    }

    // ------------------------------------------------- refined parameters

    @Test
    fun `a value outside the declared range is a 400, not a query the server runs`() {
        // `limit` is IntCodec.between(1, 100): the type says Int, the codec says
        // which ints. The typed client can still build the call, so this is the
        // server's own check.
        val tooSmall = app.response(listBookmarks, In2(0, null))
        tooSmall shouldHaveStatus 400
        tooSmall.body shouldContain "Invalid parameter"
        tooSmall.body shouldContain "a value between 1 and 100"

        app.response(listBookmarks, In2(101, null)) shouldHaveStatus 400
    }

    @Test
    fun `a value that breaks the pattern is a 400`() {
        // Slug's constructor cannot vouch for its contents; the codec does, on
        // the way in, for every request whatever built it.
        val response = app.response(listBookmarks, In2(20, Slug("NOT A SLUG")))
        response shouldHaveStatus 400
        response.body shouldContain "a slug: lowercase letters, digits and dashes"
    }

    @Test
    fun `the document states the constraints the server enforces`() {
        val params = Json.parseToJsonElement(bookmarksSpec().openApiJson())
            .jsonObject["paths"]!!.jsonObject["/bookmarks"]!!
            .jsonObject["get"]!!.jsonObject["parameters"]!!.jsonArray
            .associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }

        val limitSchema = params.getValue("limit").jsonObject.getValue("schema").jsonObject
        limitSchema["minimum"]!!.jsonPrimitive.content shouldBe "1"
        limitSchema["maximum"]!!.jsonPrimitive.content shouldBe "100"

        val tagParam = params.getValue("tag").jsonObject
        tagParam["schema"]!!.jsonObject["pattern"]!!.jsonPrimitive.content shouldBe "[a-z0-9-]{1,40}"
        tagParam["example"]!!.jsonPrimitive.content shouldBe "streams"
    }

    @Test
    fun `a codec that documents itself describes every parameter built from it`() {
        val header = Json.parseToJsonElement(bookmarksSpec().openApiJson())
            .jsonObject["paths"]!!.jsonObject["/bookmarks"]!!
            .jsonObject["post"]!!.jsonObject["parameters"]!!.jsonArray
            .single { it.jsonObject["name"]!!.jsonPrimitive.content == "X-Api-Key" }
            .jsonObject

        // headerParam("X-Api-Key", ...) passes no description of its own.
        header["description"]!!.jsonPrimitive.content shouldBe "The caller's API key"
        header["example"]!!.jsonPrimitive.content shouldBe "let-me-in"
        header["schema"]!!.jsonObject["minLength"]!!.jsonPrimitive.content shouldBe "1"
    }

    @Test
    fun `limit is honoured`() {
        app.collect(listBookmarks, In2(1, null)) shouldHaveSize 1
    }

    @Test
    fun `the server applies its own default when limit is omitted`() {
        // The tuple form always supplies `limit: Int` — there is no way to say
        // "absent" — so drop it to reach the default the endpoint declares.
        val request = app.request(listBookmarks, In2(1, null)).withoutQuery("limit")
        val returned = app.transport.send(request).body.count { it == '{' }

        returned shouldBeGreaterThan 1 // limit.default(20), and the fixtures are under it
    }

    // -------------------------------------------------------------- writes

    @Test
    fun `creates a bookmark and returns the declared 201`() {
        val created = save(url = "https://pelican.dev", title = "Pelican", tags = listOf("docs"))

        created.id shouldBeGreaterThan 0L
        created.url shouldBe "https://pelican.dev"
        created.tags shouldBe listOf("docs")

        app.response(createBookmark, In2(key, CreateBookmark("https://x.dev", "X"))) shouldHaveStatus 201
    }

    @Test
    fun `a created bookmark is readable back through the other endpoint`() {
        val created = save(url = "https://round.trip", title = "Round trip")
        app.call(getBookmark, created.id) shouldBe created
    }

    @Test
    fun `a body field's default is supplied by the codec`() {
        // `tags` has a Kotlin default, which is why the spec leaves it out of
        // `required` — the server must accept a body without it.
        save(title = "No tags").tags.shouldBeEmpty()
    }

    @Test
    fun `a bad api key is the declared 401`() {
        val response = app.response(createBookmark, In2("wrong", CreateBookmark("https://nope.dev", "Nope")))
        app.shouldBeApiError(response, 401, "Bad API key")
    }

    @Test
    fun `a missing api key is a 400 before the handler runs`() {
        val request = app.request(createBookmark, In2(key, CreateBookmark("https://nope.dev", "Nope")))
        val response = app.transport.send(request.withoutHeader("X-Api-Key"))

        response shouldHaveStatus 400
        response.body shouldContain "X-Api-Key"
    }

    @Test
    fun `a malformed body is a 400`() {
        val request = app.request(createBookmark, In2(key, CreateBookmark("https://nope.dev", "Nope")))
        val response = app.transport.send(request.withBody("""{"url":}"""))

        response shouldHaveStatus 400
        response.body shouldContain "Malformed request body"
    }

    // ------------------------------------------------------------- deletes

    @Test
    fun `deletes a bookmark, 204 with no body`() {
        val doomed = save(title = "Doomed")

        val response = app.response(deleteBookmark, In2(doomed.id, key))
        response shouldHaveStatus 204
        response.shouldHaveNoBody()

        app.response(getBookmark, doomed.id) shouldHaveStatus 404
    }

    @Test
    fun `deleting with a bad key is 401 and leaves the bookmark alone`() {
        val survivor = save(title = "Survivor")

        app.response(deleteBookmark, In2(survivor.id, "wrong")) shouldHaveStatus 401
        app.call(getBookmark, survivor.id) shouldBe survivor
    }
}

/** No socket, no port: straight through the interpreted route. */
class InMemoryBookmarksTest : BookmarksContractTest() {
    override fun open(): ApiClient = bookmarksApi().inMemory("bookmarks-in-memory")
}

/** The same assertions, over a real connection to a bound server. */
class OverHttpBookmarksTest : BookmarksContractTest() {
    private lateinit var server: PelicanServer

    override fun open(): ApiClient {
        server = bookmarksApi().start(port = 0, systemName = "bookmarks-over-http")
        return server.client()
    }

    override fun shutDown() {
        app.close()
        server.stop()
    }
}
