package example

/*
 * Plain data classes. Nothing here is annotated for a particular JSON
 * library: Jackson reads them through jackson-module-kotlin, and swagger-core
 * describes them by reading the same metadata Jackson does.
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
}
