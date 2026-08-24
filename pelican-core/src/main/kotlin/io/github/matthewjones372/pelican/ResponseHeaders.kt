package io.github.matthewjones372.pelican

/**
 * A header the endpoint promises to send back, declared the same way an input
 * is: once, as a value, reused by the document and by the handler that sets it.
 *
 * ```
 * val location = responseHeader<String>("Location", "Where the new order lives")
 *
 * val placeOrder = endpoint(newOrder) {
 *     post("orders")
 *     emits(location)
 *     json<Order>(status = 201)
 * }
 *
 * placeOrder handledNow { req ->
 *     val order = Store.create(req)
 *     setHeader(location, "/orders/${order.id}")   // `this` is the request's Params
 *     order
 * }
 * ```
 *
 * Setting one the endpoint never declared throws, so the document and the wire
 * cannot disagree about which headers exist — the same bargain [ParamKey]
 * makes on the way in.
 *
 * The same value declares a header on a *declared failure*, where it belongs
 * to that one response rather than to the endpoint and travels with the
 * failure the handler returns; see [ErrorOutput.invoke].
 */
class ResponseHeader<T> @PublishedApi internal constructor(
    val name: String,
    val codec: PlainCodec<*>,
    val description: String? = null,
    /** False for a header sent only sometimes — `Retry-After`, a paging cursor. */
    val required: Boolean = true,
) {
    override fun toString() = "responseHeader:$name"
}

inline fun <reified T : Any> responseHeader(
    name: String,
    description: String? = null,
): ResponseHeader<T> = ResponseHeader(name, plainCodecFor<T>(), description)

fun <T : Any> responseHeader(
    name: String,
    codec: PlainCodec<T>,
    description: String? = null,
): ResponseHeader<T> = ResponseHeader(name, codec, description)

/**
 * Marks the header as one the endpoint may leave off. It is still declared, so
 * it may still be set; the document simply stops promising it is always there.
 */
fun <T : Any> ResponseHeader<T>.optional(): ResponseHeader<T> =
    ResponseHeader(name, codec, description, required = false)
