package example.compiler

/*
 * The call sites the 1.0 surface promises, written out as the source a user
 * types. `StillCompilesTest` compiles every one of them against the published
 * modules, so a rename anywhere in the DSL fails a test that names the area it
 * broke. Each fixture is given a package of its own before it is compiled, so
 * two of them may declare the same `Order`.
 *
 * Only the modules main ships appear below: core, schema, openapi, import,
 * jackson, mcp, the Pekko trio, `pelican-client-java` and the test modules.
 * The surfaces on the `multi-backend` branch are frozen there, in this file's
 * counterpart, and return with the modules.
 *
 * Every public reified-inline function in those modules has a line here, since
 * those are the ones no `.api` dump lists. The rest is what a user types around
 * them — the builders, the binders, the infix operators — pinned because a dump
 * records a JVM descriptor and not whether `orFail` still reads as `orFail`.
 */
internal val frozenCallSites: Map<String, String> = mapOf(

    // ------------------------------------------------------------ endpoints

    "endpoints" to """
        import io.github.matthewjones372.pelican.*

        data class Order(val id: Long)

        val orderId = pathParam<Long>("orderId")
        val userId = pathParam<Int>("userId")
        val region = pathParam<String>("region", description = "Where it shipped from")
        val sku = pathParam("sku", StringCodec.nonBlank())

        val listOrders = endpoint { get("orders"); json<Order>() }
        val getOrder = endpoint(orderId) { get("orders" / orderId); json<Order>() }
        val getUserOrder = endpoint(userId, orderId) { get(userId / orderId); json<Order>() }
        val bySku = endpoint(region, sku, orderId) {
            get(path("catalogue") / region / sku / orderId)
            json<Order>()
        }

        val a = queryParam<Int>("a")
        val b = queryParam<Int>("b")
        val c = queryParam<Int>("c")
        val four = endpoint(orderId, a, b, c) { get("orders" / orderId); json<Order>() }
        val five = endpoint(orderId, a, b, c, region) { put(region / orderId); json<Order>() }
        val six = endpoint(orderId, a, b, c, region, userId) {
            patch(userId / region / orderId)
            json<Order>()
        }

        val lens = endpoint(lensInputs) { get("search"); query(a); json<Order>() }

        val described = endpoint(orderId) {
            route(Method.DELETE, path("orders") / orderId)
            summary = "Cancel an order"
            description = "Cancels it, and says so."
            operationId = "cancelOrder"
            deprecated = true
            hidden = false
            tag("orders", "writes")
            servers("https://writes.example.com")
            empty()
        }

        val posted = endpoint { post("orders"); json<Order>(201) }
        val replaced = endpoint(orderId) { put("orders" / orderId); json<Order>() }
        val amended = endpoint(orderId) { patch("orders" / orderId); json<Order>() }
        val dropped = endpoint(orderId) { delete("orders" / orderId); empty() }
    """,

    // --------------------------------------------------------------- inputs

    "inputs" to """
        import io.github.matthewjones372.pelican.*

        data class Order(val id: Long)
        data class CreateOrder(val sku: String)

        val limit = queryParam<Int>("limit").default(25)
        val cursor = queryParam<String>("cursor").optional()
        val size = queryParam("size", IntCodec.between(1, 100))
        val tags = queryParam<String>("tag").repeated()
        val kinds = queryParam<String>("kind").commaSeparated()
        val words = queryParam<String>("word").spaceSeparated()
        val pipes = queryParam<String>("pipe").pipeSeparated()

        val apiKey = headerParam<String>("X-Api-Key", description = "The caller's key")
        val trace = headerParam<String>("X-Trace").optional()
        val accepts = headerParam<String>("X-Accepts").commaSeparated()
        val ifCount = headerParam("X-Count", IntCodec).default(1)

        val locale = cookieParam<String>("locale")
        val flags = cookieParam<String>("flag").repeated()
        val theme = cookieParam("theme", StringCodec).optional()

        val newOrder = jsonBody<CreateOrder>(description = "The order to place")
        val formOrder = formBody<CreateOrder>()
        val either = formBody<CreateOrder>() or jsonBody<CreateOrder>()
        val raw = rawBody(description = "Whatever the caller sends")
        val uploadedOrders = ndjsonIn<CreateOrder>(description = "One order per line")

        val note = textPart<String>("note")
        val count = textPart("count", IntCodec).default(0)
        val label = textPart<String>("label").optional()
        val upload = filePart("file", contentType = "image/png")
        val thumb = bufferedFile("thumb", maxBytes = 1024)
        val extra = bufferedFile("extra", maxBytes = 1024).optional()

        val search = endpoint(limit, cursor, size, tags, kinds) {
            get("orders")
            query(words, pipes)
            header(apiKey, trace, accepts, ifCount)
            cookie(locale, flags, theme)
            json<Order>()
        }

        val place = endpoint(newOrder) { post("orders"); json<Order>(201) }
        val placeForm = endpoint(formOrder) { post("orders" / "form"); json<Order>(201) }
        val placeEither = endpoint(either) { post("orders" / "either"); json<Order>(201) }
        val ingest = endpoint(raw) { post("orders" / "raw"); empty(202) }
        val bulk = endpoint(uploadedOrders) { post("orders" / "bulk"); json<Order>(202) }
        val declaredBody = endpoint { post("orders" / "ignored"); body(raw); empty(202) }
        val uploaded = endpoint(note, count, label, thumb, extra, upload) {
            post("orders" / "attachments")
            part(description = "The attachment and what to call it")
            empty(201)
        }

        fun read(p: Params): CreateOrder = p[newOrder]
    """,

    // -------------------------------------------------------------- outputs

    "outputs" to """
        import io.github.matthewjones372.pelican.*
        import kotlin.time.Duration.Companion.seconds

        data class Order(val id: Long)
        data class Report(val total: Long)
        data class Tick(val at: Long)

        val location = responseHeader<String>("Location", description = "Where it landed")
        val retryAfter = responseHeader("Retry-After", LongCodec).optional()

        val created = endpoint {
            post("orders")
            emits(retryAfter)
            json<Order>(201, location, description = "The order as stored") or empty(202)
        }

        val plain = endpoint { get("orders" / "note"); text() }
        val nothing = endpoint { delete("orders" / "all"); empty(204) }
        val opaque = endpoint { get("orders" / "export"); bytes("text/csv") }
        val lines = endpoint { get("orders" / "stream"); ndjson<Order>() }
        val array = endpoint { get("orders" / "array"); jsonArray<Order>(200) }

        val events = endpoint {
            get("orders" / "events")
            sse<Tick>(
                eventName = "tick",
                keepAlive = 15.seconds,
                description = "One frame per tick",
                id = { it.at.toString() },
                retry = 2.seconds,
            )
        }

        val csv = endpoint {
            get("reports")
            defaultJson<ApiError>("And anything else")
            negotiated(json<Report>(200), media<Report>("text/csv", 200))
        }

        val documented = endpoint {
            get("orders" / "documented")
            errorResponse(418, "A teapot")
            defaultResponse("And anything else")
            json<Order>()
        }

        // The same three outputs again, named outside the block so a handler
        // can invoke one; see the comment above them in Outputs.kt.
        val okOrder = json<Order>(200)
        val okCsv = media<Report>("text/csv", 200)
        val okText = text(200)
        val accepted = empty(202)
        val both = negotiated(okOrder, json<Order>(200, description = "again"))
    """,

    // ------------------------------------------------------------- failures

    "failures" to """
        import io.github.matthewjones372.pelican.*

        data class Order(val id: Long)
        data class Problem(val why: String)

        val retryAfter = responseHeader("Retry-After", LongCodec)
        val missing = errorJson<Problem>(404, "No order with that id")
        val throttled = errorJson<Problem>(429, "Slow down", retryAfter)

        val one = endpoint { get("orders"); json<Order>() orFail missing }
        val several = endpoint { get("orders" / "several"); json<Order>().orFail(missing, throttled) }
        val alternatives = endpoint { get("orders" / "alts"); json<Order>(200) or empty(202) }
        val threeWays = endpoint {
            get("orders" / "three")
            json<Order>(200).or(empty(202), json<Order>(203))
        }
        val bothKinds = endpoint {
            get("orders" / "both")
            (json<Order>(200) or empty(202)) orFail missing
        }
        val bothKindsMany = endpoint {
            get("orders" / "many")
            (json<Order>(200) or empty(202)).orFail(missing, throttled)
        }

        val inline = endpoint {
            get("orders" / "inline")
            val gone = errorJson<Problem>(410, "Gone")
            json<Order>() orFail gone
        }

        fun succeed(): Outcome<Problem, Order> = ok(Order(1))
        fun named(): Outcome<Problem, Order> = json<Order>(200)(Order(1))
        fun fail(): Outcome<Problem, Order> = missing(Problem("no"))
        fun withHeader(): Outcome<Problem, Order> = throttled(Problem("later"), retryAfter of 30L)
        fun empty202(): Outcome<Problem, Unit> = empty(202)()

        fun readBack(outcome: Outcome<Problem, Order>): String? = when (outcome) {
            is Outcome.Ok -> outcome.value.id.toString()
            is Outcome.Err -> outcome[retryAfter]?.toString()
        }
    """,

    // --------------------------------------------------------- construction

    "construction" to """
        import io.github.matthewjones372.pelican.*
        import io.github.matthewjones372.pelican.importer.Backend
        import io.github.matthewjones372.pelican.importer.importOptions
        import io.github.matthewjones372.pelican.jackson.JacksonCodecs
        import io.github.matthewjones372.pelican.mcp.mcpOptions
        import io.github.matthewjones372.pelican.openapi.OpenApiVersion
        import io.github.matthewjones372.pelican.openapi.docs
        import io.github.matthewjones372.pelican.openapi.openApi
        import io.github.matthewjones372.pelican.openapi.openApiJson
        import io.github.matthewjones372.pelican.openapi.openApiYaml
        import io.github.matthewjones372.pelican.pekko.handledNow
        import java.time.Duration

        data class Order(val id: Long)

        val orderId = pathParam<Long>("orderId")
        val getOrder = endpoint(orderId) { get("orders" / orderId); json<Order>() }
        val orderPlaced = webhook("orderPlaced") { json<Order>(); empty(204) }

        val bearer = bearerAuth("bearer")

        val service = api(listOf(getOrder handledNow { id -> Order(id) }), JacksonCodecs) {
            title = "Orders"
            version = "1.0.0"
            description = "Described as values."
            servers = listOf("https://orders.example.com")
            security = listOf(bearer.requires())
            cors = cors("https://ui.example.com", allowCredentials = true)
            strictBodyTimeoutMillis = 5_000
            maxBodyBytes = 1024 * 1024
            exposeInternalErrors = false
            covers = listOf(getOrder)
            webhooks = listOf(orderPlaced)
            filter(before { it.endpoint })
            filter(after { _, result, _ -> println(result) })
            filter(afterStatus { _, status, _ -> println(status) })
            onError { reference, endpoint, error -> println("${'$'}reference ${'$'}endpoint ${'$'}error") }
            refusals(ProblemDetails)
            onRefusal { reason, status, template -> println("${'$'}reason ${'$'}status ${'$'}template") }
        }

        val spec = ApiSpec(
            endpoints = listOf(getOrder),
            schemas = JacksonCodecs,
            title = "Orders",
            webhooks = listOf(orderPlaced),
            refusals = ApiErrorEnvelope,
        )

        val document = spec.openApi(OpenApiVersion.V3_2_0)
        val documentJson = spec.openApiJson()
        val documentYaml = spec.openApiYaml()

        val page = docs {
            openApiPath = "/openapi.json"
            docsPath = "/docs"
            version = OpenApiVersion.V3_1_0
        }

        val tools = mcpOptions {
            include = { it.operationName != "cancelOrder" }
            headers = mapOf("X-Api-Key" to "let-me-in")
        }

        val retries = retryPolicy {
            maxAttempts = 3
            initialBackoff = Duration.ofMillis(100)
            backoffMultiplier = 2.0
            maxBackoff = Duration.ofSeconds(2)
            jitter = 0.5
            statuses = TRANSIENT_STATUSES
            methods = IDEMPOTENT_METHODS
            retryStreamedBodies = false
            honourRetryAfter = true
            retryAfterCap = Duration.ofSeconds(10)
            failures = { it is java.io.IOException }
        }

        val imported = importOptions("com.example.orders", "orders") {
            exclude = setOf("Internal")
            discriminators = mapOf("Payment" to "kind")
            handlers = Backend.PEKKO
        }
    """,

    // -------------------------------------------------------------- binders

    "binders" to """
        import io.github.matthewjones372.pelican.*
        import io.github.matthewjones372.pelican.jackson.JacksonCodecs
        import io.github.matthewjones372.pelican.mcp.mcpOptions
        import io.github.matthewjones372.pelican.openapi.docs
        import io.github.matthewjones372.pelican.pekko.*
        import io.github.matthewjones372.pelican.pekko.docs.docsRoutes
        import io.github.matthewjones372.pelican.pekko.docs.routeWithDocs
        import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
        import io.github.matthewjones372.pelican.pekko.mcp.mcpRoutes
        import io.github.matthewjones372.pelican.pekko.mcp.routeWithMcp
        import org.apache.pekko.NotUsed
        import org.apache.pekko.actor.typed.ActorSystem
        import org.apache.pekko.stream.javadsl.Sink
        import org.apache.pekko.stream.javadsl.Source
        import org.apache.pekko.util.ByteString
        import java.util.concurrent.CompletableFuture
        import java.util.concurrent.CompletionStage

        data class Order(val id: Long)
        data class Problem(val why: String)

        val orderId = pathParam<Long>("orderId")
        val missing = errorJson<Problem>(404, "No order with that id")
        val raw = rawBody()
        val uploaded = ndjsonIn<Order>()

        val getOrder = endpoint(orderId) { get("orders" / orderId); json<Order>() }
        val dropOrder = endpoint(orderId) { delete("orders" / orderId); empty() }
        val findOrder = endpoint(orderId) { get("find" / orderId); json<Order>() orFail missing }
        val twoWays = endpoint(orderId) {
            get("two" / orderId)
            (json<Order>(200) or empty(202)) orFail missing
        }
        val streamOrders = endpoint { get("orders" / "stream"); ndjson<Order>() }
        val streamOrFail = endpoint(orderId) {
            get("stream" / orderId)
            ndjson<Order>() orFail missing
        }
        val download = endpoint { get("orders" / "export"); bytes() }
        val downloadOrFail = endpoint(orderId) {
            get("export" / orderId)
            bytes() orFail missing
        }
        val ingest = endpoint(raw) { post("orders" / "raw"); empty(202) }
        val bulkCount = endpoint(uploaded) { post("orders" / "bulk"); json<Order>(202) }
        val bulkEcho = endpoint(uploaded) { post("orders" / "echo"); ndjson<Order>(202) }

        fun later(order: Order): CompletionStage<Order> = CompletableFuture.completedFuture(order)

        val bound: List<ServerEndpoint> = listOf(
            getOrder handledNow { id -> Order(id) },
            getOrder handledBy { id -> later(Order(id)) },
            dropOrder handledWith { },
            findOrder handledOrFail { id -> ok(Order(id)) },
            findOrder handledByOrFail { id -> CompletableFuture.completedFuture(ok(Order(id))) },
            twoWays handledOneOf { id -> ok(Order(id)) },
            twoWays handledByOneOf { id -> CompletableFuture.completedFuture(ok(Order(id))) },
            streamOrders streamedNow { Source.single(Order(1)) },
            streamOrders streamedBy { CompletableFuture.completedFuture(Source.single(Order(1))) },
            streamOrFail streamedOrFail { id -> ok(Source.single(Order(id))) },
            streamOrFail streamedByOrFail { id ->
                CompletableFuture.completedFuture(ok(Source.single(Order(id))))
            },
            download bytesNow { Source.single(ByteString.fromString("a")) },
            downloadOrFail bytesOrFail { ok(Source.single(ByteString.fromString("a"))) },
            ingest handledWith { it.toSource() },
            // A streamed request body, read two ways: consumed for a value on
            // the request's own system, and handed straight back as the response.
            bulkCount handledBy { frames ->
                frames.runWith(Sink.seq<Order>()).thenApply { Order(it.size.toLong()) }
            },
            bulkEcho streamedNow { frames -> frames.toSource() },
        )

        val service = api(bound, JacksonCodecs)

        fun routes(system: ActorSystem<Void>) = service.toRoute(system)
        fun withDocs(system: ActorSystem<Void>) = service.routeWithDocs(system, docs())
        fun docsOnly(system: ActorSystem<Void>) = service.docsRoutes(docs())
        fun withMcp(system: ActorSystem<Void>) = service.routeWithMcp(system, mcpOptions())
        fun mcpOnly() = service.mcpRoutes(mcpOptions())

        fun run(): PelicanServer = service.start(port = 0)
        fun runOn(system: ActorSystem<Void>): PelicanServer = service.start(system, port = 0)
        fun runWithDocs(): PelicanServer = service.startWithDocs(port = 0, docs = docs())

        // The two things a handler reads off the request rather than off its
        // declared inputs.
        val resumable = streamOrders streamedNow {
            val from: String? = lastEventId()
            val method = request.method()
            Source.single(Order(from?.length?.toLong() ?: method.value().length.toLong()))
        }
    """,

    // -------------------------------------------------------------- clients

    "clients" to """
        import io.github.matthewjones372.pelican.*
        import io.github.matthewjones372.pelican.client.JavaHttpTransport
        import io.github.matthewjones372.pelican.jackson.JacksonCodecs
        import io.github.matthewjones372.pelican.pekko.handledNow
        import java.io.ByteArrayInputStream
        import java.time.Duration
        import java.util.concurrent.CompletionStage
        import kotlin.reflect.typeOf

        data class Order(val id: Long)

        val orderId = pathParam<Long>("orderId")
        val getOrder = endpoint(orderId) { get("orders" / orderId); json<Order>() }
        val service = api(listOf(getOrder handledNow { id -> Order(id) }), JacksonCodecs)

        // What a generated client is written against: it builds a request out
        // of these three types and hands it to whatever transport it was given.
        fun call(transport: ClientTransport): CompletionStage<ClientResponse> {
            val request = ClientRequest(
                method = Method.GET,
                url = "https://orders.example.com/orders/1",
                headers = listOf("Accept" to "application/json"),
                body = ClientRequest.Body.Text("{}"),
            )
                .withHeader("X-Api-Key", "let-me-in")
                .withTimeout(Duration.ofSeconds(5))
            return transport.send(request)
        }

        fun decode(codecs: Codecs, response: ClientResponse): Order {
            val codec: BodyCodec<Order> = codecs.codec(typeOf<Order>())
            return codec.decodeFromString(response.text())
        }

        val streamed = ClientRequest(
            Method.POST,
            "https://orders.example.com/orders",
            emptyList(),
            ClientRequest.Body.Streaming { ByteArrayInputStream(ByteArray(0)) },
        )
        val nothing: ClientRequest.Body = ClientRequest.Body.Empty

        val discovered: ClientTransport = ClientTransport.default()
        val retrying: ClientTransport = JavaHttpTransport().retrying(retryPolicy { maxAttempts = 2 })
        val wrapped: ClientTransport = RetryingTransport(JavaHttpTransport(), retryPolicy())

        // No socket at all: the same client shape, answered by the routes.
        val inProcess: ClientTransport = InMemoryClientTransport(service)
        val formEncoded = renderFormBody(listOf("sku" to "a"))
        val formParsed = parseFormBody(formEncoded)
    """,

    // -------------------------------------------------------------- testing

    "testing" to """
        import io.github.matthewjones372.pelican.*
        import io.github.matthewjones372.pelican.jackson.JacksonCodecs
        import io.github.matthewjones372.pelican.pekko.handledNow
        import io.github.matthewjones372.pelican.pekko.handledOrFail
        import io.github.matthewjones372.pelican.pekko.streamedNow
        import io.github.matthewjones372.pelican.test.*
        import io.github.matthewjones372.pelican.test.golden.Golden
        import io.github.matthewjones372.pelican.test.golden.requestsOnly
        import io.github.matthewjones372.pelican.test.golden.wireText
        import io.github.matthewjones372.pelican.test.pekko.inMemory
        import org.apache.pekko.stream.javadsl.Source

        data class Order(val id: Long)
        data class Problem(val why: String)

        val orderId = pathParam<Long>("orderId")
        val missing = errorJson<Problem>(404, "No order with that id")
        val uploaded = ndjsonIn<Order>()
        val getOrder = endpoint(orderId) { get("orders" / orderId); json<Order>() }
        val findOrder = endpoint(orderId) { get("find" / orderId); json<Order>() orFail missing }
        val streamOrders = endpoint { get("orders" / "stream"); ndjson<Order>() }
        val bulk = endpoint(uploaded) { post("orders" / "bulk"); json<Order>(202) }

        val service = api(
            listOf(
                getOrder handledNow { id -> Order(id) },
                findOrder handledOrFail { id -> ok(Order(id)) },
                streamOrders streamedNow { Source.single(Order(1)) },
                bulk handledNow { Order(1) },
            ),
            JacksonCodecs,
        ) {
            maxFrameBytes = 64 * 1024
        }

        val spec = ApiSpec(listOf(getOrder, findOrder, streamOrders, bulk), JacksonCodecs)

        fun overHttp(): ApiClient = apiClient("http://localhost:8080", JacksonCodecs)
        fun inProcess(): ApiClient = service.inMemory()
        fun built(): ApiClient = requestsOnly(JacksonCodecs)

        fun urls() {
            built().request(getOrder, 1L) shouldBuild "/orders/1"
            built().request(getOrder, 1L).withHeader("X-Api-Key", "k").wireText()
        }

        fun calls(client: ApiClient) {
            val order: Order = client.call(getOrder, 1L)
            val value: Order = client.call(findOrder, 1L)
            val outcome: Outcome<Problem, Order> = client.outcome(findOrder, 1L)
            outcome.shouldBeOk()
            outcome.shouldBeError()
            outcome shouldBeFailure missing
            outcome shouldBeResponse json<Order>(200)
            val stream: List<Order> = client.collect(streamOrders, Unit, lastEventId = "7")
            client.call(bulk, frames(Order(1), Order(2)))
            client.call(bulk, frames(listOf(Order(3))))

            val response: ResponseSpec = client.response(getOrder, 1L)
            response.shouldBeSuccessful()
            response.shouldHaveStatus(200)
            response.shouldHaveHeader("Content-Type", "application/json")
            response.shouldHaveContentType("application/json")
            response.shouldHaveNoBody()
            val decoded: Order = client.decodeBody(response)
            client.errorBody(response)
            client.shouldBeApiError(response, 404, "Not found")
            client.sending("application/json")

            println("${'$'}order ${'$'}value ${'$'}stream ${'$'}decoded")
        }

        fun goldens(client: ApiClient) {
            val golden = Golden(strict = false)
            golden.document(spec)
            golden.operations(spec)
            golden.operation(spec, getOrder)
            golden.request("get-order", client.request(getOrder, 1L))
            golden.response("get-order", client.response(getOrder, 1L))
            golden.exchange("get-order", client, getOrder, 1L)
        }

        val body: ByteStreamHandle = rawText("hello")
    """,

    // --------------------------------------------------------------- codecs

    "codecs" to """
        import io.github.matthewjones372.pelican.*
        import io.github.matthewjones372.pelican.jackson.JacksonCodecs
        import io.github.matthewjones372.pelican.jackson.defaultMapper
        import io.github.matthewjones372.pelican.schema.StandaloneSchemas
        import java.net.URI
        import java.time.Instant
        import java.time.LocalDate
        import java.time.LocalDateTime
        import java.util.UUID

        data class Order(val id: Long)
        enum class Kind { FAST, SLOW }

        @JvmInline value class Slug(val value: String)

        val strings: PlainCodec<String> = plainCodecFor<String>()
        val ints: PlainCodec<Int> = plainCodecFor<Int>()
        val longs: PlainCodec<Long> = plainCodecFor<Long>()
        val doubles: PlainCodec<Double> = plainCodecFor<Double>()
        val booleans: PlainCodec<Boolean> = plainCodecFor<Boolean>()
        val uuids: PlainCodec<UUID> = plainCodecFor<UUID>()
        val kinds: PlainCodec<Kind> = plainCodecFor<Kind>()

        val builtIn = listOf<PlainCodec<*>>(
            StringCodec, IntCodec, LongCodec, DoubleCodec, BooleanCodec, UuidCodec,
            InstantCodec, LocalDateCodec, LocalDateTimeCodec, UriCodec, NonEmptyStringCodec,
            EnumCodec(Kind.entries.toTypedArray(), Kind::name),
        )

        val slug: PlainCodec<Slug> = StringCodec
            .matching(Regex("[a-z-]{1,40}"), "a slug")
            .nonEmpty()
            .nonBlank()
            .minLength(1)
            .maxLength(40)
            .map(::Slug, Slug::value)
            .describedAs("A URL-safe tag", example = "streams")

        val bounded: PlainCodec<Int> = IntCodec
            .between(1, 100)
            .atLeast(1)
            .atMost(100)
            .positive()
            .refine("at most a hundred") { it <= 100 }
            .withFacets(jsonObj { "multipleOf" to 1 })

        val mapped: PlainCodec<Slug> = StringCodec.mapOrFail(
            expected = "a slug",
            decode = { if (it.isBlank()) null else Slug(it) },
            encode = Slug::value,
        )

        val moment: Instant = InstantCodec.decode("when", "2026-08-26T00:00:00Z")
        val day: LocalDate = LocalDateCodec.decode("when", "2026-08-26")
        val stamp: LocalDateTime = LocalDateTimeCodec.decode("when", "2026-08-26T00:00:00")
        val where: URI = UriCodec.decode("where", "https://example.com")
        val schema = StringCodec.openApiSchema()

        val codecs: Codecs = JacksonCodecs
        val mapper = defaultMapper()
        val bodyCodec: BodyCodec<Order> = codecs.codec(kotlin.reflect.typeOf<Order>())
        val encoded: String = bodyCodec.encodeToString(Order(1))
        val decoded: Order = bodyCodec.decodeFromString(encoded)
        val standalone = StandaloneSchemas(JacksonCodecs).schema(kotlin.reflect.typeOf<Order>())
    """,
)
