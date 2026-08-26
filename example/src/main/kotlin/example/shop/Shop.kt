package example.shop

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.pekko.*
import io.github.matthewjones372.pelican.pekko.docs.Docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs

/*
 * A bookshop: a shelf to browse, a till that prices a basket, and an order.
 *
 * It exists for one thing the other examples do not show — a service whose
 * *domain* can fail in more than one way, and a document that says so. The
 * shape it is arguing against is the one everybody writes first:
 *
 *     placeOrder handledOrFail { req ->
 *         runCatching { ok(desk.place(req)) }.getOrElse { badOrder(Problem(...)) }
 *     }
 *
 * That compiles, serves, and publishes a single 400 for an empty basket, an id
 * nobody stocks, and an undeliverable email alike. The three failures a
 * bookshop actually has become one shrug, and a caller reading the document
 * cannot tell which of them happened or which are worth retrying.
 *
 * The fix is not a library feature. It is refusing to have two error models:
 * `ShopError` below is the till's vocabulary *and* the payload type of three
 * declared responses, so `declared()` is a `when` the compiler completes, and
 * an exception is never in hand to be flattened.
 */

// ============================================================ 1. the models

enum class Genre { FICTION, ESSAYS, SCIENCE, CRIME, POETRY, HISTORY }

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val year: Int,
    val genre: Genre,
    val pricePence: Int,
    val staffPick: Boolean,
)

data class CartLine(val bookId: String, val quantity: Int)

data class Basket(val items: List<CartLine>)

data class PlaceOrder(
    val customerName: String,
    val email: String,
    val note: String? = null,
    val items: List<CartLine>,
)

data class ReceiptLine(
    val bookId: String,
    val title: String,
    val quantity: Int,
    val unitPence: Int,
    val linePence: Int,
)

data class Receipt(
    val lines: List<ReceiptLine>,
    val subtotalPence: Int,
    val discountPence: Int,
    val totalPence: Int,
    val offerLabel: String?,
)

data class Order(
    val id: String,
    val customerName: String,
    val email: String,
    val note: String?,
    val receipt: Receipt,
)

// =========================================================== 2. the failures
//
// One hierarchy, used twice: the till returns these, and three declared
// responses carry them. Nothing translates between the two readings, because
// there is only one value.
//
// Each branch carries what a caller would need to act — which book was not
// stocked, which address was refused — rather than a `code` string that means
// the same thing to nobody in particular.

sealed interface ShopError {
    /** Nothing to price. A basket is a customer's mistake, not the shop's. */
    data class EmptyBasket(val message: String) : ShopError

    /** An id off the shelf, or off a bookmark from last year. */
    data class UnknownBook(val bookId: String, val message: String) : ShopError

    /** The receipt has nowhere to go. */
    data class BadEmail(val email: String, val message: String) : ShopError
}

/**
 * What the till answers with: the thing that was asked for, or why not.
 *
 * Four lines rather than a dependency, and the point of them is that
 * [OrderDesk] never throws. A domain that signals by exception hands the HTTP
 * layer a `Throwable` and no list of what it might be, and the only honest
 * thing to write there is a catch-all — which is how three failures become one
 * status. A domain that *returns* its failures hands over a sealed type, and
 * the `when` over it is checked.
 */
sealed interface Till<out T> {
    data class Answered<T>(val value: T) : Till<T>
    data class Refused(val error: ShopError) : Till<Nothing>
}

// ========================================================= 3. the shop, behind interfaces
//
// Neither of these mentions HTTP, and neither imports anything from Pelican
// except `Till`, which is this file's own type. Swap the fakes at the bottom
// for a database and nothing above this line moves.

interface BookCatalog {
    fun list(q: String?, genre: Genre?, limit: Int): List<Book>
    fun get(id: String): Book?
}

interface OrderDesk {
    /** Prices a basket. Offers apply here and never on the shelf. */
    fun quote(basket: Basket): Till<Receipt>
    fun place(request: PlaceOrder): Till<Order>
}

// ============================================================ 4. the inputs

val bookId = pathParam(
    "bookId",
    StringCodec.matching(Regex("[a-z0-9-]{3,80}"), "a book id: lowercase letters, digits and dashes"),
    description = "Shelf id, hyphenated",
)

val titleQuery = queryParam<String>("q", description = "Match on title or author").optional()

/**
 * A genre, not a string.
 *
 * `plainCodecFor<Genre>()` is picked up by the reified overload, and it does
 * two things a `queryParam<String>` cannot: `?genre=BANANA` is a 400 raised
 * before the handler runs, and the published parameter carries
 * `enum: [FICTION, ESSAYS, ...]` — so a caller is told the six words rather
 * than discovering them one refusal at a time. Matching is case-insensitive;
 * what is *documented* is the constant names.
 */
val genreFilter = queryParam<Genre>("genre", description = "Only this genre").optional()

val limit = queryParam("limit", IntCodec.between(1, 100), description = "Page size").default(24)

/**
 * One body value, used by two endpoints — the same reuse `bookId` gets, and
 * for the same reason. A body is an input like any other: declared once, and
 * the schema published under `/cart/quote` is the schema published under
 * `/orders` because it is the same declaration.
 */
val basket = jsonBody<Basket>(description = "What is in the basket")

val newOrder = jsonBody<PlaceOrder>(description = "Who is buying, and what")

// ========================================================= 5. the declared failures
//
// Three responses, three statuses, three payload types — and the payload types
// are the domain's own. `E` infers to their common supertype, `ShopError`, so
// the handler's `when` over a refusal is exhaustive and adding a fourth branch
// to the hierarchy stops this file compiling until it has a status.
//
// The statuses are the argument. 400 says the request was malformed and 404
// says the shelf is the problem; 422 says the request was well-formed and
// still cannot be acted on, which is exactly an address that will not take a
// receipt. One shared 400 would have said none of that.

val emptyBasket = errorJson<ShopError.EmptyBasket>(400, "The basket has nothing in it")
val unknownBook = errorJson<ShopError.UnknownBook>(404, "No book with that id")
val badEmail = errorJson<ShopError.BadEmail>(422, "That address will not take a receipt")

/**
 * The one place a domain failure becomes the response declared for it.
 *
 * This is the whole of the mapping the `runCatching` version was standing in
 * for, and the difference is that this one is checked: it is a `when` over a
 * sealed type with no `else`, so a new `ShopError` is a compile error here
 * rather than a silent fourth thing arriving as somebody's 400.
 */
private fun ShopError.declared(): Outcome<ShopError, Nothing> = when (this) {
    is ShopError.EmptyBasket -> emptyBasket(this)
    is ShopError.UnknownBook -> unknownBook(this)
    is ShopError.BadEmail -> badEmail(this)
}

/** Either the till's answer, or the response declared for its refusal. */
private fun <T> Till<T>.outcome(): Outcome<ShopError, T> = when (this) {
    is Till.Answered -> ok(value)
    is Till.Refused -> error.declared()
}

// ========================================================== 6. the endpoints

/**
 * The whole shelf, as one JSON array.
 *
 * `json<List<Book>>()` and `jsonArray<Book>()` put the same bytes on the wire.
 * The difference is when they are written: this one encodes a list that is
 * already in hand and sends it with a `Content-Length`, and `jsonArray` frames
 * a stream, flushing elements as they are produced. A shelf of sixteen books is
 * the first; a table nobody has counted is the second. Choose by whether the
 * collection is bounded and already loaded, not by which reads better — and
 * note that only the streaming form needs a `Source`, which is why this handler
 * is `handledNow` and `listBookmarks` next door is `streamedNow`.
 */
val listBooks = endpoint(titleQuery, genreFilter, limit) {
    get("books")
    summary = "List books on the shelf"
    operationId = "listBooks"
    tag("catalog")
    json<List<Book>>()
}

val getBook = endpoint(bookId) {
    get("books" / bookId)
    summary = "Fetch one book"
    operationId = "getBook"
    tag("catalog")
    json<Book>() orFail unknownBook
}

/**
 * What the basket costs, which is not the sum of the shelf prices — offers are
 * the till's business, and an endpoint is where a shop says so.
 */
val quoteBasket = endpoint(basket) {
    post("cart" / "quote")
    summary = "Price a basket at the till"
    operationId = "quoteBasket"
    tag("till")
    // Two of the three: a quote never sees an email, so it cannot fail for one.
    // The handler's `when` is over `ShopError` all the same — `declared()` is
    // shared — and this list is what says a 422 is not among this endpoint's
    // answers.
    json<Receipt>().orFail(emptyBasket, unknownBook)
}

val placeOrder = endpoint(newOrder) {
    post("orders")
    summary = "Place an order"
    operationId = "placeOrder"
    tag("till")
    json<Order>(status = 201).orFail(emptyBasket, unknownBook, badEmail)
}

/**
 * The call this shop *sends*, when an order leaves the building.
 *
 * No path, because the path is the subscriber's. Nothing routes it and nothing
 * binds a handler to it; what reads it is the document, under `webhooks`, and
 * the generated client, which grows an `orderDispatched(url, body)` that sends
 * one. Firing it from this process today means holding that generated client —
 * there is no sender in core.
 */
val orderDispatched = webhook("orderDispatched") {
    body(jsonBody<Order>(description = "The order that just left the shop"))
    summary = "Sent to a subscriber when an order is dispatched"
    operationId = "orderDispatched"
    tag("till")
    empty(status = 204)
}

/** Every route this shop answers, in one list. See `covers` below. */
val allShopEndpoints: List<Endpoint<*, *>> = listOf(listBooks, getBook, quoteBasket, placeOrder)

val allShopWebhooks: List<Webhook> = listOf(orderDispatched)

// ============================================================ 7. the server
//
// Five handlers, no `runCatching`, and no `Problem` type invented to hold what
// an exception was carrying. Each one either answers or hands back the till's
// refusal under the status declared for it.

fun shopRoutes(catalog: BookCatalog, desk: OrderDesk): List<ServerEndpoint> = listOf(

    listBooks handledNow { (q, genre, max) ->            // q: String?, genre: Genre?, max: Int
        catalog.list(q, genre, max)
    },

    getBook handledOrFail { id ->                        // id: String
        catalog.get(id)?.let { ok(it) }
            ?: unknownBook(ShopError.UnknownBook(id, "No book $id on the shelf"))
    },

    quoteBasket handledOrFail { contents -> desk.quote(contents).outcome() },

    placeOrder handledOrFail { request -> desk.place(request).outcome() },

)

fun shopApi(
    catalog: BookCatalog = InMemoryCatalog(),
    desk: OrderDesk = InMemoryOrderDesk(catalog),
): Api = api(
    endpoints = shopRoutes(catalog, desk),
    codecs = JacksonCodecs,
) {
    title = "Rookery Books"
    version = "1.0.0"
    description = "An independent bookshop: a shelf, a till, and an order."
    // The list the document is built from, handed to the server as well. An
    // endpoint added to `allShopEndpoints` and forgotten in `shopRoutes` is a
    // startup failure naming it, rather than a route that documents itself and
    // answers 404.
    covers = allShopEndpoints
    webhooks = allShopWebhooks
}

/** The same descriptions, no server and no handlers — what the docs task reads. */
fun shopSpec(): ApiSpec = ApiSpec(
    endpoints = allShopEndpoints,
    schemas = JacksonCodecs,
    title = "Rookery Books",
    version = "1.0.0",
    description = "An independent bookshop: a shelf, a till, and an order.",
    webhooks = allShopWebhooks,
)

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val server = shopApi().startWithDocs(port = port, docs = docs { docsPath = "/api-docs" })
    println("Rookery Books on ${server.baseUrl} — docs at ${server.baseUrl}/api-docs")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    server.block()
}

fun writeShopSpec() = println(shopSpec().openApiJson())

private const val DEFAULT_PORT = 8080
