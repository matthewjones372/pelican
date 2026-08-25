package example.shop

import java.util.concurrent.atomic.AtomicLong

/*
 * The fake database, behind the interfaces in Shop.kt.
 *
 * It is here rather than in that file so the shape of the argument stays
 * visible there: descriptions, failures, handlers. Nothing below is interesting
 * except that it never throws — every refusal is a `Till.Refused` carrying the
 * branch of `ShopError` that says which one, which is what lets the HTTP layer
 * have three statuses instead of one catch-all.
 */

class InMemoryCatalog : BookCatalog {

    private val shelf = listOf(
        Book("the-waves", "The Waves", "Virginia Woolf", 1931, Genre.FICTION, 899, staffPick = true),
        Book("gilead", "Gilead", "Marilynne Robinson", 2004, Genre.FICTION, 999, staffPick = false),
        Book("the-rings-of-saturn", "The Rings of Saturn", "W. G. Sebald", 1995, Genre.FICTION, 1099, true),
        Book("essays-in-idleness", "Essays in Idleness", "Yoshida Kenkō", 1332, Genre.ESSAYS, 799, false),
        Book("the-white-album", "The White Album", "Joan Didion", 1979, Genre.ESSAYS, 949, true),
        Book("the-sea-around-us", "The Sea Around Us", "Rachel Carson", 1951, Genre.SCIENCE, 899, false),
        Book("the-double-helix", "The Double Helix", "James Watson", 1968, Genre.SCIENCE, 849, false),
        Book("the-daughter-of-time", "The Daughter of Time", "Josephine Tey", 1951, Genre.CRIME, 749, true),
        Book("the-long-goodbye", "The Long Goodbye", "Raymond Chandler", 1953, Genre.CRIME, 799, false),
        Book("north", "North", "Seamus Heaney", 1975, Genre.POETRY, 699, false),
        Book("the-waste-land", "The Waste Land", "T. S. Eliot", 1922, Genre.POETRY, 649, true),
        Book("citizens", "Citizens", "Simon Schama", 1989, Genre.HISTORY, 1299, false),
        Book("the-face-of-battle", "The Face of Battle", "John Keegan", 1976, Genre.HISTORY, 999, false),
    )

    private val byId = shelf.associateBy { it.id }

    override fun list(q: String?, genre: Genre?, limit: Int): List<Book> =
        shelf.asSequence()
            .filter { genre == null || it.genre == genre }
            .filter { q == null || it.matches(q) }
            .take(limit)
            .toList()

    override fun get(id: String): Book? = byId[id]

    private fun Book.matches(q: String) =
        title.contains(q, ignoreCase = true) || author.contains(q, ignoreCase = true)
}

class InMemoryOrderDesk(private val catalog: BookCatalog) : OrderDesk {

    private val nextOrder = AtomicLong(1004)

    override fun quote(basket: Basket): Till<Receipt> = price(basket.items)

    override fun place(request: PlaceOrder): Till<Order> {
        if (!deliverable(request.email)) {
            return Till.Refused(
                ShopError.BadEmail(request.email, "We could not send a receipt to ${request.email}"),
            )
        }
        return when (val priced = price(request.items)) {
            is Till.Refused -> priced

            is Till.Answered -> Till.Answered(
                Order(
                    id = "RB-${nextOrder.getAndIncrement()}",
                    customerName = request.customerName,
                    email = request.email,
                    note = request.note,
                    receipt = priced.value,
                ),
            )
        }
    }

    /**
     * The till, and the reason `/cart/quote` is an endpoint rather than a sum
     * the page could do for itself: the offer lives here, so the shelf price
     * and the price paid are allowed to differ, and only one of them is ever
     * authoritative.
     */
    private fun price(items: List<CartLine>): Till<Receipt> {
        if (items.isEmpty() || items.all { it.quantity <= 0 }) {
            return Till.Refused(ShopError.EmptyBasket("There is nothing in the basket to price"))
        }

        val lines = mutableListOf<ReceiptLine>()
        for (line in items.filter { it.quantity > 0 }) {
            val book = catalog.get(line.bookId)
                ?: return Till.Refused(
                    ShopError.UnknownBook(line.bookId, "No book ${line.bookId} on the shelf"),
                )
            lines += ReceiptLine(
                bookId = book.id,
                title = book.title,
                quantity = line.quantity,
                unitPence = book.pricePence,
                linePence = book.pricePence * line.quantity,
            )
        }

        val subtotal = lines.sumOf { it.linePence }
        val copies = lines.sumOf { it.quantity }
        val offered = copies >= BULK_COPIES
        val discount = if (offered) subtotal * BULK_PERCENT / PERCENT else 0

        return Till.Answered(
            Receipt(
                lines = lines,
                subtotalPence = subtotal,
                discountPence = discount,
                totalPence = subtotal - discount,
                offerLabel = if (offered) "Three or more books: $BULK_PERCENT% off" else null,
            ),
        )
    }

    /**
     * Deliberately shallow. What makes an address deliverable is a question for
     * the thing that sends the mail; what matters here is that the answer comes
     * back as a value, so the handler has a 422 to name rather than a throw to
     * catch.
     */
    private fun deliverable(email: String): Boolean {
        val at = email.indexOf('@')
        return at > 0 && email.indexOf('.', at) > at + 1 && !email.endsWith(".")
    }

    private companion object {
        const val BULK_COPIES = 3
        const val BULK_PERCENT = 10
        const val PERCENT = 100
    }
}
