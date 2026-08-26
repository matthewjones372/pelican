package example.shop

import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.shouldBeFailure
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The bookshop, and chiefly the thing it exists to demonstrate: three domain
 * failures reaching a caller as three different responses.
 *
 * The assertions below are about *which declared failure* came back, not about
 * a status code copied into the test — `shouldBeFailure(badEmail)` names the
 * same value the endpoint declared, so a 422 quietly becoming a 400 fails here
 * and renaming the declaration fails at compile time.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopContractTest {

    private lateinit var app: ApiClient

    @BeforeAll fun setUp() { app = shopApi().inMemory("pelican-shop-test") }

    @AfterAll fun tearDown() = app.close()

    private fun basketOf(vararg lines: Pair<String, Int>) =
        Basket(lines.map { (id, qty) -> CartLine(id, qty) })

    // ------------------------------------------------------------- the shelf

    @Test
    fun `the shelf can be filtered by genre, as a genre`() {
        val crime = app.call(listBooks, In3(null, Genre.CRIME, 24))

        crime.map { it.id } shouldContainExactly listOf("the-daughter-of-time", "the-long-goodbye")
        crime.forEach { it.genre shouldBe Genre.CRIME }
    }

    @Test
    fun `a genre nobody stocks is refused before the handler runs`() {
        // The typed call cannot express this: `genre` is a `Genre?`, so there
        // is no `BANANA` to pass. Reaching the refusal means building the
        // request by hand, which is the whole evidence that the codec — and not
        // a line in the handler — is what refuses it.
        val refused = app.transport.send(
            RequestSpec(Method.GET, "/books", listOf("genre" to "BANANA"), emptyList(), null),
        )

        refused shouldHaveStatus 400
        refused.body shouldContain "genre"
    }

    @Test
    fun `a genre is matched however it was typed, and published as it was written`() {
        val lowercase = app.transport.send(
            RequestSpec(Method.GET, "/books", listOf("genre" to "crime"), emptyList(), null),
        )

        lowercase shouldHaveStatus 200
        lowercase.body shouldContain "the-long-goodbye"
    }

    @Test
    fun `a title search reads title and author alike`() {
        app.call(listBooks, In3("didion", null, 24)) shouldHaveSize 1
        app.call(listBooks, In3("waves", null, 24)).single().id shouldBe "the-waves"
    }

    @Test
    fun `a book that is not on the shelf is a 404 naming the id asked for`() {
        val missing = app.outcome(getBook, "the-pelican-brief").shouldBeFailure(unknownBook)

        missing.bookId shouldBe "the-pelican-brief"
        app.response(getBook, "the-pelican-brief") shouldHaveStatus 404
    }

    // -------------------------------------------------------------- the till

    @Test
    fun `an empty basket is a 400 and says so as an empty basket`() {
        app.outcome(quoteBasket, Basket(emptyList())).shouldBeFailure(emptyBasket)
        app.response(quoteBasket, Basket(emptyList())) shouldHaveStatus 400
    }

    @Test
    fun `a basket naming a book nobody stocks is a 404, not the same 400`() {
        val basket = basketOf("gilead" to 1, "the-pelican-brief" to 1)

        app.outcome(quoteBasket, basket)
            .shouldBeFailure(unknownBook)
            .shouldBeInstanceOf<ShopError.UnknownBook>()
            .bookId shouldBe "the-pelican-brief"
        app.response(quoteBasket, basket) shouldHaveStatus 404
    }

    @Test
    fun `the till applies the offer the shelf does not`() {
        val receipt = app.outcome(quoteBasket, basketOf("gilead" to 3)).shouldBeOk()

        receipt.subtotalPence shouldBe 2997
        receipt.discountPence shouldBe 299
        receipt.totalPence shouldBe 2698
        receipt.offerLabel shouldBe "Three or more books: 10% off"
    }

    @Test
    fun `two copies is under the offer, and priced at the shelf`() {
        val receipt = app.outcome(quoteBasket, basketOf("gilead" to 2)).shouldBeOk()

        receipt.totalPence shouldBe receipt.subtotalPence
        receipt.offerLabel shouldBe null
    }

    // ------------------------------------------------------------ the order

    @Test
    fun `an undeliverable address is a 422 — well-formed, and still not placeable`() {
        val order = PlaceOrder("Ada", "ada@example", items = listOf(CartLine("gilead", 1)))

        app.outcome(placeOrder, order)
            .shouldBeFailure(badEmail)
            .shouldBeInstanceOf<ShopError.BadEmail>()
            .email shouldBe "ada@example"
        app.response(placeOrder, order) shouldHaveStatus 422
    }

    @Test
    fun `the same three failures are told apart on the way to placing an order`() {
        val who = "Ada" to "ada@example.com"

        app.outcome(placeOrder, PlaceOrder(who.first, who.second, items = emptyList()))
            .shouldBeFailure(emptyBasket)

        app.outcome(placeOrder, PlaceOrder(who.first, who.second, items = listOf(CartLine("dune", 1))))
            .shouldBeFailure(unknownBook)

        app.outcome(placeOrder, PlaceOrder(who.first, "ada@example", items = listOf(CartLine("gilead", 1))))
            .shouldBeFailure(badEmail)
    }

    @Test
    fun `a placed order carries the receipt it was priced with`() {
        val request = PlaceOrder(
            customerName = "Ada Lovelace",
            email = "ada@example.com",
            note = "Leave with the neighbour",
            items = listOf(CartLine("gilead", 2), CartLine("north", 1)),
        )

        val order = app.outcome(placeOrder, request).shouldBeOk()

        order.id shouldContain "RB-"
        order.note shouldBe "Leave with the neighbour"
        order.receipt.lines shouldHaveSize 2
        order.receipt.totalPence shouldBe order.receipt.subtotalPence - order.receipt.discountPence
        app.response(placeOrder, request) shouldHaveStatus 201
    }

    // ---------------------------------------------------------- the document
    //
    // The point of the whole example: what a caller reading the published
    // document is told about those three failures.

    @Test
    fun `the document publishes three distinct failures for placing an order`() {
        val responses = Json.parseToJsonElement(shopSpec().openApiJson())
            .jsonObject["paths"]!!.jsonObject["/orders"]!!
            .jsonObject["post"]!!.jsonObject["responses"]!!.jsonObject

        // `default` is not a fourth failure: it is the refusal envelope, which
        // this operation answers with and does not declare.
        responses.keys shouldContainExactly setOf("201", "400", "404", "422", "default")

        // Three statuses is only half of it: they carry different payloads too,
        // so a caller can read which book was missing or which address was
        // refused rather than parsing one `message` string.
        val schemas = listOf("400", "404", "422").map { status ->
            responses[status]!!.jsonObject["content"]!!
                .jsonObject["application/json"]!!.jsonObject["schema"]!!
                .jsonObject["\$ref"]!!.jsonPrimitive.content
        }
        schemas.toSet() shouldHaveSize 3
        schemas[1] shouldContain "UnknownBook"
    }

    @Test
    fun `a whole list publishes what a streamed array publishes`() {
        val schema = Json.parseToJsonElement(shopSpec().openApiJson())
            .jsonObject["paths"]!!.jsonObject["/books"]!!
            .jsonObject["get"]!!.jsonObject["responses"]!!
            .jsonObject["200"]!!.jsonObject["content"]!!
            .jsonObject["application/json"]!!.jsonObject["schema"]!!.jsonObject

        // `json<List<Book>>()` here; `jsonArray<Book>()` would publish this
        // same object — see `/users/{userId}/orders/list` in the Orders golden
        // document. The choice between them is about memory and latency, and a
        // caller reading the document cannot tell which was made.
        schema["type"]!!.jsonPrimitive.content shouldBe "array"
        schema["items"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content shouldBe "#/components/schemas/Book"
    }

    @Test
    fun `the document tells a caller the six genres rather than making them guess`() {
        val parameters = Json.parseToJsonElement(shopSpec().openApiJson())
            .jsonObject["paths"]!!.jsonObject["/books"]!!
            .jsonObject["get"]!!.jsonObject["parameters"]!!.jsonArray

        val genre = parameters.single { it.jsonObject["name"]!!.jsonPrimitive.content == "genre" }
        val values = genre.jsonObject["schema"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }

        values shouldContainExactly Genre.entries.map { it.name }
    }
}
