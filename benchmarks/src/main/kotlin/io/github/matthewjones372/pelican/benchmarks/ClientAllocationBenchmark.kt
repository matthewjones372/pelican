package io.github.matthewjones372.pelican.benchmarks

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.client.jetty.JettyHttpTransport
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.path
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.reflect.typeOf

/**
 * What one `ApiClient` call allocates (spec 0091): in process with a canned answer, and over loopback with Jetty
 * 12's client. Read `gc.alloc.rate.norm`, which counts every thread, so the Jetty rows include the in-process
 * server's share.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2, jvmArgsAppend = ["-Xms1g", "-Xmx1g"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class ClientAllocationBenchmark {

    data class Pet(val id: Long, val name: String, val species: String, val tags: List<String>, val price: Double)

    private val petId = pathParam<Long>("petId")
    private val limit = queryParam<Int>("limit")
    private val noSuchPet = errorJson<ApiError>(404, "No pet")
    private val getPet = endpoint(petId) {
        get("pets" / petId)
        json<Pet>() orFail noSuchPet
    }
    private val listPets = endpoint(limit) {
        get(path("pets"))
        json<List<Pet>>()
    }

    /** Pets in the list body: 1 is ~90 bytes, 10 is ~1 KB, near what petshop's 11 KB/request implies. */
    @Param("1", "10")
    var pets: Int = 0

    private lateinit var petBytes: ByteArray
    private lateinit var listBytes: ByteArray
    private val responseHeaders = listOf(
        "Content-Type" to "application/json",
        "Content-Length" to "100",
        "Date" to "Sun, 25 Oct 2026 10:00:00 GMT",
        "Server" to "Jetty(12.1.14)",
    )
    private val listCodec: BodyCodec<List<Pet>> = JacksonCodecs.codec(typeOf<List<Pet>>())

    private lateinit var canned: ApiClient
    private lateinit var fresh: ApiClient
    private lateinit var server: Server
    private lateinit var http: HttpClient
    private lateinit var base: String
    private lateinit var overJetty: ApiClient
    private lateinit var pelicanJetty: JettyHttpTransport

    @Setup
    fun start() {
        val all = (1..pets).map { Pet(it.toLong(), "Biscuit-$it", "dog", listOf("friendly", "vaccinated"), 125.5) }
        petBytes = JacksonCodecs.codec<Pet>(typeOf<Pet>()).encodeToString(all.first()).toByteArray()
        listBytes = listCodec.encodeToString(all).toByteArray()
        val petText = String(petBytes)
        val listText = String(listBytes)

        // Shares one String per body: only Pelican's own frames allocate.
        canned = ApiClient(
            object : Transport {
                override fun send(request: RequestSpec) =
                    ResponseSpec(200, responseHeaders, if (request.path == "/pets") listText else petText)
            },
            JacksonCodecs,
        )
        // The least any String-bodied transport can do: one UTF-8 decode per response.
        fresh = ApiClient(
            object : Transport {
                override fun send(request: RequestSpec) = ResponseSpec(
                    200,
                    responseHeaders,
                    String(if (request.path == "/pets") listBytes else petBytes, Charsets.UTF_8),
                )
            },
            JacksonCodecs,
        )

        server = Server()
        val connector = ServerConnector(server).apply { host = "127.0.0.1" }
        server.addConnector(connector)
        server.handler = object : Handler.Abstract() {
            override fun handle(request: Request, response: Response, callback: Callback): Boolean {
                val body = if (Request.getPathInContext(request) == "/pets") listBytes else petBytes
                response.status = 200
                response.headers.put(HttpHeader.CONTENT_TYPE, "application/json")
                response.write(true, ByteBuffer.wrap(body), callback)
                return true
            }
        }
        server.start()
        base = "http://127.0.0.1:${connector.localPort}"
        http = HttpClient().apply { start() }
        overJetty = ApiClient(StringBodied(http, base), JacksonCodecs)
        pelicanJetty = JettyHttpTransport(http)

        check(overJetty.call(listPets, pets).size == pets)
        check(jettyBytes().size == pets && jettyStream().size == pets)
    }

    @TearDown
    fun stop() {
        http.stop()
        server.stop()
    }

    /** As petshop's `JettyTransport` is written: `ContentResponse`, `getContentAsString`, a `Pair` per header. */
    private class StringBodied(private val http: HttpClient, private val base: String) : Transport {
        override fun send(request: RequestSpec): ResponseSpec {
            val res = http.newRequest(base + request.target)
                .method(request.method.name)
                .headers { fields -> request.headers.forEach { (n, v) -> fields.add(n, v) } }
                .send()
            return ResponseSpec(res.status, res.headers.map { it.name to it.value }, res.contentAsString)
        }
    }

    @Benchmark
    fun inMemoryRequestOnly(): RequestSpec = canned.request(listPets, pets)

    @Benchmark
    fun inMemoryCallSmall(): Pet = canned.call(getPet, PET_ID)

    @Benchmark
    fun inMemoryOutcomeSmall(): Outcome<ApiError, Pet> = canned.outcome(getPet, PET_ID)

    @Benchmark
    fun inMemoryCallList(): List<Pet> = canned.call(listPets, pets)

    @Benchmark
    fun inMemoryCallListFreshString(): List<Pet> = fresh.call(listPets, pets)

    /** The floor a byte body could reach: the same request, then `decodeFrom` over bytes already held. */
    @Benchmark
    fun inMemoryListDecodedFromBytes(): List<Pet> {
        canned.request(listPets, pets)
        return listCodec.decodeFrom(listBytes)
    }

    /** As above, with the codec looked up per call: what a client that caches nothing pays. */
    @Benchmark
    fun inMemoryListCodecPerCall(): List<Pet> {
        canned.request(listPets, pets)
        return JacksonCodecs.codec<List<Pet>>(typeOf<List<Pet>>()).decodeFrom(listBytes)
    }

    @Benchmark
    fun jettyString(): List<Pet> = overJetty.call(listPets, pets)

    /** `ContentResponse.getContent()` handed to `decodeFrom`: no String, Jetty's own `toArray` still there. */
    @Benchmark
    fun jettyBytes(): List<Pet> {
        val req = overJetty.request(listPets, pets)
        val res = http.newRequest(base + req.target).method(req.method.name).send()
        return listCodec.decodeFrom(res.content)
    }

    /** Through `pelican-client-jetty`, whose body is an `InputStream` over Jetty's buffers. */
    @Benchmark
    fun jettyStream(): List<Pet> {
        val req = overJetty.request(listPets, pets)
        val res = pelicanJetty.send(ClientRequest(req.method, base + req.target, req.headers))
            .toCompletableFuture().join()
        return listCodec.decodeFrom(res.body.use { it.readAllBytes() })
    }

    /** Jetty's exchange and nothing read from the body: the floor under every Jetty row. */
    @Benchmark
    fun jettyDiscard(): Int = http.newRequest("$base/pets?limit=$pets").send().status
}

private const val PET_ID = 7L
