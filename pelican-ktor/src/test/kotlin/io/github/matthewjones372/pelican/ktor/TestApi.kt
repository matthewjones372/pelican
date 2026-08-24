package io.github.matthewjones372.pelican.ktor

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/*
 * A small API that exercises every output shape the interpreter has to render,
 * described exactly as any Pelican service describes one: these files import
 * io.github.matthewjones372.pelican only, and the binding below is the single place that knows the
 * backend is Ktor.
 *
 * It is deliberately the same API `pelican-http4k` and `pelican-pekko` test
 * against, down to the paths and the payloads. Three backends answering the
 * same requests identically is the claim; sharing the description is how it is
 * checked.
 */

data class Item(val id: Long, val name: String)

data class NewItem(val name: String, val quantity: Int = 1)

val itemId = pathParam<Long>("itemId")
val limit = queryParam("limit", IntCodec.between(1, 100)).default(3)
val tag = queryParam<String>("tag").optional()
val apiKey = headerParam("X-Api-Key", StringCodec.nonEmpty())
val newItem = jsonBody<NewItem>()
val upload = rawBody()

val noSuchItem = errorJson<ApiError>(404, "No item with that id")
val badApiKey = errorJson<ApiError>(401, "Missing or bad API key")

/** Declared by another endpoint. Returning it here is the bookkeeping failure. */
val notMine = errorJson<ApiError>(409, "Declared somewhere else entirely")

val getItem = endpoint(itemId) {
    get("items" / itemId)
    operationId = "getItem"
    json<Item>() orFail noSuchItem
}

val countItems = endpoint(limit, tag) {
    get("items" / "count")
    operationId = "countItems"
    text()
}

val streamItems = endpoint(limit) {
    get("items" / "stream")
    operationId = "streamItems"
    ndjson<Item>()
}

val watchItems = endpoint(limit) {
    get("items" / "watch")
    operationId = "watchItems"
    sse<Item>(eventName = "item")
}

val listItems = endpoint(limit) {
    get("items" / "list")
    operationId = "listItems"
    jsonArray<Item>()
}

val createItem = endpoint(apiKey, newItem) {
    post("items")
    operationId = "createItem"
    json<Item>(status = 201).orFail(badApiKey, noSuchItem)
}

val deleteItem = endpoint(itemId) {
    delete("items" / itemId)
    operationId = "deleteItem"
    empty(status = 204)
}

val echo = endpoint(upload) {
    post("echo")
    operationId = "echo"
    bytes()
}

val boom = endpoint {
    get("boom")
    operationId = "boom"
    json<Item>()
}

/** Returns a failure it never declared, which is a 500 rather than a 409. */
val misdeclared = endpoint {
    get("misdeclared")
    operationId = "misdeclared"
    json<Item>() orFail noSuchItem
}

fun items(count: Int): Flow<Item> = flow {
    for (i in 1..count) emit(Item(i.toLong(), "item-$i"))
}

fun testApi(extra: List<ServerEndpoint> = emptyList()): Api = Api(
    endpoints = listOf(
        getItem handledOrFail { id ->
            if (id == 1L) ok(Item(1, "widget")) else noSuchItem(ApiError(404, "No item $id"))
        },
        countItems handledNow { (max, tag) -> "$max/${tag ?: "-"}" },
        streamItems streamedNow { max -> items(max) },
        watchItems streamedNow { max -> items(max) },
        listItems streamedNow { max -> items(max) },
        createItem handledOrFail { (key, req) ->
            when {
                key != "let-me-in" -> badApiKey(ApiError(401, "Bad API key"))
                req.name == "nope" -> noSuchItem(ApiError(404, "No such item"))
                else -> ok(Item(7, req.name))
            }
        },
        deleteItem handledWith { id -> if (id != 1L) notFound("No item $id") },
        echo bytesNow { body -> body.toChannel() },
        boom handledNow { _ -> error("handler blew up") },
        misdeclared handledOrFail { _ -> notMine(ApiError(409, "Not this endpoint's failure")) },
    ) + extra,
    codecs = JacksonCodecs,
    title = "Items",
)
