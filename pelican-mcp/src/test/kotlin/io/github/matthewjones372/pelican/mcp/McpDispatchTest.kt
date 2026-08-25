package io.github.matthewjones372.pelican.mcp

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * A model calling the service. The arguments go through the same codecs and
 * refinements an HTTP request would, so a tool cannot accept what the endpoint
 * rejects, and the handler is the one already bound to the route.
 */
class McpDispatchTest {

    data class Order(val id: Long, val item: String)
    data class CreateOrder(val item: String, val quantity: Int = 1)
    data class ApiError(val status: Int, val error: String)

    private val userId = pathParam<Long>("userId")
    private val limit = queryParam("limit", IntCodec.between(1, 100)).default(25)
    private val apiKey = headerParam<String>("X-Api-Key")
    private val newOrder = jsonBody<CreateOrder>()
    private val noSuchUser = errorJson<ApiError>(404, "No user with that id")

    private val getOrders = endpoint(userId, limit) {
        get("users" / userId / "orders")
        operationId = "getOrders"
        json<Order>() orFail noSuchUser
    }

    private val placeOrder = endpoint(userId, apiKey, newOrder) {
        post("users" / userId / "orders")
        operationId = "placeOrder"
        json<Order>(status = 201)
    }

    /** What the handler was actually given, so a test can say the decoding was real. */
    private class Seen {
        var userId: Long? = null
        var limit: Int? = null
        var apiKey: String? = null
        var body: CreateOrder? = null
    }

    /** The credential the service requires, supplied by what serves the tools rather than by the model. */
    private val supplied = McpOptions(headers = mapOf("X-Api-Key" to "let-me-in"))

    private fun api(seen: Seen = Seen(), options: McpOptions = supplied): McpDispatch = api(
        endpoints = listOf(
            ServerEndpoint(getOrders) { p ->
                seen.userId = p[userId]
                seen.limit = p[limit]
                val outcome =
                    if (p[userId] == 0L) Outcome.Err(noSuchUser, ApiError(404, "No user 0"))
                    else ok(Order(1, "a-widget"))
                completed(outcome)
            },
            // `placeOrder` declares one response, so its handler returns the
            // payload — an `Outcome` here would be the wrapper, which is what
            // `handledNow` hands over and what the codec is asked to write.
            ServerEndpoint(placeOrder) { p ->
                seen.apiKey = p[apiKey]
                seen.body = p[newOrder]
                completed(Order(2, p[newOrder].item))
            },
        ),
        codecs = JacksonCodecs,
    ).mcpDispatch(options)

    private fun completed(value: Any?): CompletionStage<Any?> = CompletableFuture.completedStage(value)

    @Test
    fun `valid arguments are decoded to their declared types and reach the handler`() {
        val seen = Seen()
        val result = api(seen).call("getOrders", jsonObj { "userId" to 7; "limit" to 50 }).result()

        seen.userId shouldBe 7L
        seen.limit shouldBe 50
        result.isError.shouldBeFalse()
        result.structuredContent.shouldNotBeNull()["item"] shouldBe JsonStr("a-widget")
        withClue("the same JSON is the text, for a client that reads only content") {
            result.text shouldContain "a-widget"
        }
    }

    @Test
    fun `an argument left out falls back to the default the endpoint declared`() {
        val seen = Seen()
        api(seen).call("getOrders", jsonObj { "userId" to 7 }).result()

        seen.limit shouldBe 25
    }

    @Test
    fun `a declared failure comes back as an error the model can act on`() {
        val result = api().call("getOrders", jsonObj { "userId" to 0 }).result()

        result.isError.shouldBeTrue()
        withClue("the failure's own description is what says why") {
            result.text shouldContain "No user with that id"
        }
        result.text shouldContain "404"
        withClue("structuredContent is bound to the success schema, so a failure carries none") {
            result.structuredContent.shouldBeNull()
        }
    }

    @Test
    fun `a refinement rejects bad input before the handler runs`() {
        val seen = Seen()
        val result = api(seen).call("getOrders", jsonObj { "userId" to 7; "limit" to 0 }).result()

        result.isError.shouldBeTrue()
        result.text shouldContain "limit"
        withClue("the endpoint answers 400 for this, so the handler must never see it") {
            seen.limit.shouldBeNull()
        }
    }

    @Test
    fun `a missing required argument is an error naming what was missing`() {
        val result = api().call("getOrders", jsonObj { "limit" to 10 }).result()

        result.isError.shouldBeTrue()
        result.text shouldContain "userId"
    }

    @Test
    fun `a tool nobody described is an error listing the ones that exist`() {
        val result = api().call("reindexEverything", emptyJsonObj).result()

        result.isError.shouldBeTrue()
        result.text shouldContain "getOrders"
    }

    @Test
    fun `a body is decoded through the codec the endpoint declared`() {
        val seen = Seen()
        val result = api(seen)
            .call(
                "placeOrder",
                jsonObj {
                    "userId" to 7
                    put("body", jsonObj { "item" to "a-widget"; "quantity" to 3 })
                },
            )
            .result()

        seen.body shouldBe CreateOrder("a-widget", 3)
        withClue("a credential is supplied by whatever serves the tools, never by the model") {
            seen.apiKey shouldBe "let-me-in"
        }
        result.isError.shouldBeFalse()
    }

    // ------------------------------------------------------------- refusals

    private fun refusalFor(vararg endpoints: Endpoint<*, *>, options: McpOptions = McpOptions()): String =
        shouldThrow<IllegalArgumentException> {
            ApiSpec(endpoints.toList(), JacksonCodecs).mcpTools(options)
        }.message.orEmpty()

    @Test
    fun `a streamed answer is refused rather than half-served`() {
        val ticks = endpoint {
            get("ticks")
            operationId = "ticks"
            ndjson<Order>()
        }

        val message = refusalFor(ticks)
        message shouldContain "ticks"
        withClue("the refusal has to say how to get past it") { message shouldContain "include" }
    }

    @Test
    fun `a body no model could write is refused`() {
        val upload = endpoint(rawBody()) {
            post("upload")
            operationId = "upload"
            json<Order>()
        }

        refusalFor(upload) shouldContain "upload"
    }

    @Test
    fun `a cookie is refused, having no place in a tool call`() {
        val session = cookieParam<String>("session")
        val whoami = endpoint(session) {
            get("whoami")
            operationId = "whoami"
            json<Order>()
        }

        refusalFor(whoami) shouldContain "session"
    }

    @Test
    fun `a required header with no value behind it is refused, and supplying one is the way past`() {
        val message = refusalFor(placeOrder)
        message shouldContain "X-Api-Key"
        message shouldContain "headers"

        ApiSpec(listOf(placeOrder), JacksonCodecs)
            .mcpTools(McpOptions(headers = mapOf("X-Api-Key" to "let-me-in")))
            .single().name shouldBe "placeOrder"
    }
}

/** A tool call is asynchronous because the handler chain is; a test is not. */
private fun CompletionStage<ToolResult>.result(): ToolResult = toCompletableFuture().join()
