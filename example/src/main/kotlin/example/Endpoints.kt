package example

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *     }
 */

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.Webhook
import io.github.matthewjones372.pelican.between
import io.github.matthewjones372.pelican.bufferedFile
import io.github.matthewjones372.pelican.commaSeparated
import io.github.matthewjones372.pelican.cookieParam
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.describedAs
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.filePart
import io.github.matthewjones372.pelican.formBody
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.lensInputs
import io.github.matthewjones372.pelican.ndjsonIn
import io.github.matthewjones372.pelican.nonEmpty
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pipeSeparated
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.rawBody
import io.github.matthewjones372.pelican.repeated
import io.github.matthewjones372.pelican.responseHeader
import io.github.matthewjones372.pelican.spaceSeparated
import io.github.matthewjones372.pelican.textPart
import io.github.matthewjones372.pelican.webhook

/*
 * This file imports io.github.matthewjones372.pelican only. No Pekko, no HTTP library, no OpenAPI.
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

/**
 * Inputs that carry more than one value, one per encoding OpenAPI can
 * describe. The modifier says how the values are told apart on the wire; what
 * one of them decodes to is still the codec's business, so `item` is refined
 * exactly as a single-valued parameter would be.
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

// A body that is a discriminated union: three shapes, and a `method` property
// saying which one arrived. The description says nothing about that — the
// hierarchy carries it, and the document is generated from the same
// annotations Jackson decodes with.
val payment = jsonBody<PaymentMethod>(description = "How the order is being paid for")
val rawUpload = rawBody(description = "Anything; it is never buffered")

/**
 * A typed upload: one order per line, decoded and placed as the line arrives.
 * `rawUpload` is the same promise without the types — this one names what a
 * frame is, so the document says it and the codec enforces it.
 */
val orderRows = ndjsonIn<CreateOrder>(description = "One order to place per line")

// A cookie is an ordinary input, not a credential: this one is a session the
// caller may or may not have, and reading it is the same three lines a header
// takes.
val session = cookieParam<String>("session", description = "An opaque session id").optional()

val orderForm = formBody<CreateOrder>(description = "The order to place, as a form or as JSON") or
    jsonBody<CreateOrder>()

val importLabel = textPart("label", StringCodec.nonEmpty(), description = "What to call this import")
val importManifest = bufferedFile(
    "manifest",
    maxBytes = 8 * 1024,
    contentType = "text/csv",
    description = "What the file is supposed to contain",
)
val importFile = filePart("file", contentType = "text/csv", description = "One order per line")

// ----------------------------------------------------------- declared failures
//
// Named, so a handler can return one. An endpoint that lists a failure with
// `orFail` has it in its type: the handler must answer with `ok(...)` or with
// one of these, and returning an error nobody declared does not compile.

val noSuchUser = errorJson<ApiError>(404, "No user with that id")
val badApiKey = errorJson<ApiError>(401, "Missing or bad API key")

/**
 * A failure that carries a header as well as a payload: the body says what
 * happened and the header says when to come back, which is what a 429 nearly
 * always is.
 */
val retryAfter = responseHeader<Long>("Retry-After", description = "Seconds to wait before trying again")

val throttled = errorJson<ApiError>(429, "Too much asked for at once", retryAfter)

/**
 * Where a newly placed order lives — declared on the 201 below and on nothing
 * else, for exactly the reason [retryAfter] is declared on the 429. `emits(...)`
 * would have documented it on the 202 as well, which is a `Location` for an
 * order that does not have one yet.
 */
val orderAt = responseHeader<String>("Location", description = "Where the placed order lives")

/*
 * Two successful answers to one question, declared as values so the handler can
 * name the one it is producing — the same reason the failures above are values.
 * They carry different payload types, so `submitOrder`'s success type is their
 * common supertype; the generated client turns them into a sealed pair and the
 * caller has to say which it is looking at.
 */

/** The order was placed there and then. */
val orderPlaced = json<Order>(status = 201, orderAt)

/** It was taken but not placed; the ticket is how to ask about it. */
val orderQueued = json<Queued>(status = 202)

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
    defaultJson<ApiError>("Any other failure, rendered as an ApiError")
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
    // Every event carries its sequence as the `id:`, so a caller whose
    // connection drops sends the last one back and is answered from there.
    sse<Tick>(eventName = "order", id = { it.seq.toString() })
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
    // Three failures, all carrying ApiError, under three statuses. The handler
    // names the one it is returning, so the status comes from the declaration —
    // and the 429 carries a `Retry-After` the handler has to supply.
    json<Order>(status = 201).orFail(badApiKey, noSuchUser, throttled)
}

/**
 * The same question with two right answers: placed, or taken and queued.
 */
val submitOrder = endpoint(userId, apiKey, newOrder) {
    post("users" / userId / "orders" / "submit")
    summary = "Place an order, or take it for later when it is too large to place now"
    operationId = "submitOrder"
    tag("orders")
    orderPlaced or orderQueued orFail badApiKey
}

/**
 * The union, both ways round: it arrives as the whole request body and comes
 * back nested inside [Receipt]. Two positions in the document, one hierarchy —
 * which is the case a schema generator has to get right in the components
 * rather than at the top of one operation.
 */
val payOrder = endpoint(userId, orderId, apiKey, payment) {
    post("users" / userId / "orders" / orderId / "payment")
    summary = "Pay for an order"
    operationId = "payOrder"
    tag("orders")
    json<Receipt>(status = 201).orFail(badApiKey, noSuchUser)
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

val importOrders = endpoint(userId, session, importLabel, importManifest, importFile) {
    post("users" / userId / "orders" / "import")
    summary = "Import orders from an uploaded file"
    operationId = "importOrders"
    tag("orders")
    json<ImportResult>(status = 201)
}

/**
 * Streamed both ways in one call: the orders arrive a line at a time and the
 * placed ones go back a line at a time, so a caller uploading a hundred
 * thousand rows sees the first receipt without waiting for the last upload.
 * Nothing but the frame being read is held at either end.
 */
val ingestOrders = endpoint(userId, orderRows) {
    post("users" / userId / "orders" / "ingest")
    summary = "Place a run of orders from a streamed upload"
    operationId = "ingestOrders"
    description = "Rows are decoded and placed as they arrive; a row that will not decode is a 400 naming it."
    tag("orders")
    ndjson<Order>()
}

/**
 * The lens style, for when there are more inputs than a tuple can carry
 * comfortably. Inputs are declared with query()/header() and read by key. The
 * trade: reading an undeclared key is a runtime error rather than a compile
 * error, so prefer the typed form until it stops scaling.
 */
val searchOrders = endpoint(lensInputs) {
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
    getUser, streamOrders, watchOrders, listOrders, placeOrder, submitOrder, placeOrderForm, importOrders,
    ingestOrders, payOrder, cancelOrder, echo, searchOrders, reindex,
)

// ----------------------------------------------------------------- webhooks

/** What the receiver checks the body against, so a notification cannot be forged. */
val hookSignature = headerParam(
    "X-Signature",
    StringCodec.nonEmpty().describedAs("HMAC-SHA256 of the body", example = "sha256=abc123"),
)

/** The event body, which is the order as it stood when it was placed. */
val orderPlacedEvent = jsonBody<Order>(description = "The order that was just placed")

/**
 * The one description in this file that is not a route.
 */
val orderPlacedHook = webhook("orderPlaced") {
    body(orderPlacedEvent)
    header(hookSignature)
    summary = "Sent to a subscriber when an order is placed"
    operationId = "orderPlaced"
    tag("orders")
    empty(status = 204)
}

/** The calls this service makes, kept apart from the ones it answers. */
val allWebhooks: List<Webhook> = listOf(orderPlacedHook)
