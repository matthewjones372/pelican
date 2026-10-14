package io.github.matthewjones372.pelican.benchmarks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.jetty.PELICAN_URI_COMPLIANCE
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.jetty.toHandler
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.queryParam
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.http.HttpTester
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.LocalConnector
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.Callback
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * What the description costs on Jetty: [PekkoOverheadBenchmark]'s GET and POST, served by `toHandler` and by a
 * `Handler.Abstract` written against Jetty core, each on its own started server.
 *
 * Through a `LocalConnector`, so no socket but every request is parsed from bytes and every response generated to
 * them, as a bound server would. That is HTTP/1.1 work Pekko's `Route.function` never does, which is why the two
 * files' numbers are not read against each other.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class JettyOverheadBenchmark {

    data class Item(val id: Long, val name: String)

    data class NewItem(val name: String)

    private val itemId = pathParam<Long>("itemId")
    private val limit = queryParam("limit", IntCodec).default(10)

    private val getItem = endpoint(itemId, limit) {
        get("items" / itemId)
        json<Item>()
    }

    private val createItem = endpoint(jsonBody<NewItem>()) {
        post("items")
        json<Item>(status = 201)
    }

    private val described: Api = api(
        endpoints = listOf(
            getItem handledNow { (id, lim) -> Item(id, "item-$lim") },
            createItem handledNow { body -> Item(1, body.name) },
        ),
        codecs = JacksonCodecs,
    )

    /** The same two routes, written the way a Jetty core service would write them. */
    private class HandWritten : Handler.Abstract() {
        private val mapper = ObjectMapper().registerKotlinModule()

        override fun handle(request: Request, response: Response, callback: Callback): Boolean {
            val path = Request.getPathInContext(request)
            return when {
                request.method == "GET" && path.startsWith("/items/") -> {
                    val id = path.removePrefix("/items/").toLongOrNull() ?: return false
                    val lim = Request.extractQueryParameters(request).getValue("limit")?.toInt() ?: 10
                    respond(response, callback, 200, Item(id, "item-$lim"))
                    true
                }

                request.method == "POST" && path == "/items" -> {
                    Content.Source.asByteArrayAsync(request, MAX_BODY).whenComplete { bytes, failure ->
                        if (failure != null) {
                            Response.writeError(request, response, callback, failure)
                        } else {
                            respond(response, callback, 201, Item(1, mapper.readValue(bytes, NewItem::class.java).name))
                        }
                    }
                    true
                }

                else -> false
            }
        }

        private fun respond(response: Response, callback: Callback, status: Int, item: Item) {
            response.status = status
            response.headers.put(HttpHeader.CONTENT_TYPE, "application/json")
            response.write(true, ByteBuffer.wrap(mapper.writeValueAsBytes(item)), callback)
        }
    }

    private class Served(handler: Handler) {
        val server = Server()
        val connector = LocalConnector(
            server,
            HttpConnectionFactory(HttpConfiguration().apply { uriCompliance = PELICAN_URI_COMPLIANCE }),
        )

        init {
            server.addConnector(connector)
            server.handler = handler
            server.start()
        }

        /** A duplicate, because the connector consumes the buffer it is handed. */
        fun answer(request: ByteBuffer): ByteBuffer =
            connector.getResponse(request.duplicate(), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?: error("No response within $TIMEOUT_SECONDS seconds")
    }

    // Pelican twice: on the Jetty thread, which is where the hand-written handler answers, and on the virtual
    // thread per request that `toHandler` uses unless told otherwise.
    private val onDispatcher = Served(described.toHandler(Handlers.onDispatcher))
    private val onVirtualThreads = Served(described.toHandler(Handlers.onVirtualThreads))
    private val handWritten = Served(HandWritten())

    // Generated once: the bytes are what each request costs the server to parse, not what building them costs.
    private val request = rawRequest("GET", "/items/7?limit=25", null)
    private val postRequest = rawRequest("POST", "/items", """{"name":"anvil"}""")

    private fun rawRequest(method: String, uri: String, body: String?): ByteBuffer {
        val req = HttpTester.newRequest()
        req.method = method
        req.uri = uri
        req.put("Host", "benchmark")
        body?.let {
            req.put("Content-Type", "application/json")
            req.setContent(it)
        }
        return req.generate()
    }

    /** As in [PekkoOverheadBenchmark.verify]: a handler that passes is a 404, and would be the fastest row. */
    @Setup
    fun verify() {
        listOf(onDispatcher, onVirtualThreads, handWritten).forEach { served ->
            val get = HttpTester.parseResponse(served.answer(request))
            check(get.status == 200 && get.content == """{"id":7,"name":"item-25"}""") { "GET answered $get" }
            val post = HttpTester.parseResponse(served.answer(postRequest))
            check(post.status == 201 && post.content == """{"id":1,"name":"anvil"}""") { "POST answered $post" }
        }
    }

    @TearDown
    fun stop() {
        listOf(onDispatcher, onVirtualThreads, handWritten).forEach { it.server.stop() }
    }

    // The returned buffer is what stops the JIT deleting the call.

    @Benchmark
    fun pelican(): ByteBuffer = onDispatcher.answer(request)

    @Benchmark
    fun pelicanOnVirtualThreads(): ByteBuffer = onVirtualThreads.answer(request)

    @Benchmark
    fun jetty(): ByteBuffer = handWritten.answer(request)

    @Benchmark
    fun pelicanPostBody(): ByteBuffer = onDispatcher.answer(postRequest)

    @Benchmark
    fun pelicanPostBodyOnVirtualThreads(): ByteBuffer = onVirtualThreads.answer(postRequest)

    @Benchmark
    fun jettyPostBody(): ByteBuffer = handWritten.answer(postRequest)

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val MAX_BODY = 1024 * 1024
    }
}
