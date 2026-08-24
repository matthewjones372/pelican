package example

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/*
 * Plain data classes. Nothing here is annotated for a particular JSON library
 * — Jackson reads them through jackson-module-kotlin, and swagger-core
 * describes them by reading the same metadata Jackson does — with one
 * exception, and it is an exception for a reason: `PaymentMethod` below.
 *
 * A sealed hierarchy is the one shape no JSON library can read off the Kotlin.
 * Nothing in `sealed interface PaymentMethod` says which property carries the
 * branch or what string selects each one, so it has to be written down, and
 * it can only be written down in one library's vocabulary. Everything else
 * here stays library-agnostic.
 */

enum class OrderStatus { PENDING, SHIPPED, DELIVERED, CANCELLED }

data class User(val id: Long, val name: String, val email: String)

data class Order(
    val id: Long,
    val userId: Long,
    val item: String,
    val quantity: Int,
    val status: OrderStatus,
)

data class CreateOrder(val item: String, val quantity: Int = 1)

data class Tick(val seq: Int, val label: String)

/**
 * How an order was paid for: one of three shapes, told apart by `method`.
 *
 * The names are the interesting part. `bank_transfer` is what goes on the
 * wire, `BankTransfer` is what the class is called, and the two have no reason
 * to match — a wire format is somebody else's convention. The published
 * document carries both, which is what lets a client generated from it decode
 * a payload this service actually sends; see `ImportedOrdersTest`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "method")
@JsonSubTypes(
    JsonSubTypes.Type(value = Card::class, name = "card"),
    JsonSubTypes.Type(value = BankTransfer::class, name = "bank_transfer"),
    JsonSubTypes.Type(value = Invoice::class, name = "invoice"),
)
sealed interface PaymentMethod

data class Card(val number: String, val expiry: String) : PaymentMethod

data class BankTransfer(val iban: String) : PaymentMethod

data class Invoice(val reference: String) : PaymentMethod

/** What paying produced. The union travels inside a payload here, and as a body of its own. */
data class Receipt(val orderId: Long, val paidWith: PaymentMethod, val total: Double)

data class ImportResult(
    val label: String,
    val filename: String?,
    val lines: Int,
    /** Null when the caller sent no session cookie, which most callers will not. */
    val session: String?,
)

/** Stand-in for a database. */
object Store {
    private val users = listOf(
        User(1, "Ada Lovelace", "ada@example.com"),
        User(2, "Grace Hopper", "grace@example.com"),
    ).associateBy { it.id }

    private var nextOrderId = 100L

    fun user(id: Long): User? = users[id]

    fun orders(userId: Long, limit: Int, status: OrderStatus?): List<Order> =
        (1..200).asSequence()
            .map { i ->
                Order(
                    id = i.toLong(),
                    userId = userId,
                    item = "widget-$i",
                    quantity = (i % 5) + 1,
                    status = OrderStatus.entries[i % OrderStatus.entries.size],
                )
            }
            .filter { status == null || it.status == status }
            .take(limit)
            .toList()

    fun create(userId: Long, req: CreateOrder): Order =
        Order(nextOrderId++, userId, req.item, req.quantity, OrderStatus.PENDING)

    /**
     * What each way of paying costs. The `when` is the reason a union is worth
     * having in Kotlin at all: add a branch to [PaymentMethod] and this stops
     * compiling until it says what the new one charges.
     */
    fun pay(orderId: Long, method: PaymentMethod): Receipt {
        val fee = when (method) {
            is Card -> CARD_FEE
            is BankTransfer -> 0.0
            is Invoice -> INVOICE_FEE
        }
        return Receipt(orderId, method, total = ORDER_TOTAL + fee)
    }

    private const val ORDER_TOTAL = 42.0
    private const val CARD_FEE = 1.5
    private const val INVOICE_FEE = 4.0
}
