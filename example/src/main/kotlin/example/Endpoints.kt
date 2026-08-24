package example

import dev.pelican.*

/*
 * This file imports dev.pelican only. No Pekko, no HTTP library, no OpenAPI.
 * It is the single source of truth that both the server interpreter and the
 * documentation generator read.
 */

// --------------------------------------------------------------- parameters

val userId = pathParam<Long>("userId", description = "The user's id")
val orderId = pathParam<Long>("orderId")

// Refinements are enforced before a handler runs and documented in the schema,
// so `?limit=0` is a 400 that the document already predicted.
val limit = queryParam("limit", IntCodec.between(1, 100), description = "Maximum rows to stream").default(25)
val statusFilter = queryParam<OrderStatus>("status", description = "Only this status").optional()
val traceId = headerParam<String>("X-Trace-Id", description = "Correlation id").optional()
val apiKey = headerParam(
    "X-Api-Key",
    // The codec documents the type once, wherever it is used.
    StringCodec.nonEmpty().describedAs("Required credential", example = "let-me-in"),
)

/*
 * Inputs that carry more than one value, one per encoding OpenAPI can
 * describe. The modifier says how the values are told apart on the wire; what
 * one of them decodes to is still the codec's business, so `item` is refined
 * exactly as a single-valued parameter would be.
 *
 * All of them are optional, and an absent one arrives as `null` rather than as
 * an empty list: `?item=` carries no element, so a list is never empty on the
 * wire, and reading absence as empty would leave "matched nothing" and "did
 * not filter" spelled the same way.
 */
val itemFilter = queryParam("item", StringCodec.nonEmpty(), description = "Only these items")
    .commaSeparated()
    .optional()
val tagFilter = queryParam<String>("tag", description = "Only orders carrying these tags").repeated().optional()
val sortBy = queryParam<String>("sort", description = "Sort keys, most significant first").pipeSeparated().optional()
val fieldMask = queryParam<String>("fields", description = "Return only these fields").spaceSeparated().optional()
val features = headerParam<String>("X-Feature", description = "Feature flags the caller has on")
    .commaSeparated()
    .optional()
val seenOrders = cookieParam<Long>("seen", description = "Orders this browser has already been shown")
    .repeated()
    .optional()

val newOrder = jsonBody<CreateOrder>(description = "The order to place")
val rawUpload = rawBody(description = "Anything; it is never buffered")

// A cookie is an ordinary input, not a credential: this one is a session the
// caller may or may not have, and reading it is the same three lines a header
// takes.
val session = cookieParam<String>("session", description = "An opaque session id").optional()

// A form, for the caller that posts one rather than JSON. The schema published
// for CreateOrder is what says `quantity=2` is a number.
val orderForm = formBody<CreateOrder>(description = "The order to place, as a form posts it")

// A multipart upload: one text field and one file, where the file is handed to
// the handler as a stream rather than read into memory.
val importLabel = textPart("label", StringCodec.nonEmpty(), description = "What to call this import")
val importFile = filePart("file", contentType = "text/csv", description = "One order per line")

// ----------------------------------------------------------- declared failures
//
// Named, so a handler can return one. An endpoint that lists a failure with
// `orFail` has it in its type: the handler must answer with `ok(...)` or with
// one of these, and returning an error nobody declared does not compile.

val noSuchUser = errorJson<ApiError>(404, "No user with that id")
val badApiKey = errorJson<ApiError>(401, "Missing or bad API key")

// --------------------------------------------------------------- endpoints
//
// endpoint(...) declares the input list once. It registers each parameter for
// decoding and documentation, *and* fixes the handler's signature — so a
// handler cannot read something the endpoint never declared, and cannot
// mistake its type.
//
// operationId names the operation for every interpreter at once: it is the
// `operationId` in the OpenAPI document and the method name in the generated
// TypeScript client. Leave it out and both fall back to the same name derived
// from the method and the path.

val getUser = endpoint(userId) {
    get("users" / userId)
    summary = "Fetch a single user"
    operationId = "getUser"
    tag("users")
    json<User>() orFail noSuchUser
}
// handler: (Long) -> Outcome<ApiError, User>

val streamOrders = endpoint(userId, limit, statusFilter, traceId) {
    get("users" / userId / "orders")
    summary = "Stream a user's orders as newline-delimited JSON"
    operationId = "streamOrders"
    description = "Rows are flushed as they are produced; nothing is collected in memory."
    tag("orders")
    ndjson<Order>() orFail noSuchUser
}
// handler: (In4<Long, Int, OrderStatus?, String?>) -> Outcome<ApiError, stream of Order>

val watchOrders = endpoint(userId, limit) {
    get("users" / userId / "orders" / "watch")
    summary = "Live order events over server-sent events"
    operationId = "watchOrders"
    tag("orders")
    sse<Tick>(eventName = "order")
}

/**
 * The same rows as [streamOrders], framed as one JSON array rather than one
 * document per line. Pekko supplies the framing; the description does not care
 * how the commas get there.
 */
val listOrders = endpoint(userId, limit) {
    get("users" / userId / "orders" / "list")
    summary = "Stream a user's orders as a chunked JSON array"
    operationId = "listOrders"
    description = "Elements are flushed as they are produced; the array is never assembled in memory."
    tag("orders")
    jsonArray<Order>()
}

val placeOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders")
    summary = "Place an order"
    operationId = "placeOrder"
    tag("orders")
    // Two failures, same payload type, different statuses. The handler names
    // the one it is returning, so the status comes from the declaration.
    json<Order>(status = 201).orFail(badApiKey, noSuchUser)
}

val cancelOrder = endpoint(userId, orderId, apiKey) {
    delete("users" / userId / "orders" / orderId)
    summary = "Cancel an order"
    operationId = "cancelOrder"
    tag("orders")
    empty(status = 204)
}

val echo = endpoint(rawUpload) {
    post("echo")
    summary = "Echo the request body back as it arrives"
    operationId = "echo"
    tag("diagnostics")
    bytes()
}

val placeOrderForm = endpoint(userId, orderForm) {
    post("users" / userId / "orders" / "form")
    summary = "Place an order from an HTML form"
    operationId = "placeOrderForm"
    tag("orders")
    json<Order>(status = 201)
}

val importOrders = endpoint(userId, session, importLabel, importFile) {
    post("users" / userId / "orders" / "import")
    summary = "Import orders from an uploaded file"
    operationId = "importOrders"
    tag("orders")
    json<ImportResult>(status = 201)
}

/**
 * The lens style, for when there are more inputs than a tuple can carry
 * comfortably. Inputs are declared with query()/header() and read by key. The
 * trade: reading an undeclared key is a runtime error rather than a compile
 * error, so prefer the typed form until it stops scaling.
 */
val searchOrders = endpoint {
    get("search")
    summary = "Lens-style: the handler reads inputs from the Params bag"
    operationId = "searchOrders"
    tag("diagnostics")
    query(limit, statusFilter, itemFilter, tagFilter, sortBy, fieldMask)
    header(traceId, features)
    cookie(seenOrders)
    ndjson<Order>()
}

/**
 * Routed, served, and absent from the published document. `hidden` hides the
 * description; it is not a lock — this endpoint still checks the API key like
 * any other, because a path nobody wrote down is not a path nobody can guess.
 */
val reindex = endpoint(apiKey) {
    post("internal" / "reindex")
    hidden = true
    summary = "Rebuild the order index"
    operationId = "reindex"
    tag("diagnostics")
    empty(status = 202)
}

/** Everything above, in one list, so the server and the docs cannot drift. */
val allEndpoints: List<Endpoint<*, *>> = listOf(
    getUser, streamOrders, watchOrders, listOrders, placeOrder, placeOrderForm, importOrders,
    cancelOrder, echo, searchOrders, reindex,
)
