package io.github.matthewjones372.pelican.test.wiremock

import io.github.matthewjones372.pelican.BodyDecodeFailure
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.InMemoryClientTransport
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.PathParam
import io.github.matthewjones372.pelican.PathSegment
import io.github.matthewjones372.pelican.PlainCodec
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jsonArr
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import io.github.matthewjones372.pelican.renderPretty
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Rewrites every file instead of failing: `./gradlew test -Dpelican.golden.update=true`.
 *
 * The same property the document goldens read, so one switch rewrites everything a contract change
 * touched. Declared again rather than shared, because `pelican-test-golden` carries the OpenAPI
 * generator and this module is a WireMock consumer's whole classpath.
 */
const val STUB_FILE_UPDATE_PROPERTY: String = "pelican.golden.update"

// Framing is WireMock's to decide once it knows the body it is sending, not Pelican's to record.
private val FRAMING_HEADERS = listOf("Content-Length", "Transfer-Encoding", "Connection")

// WireMock tries lower numbers first. A fixed input is the more specific stub, so it has to beat the
// template for the one request it names; both sit on the default 5 otherwise and file order decides.
private const val FIXED_PRIORITY = 1
private const val TEMPLATED_PRIORITY = 5

/** The transformer WireMock fills a template with; named per stub so nothing else is templated. */
private const val TEMPLATE_TRANSFORMER = "response-template"

/**
 * The stubs a demo needs, written in endpoints and exported as WireMock mapping files.
 *
 * The point is that the answer is written once. [PelicanWireMock] renders a stub's answer through
 * the codecs a server would use; so does this, so a `404` carries the `Problem` its contract
 * declares rather than whatever a hand-written file happened to say. See spec 0062.
 *
 * ```kotlin
 * stubFile(JacksonCodecs) {
 *     stub(lookupChip) { (petId, _, _) -> ok(Chip("98100000000000$petId", "Petshop")) }
 *     stub(lookupChip, In3(3L, "eu", 1)) answers noSuchChip(Problem("never chipped"))
 * }.writeTo(Path.of("demo/registry/mappings"))
 * ```
 */
fun stubFile(codecs: Codecs, declare: StubFile.() -> Unit): StubFile =
    StubFile(codecs).apply(declare)

/** A set of stubs, ready to be written. Build one with [stubFile]. */
class StubFile internal constructor(private val codecs: Codecs) {

    // Accumulated as they are declared and frozen into the files writeTo renders; see
    // FunctionalStyleTest, which records why this one is allowed to be mutable.
    private val declared = mutableListOf<Declared>()

    /**
     * No transport is ever used: [ApiClient.request] builds the request a call would send without
     * sending it, which is exactly the request a mapping has to match.
     */
    private val requests = ApiClient(UnusedTransport, codecs)

    /** [endpoint] asked with exactly [input]; say what it answers with on the result. */
    fun <I, E : Any, T : Any> stub(endpoint: Endpoint<I, Outcome<E, T>>, input: I): Stubbing<I, E, T> =
        Stubbing(endpoint, input)

    /**
     * Every call to [endpoint], answered from its own decoded input, as one mapping with a response
     * template filling the path parameters the answer uses.
     *
     * A lambda cannot be read, so the template is derived by asking: the endpoint is called twice
     * with distinctive values, and the answer's dependence on the path is whatever moved with them.
     * An answer that moved for any other reason — a query parameter, a header — cannot be written as
     * a template and is refused here, naming the endpoint, rather than exported wrong.
     */
    fun <I, E : Any, T : Any> stub(endpoint: Endpoint<I, Outcome<E, T>>, answer: (I) -> Outcome<E, T>) {
        require(endpoint.bodyInput == null) {
            "${endpoint.pathSpec.template} answers from its input, and that input includes a request " +
                "body. Pelican cannot build a body of an arbitrary type to probe it with, so give it one: " +
                "stub(endpoint, example = input) { input -> ... }."
        }
        templated(endpoint, answer, example = null)
    }

    /**
     * The same, for an endpoint with a JSON request body, whose answer may copy values out of it.
     *
     * [example] is a body the endpoint accepts. Each string and number in it is varied like a path
     * parameter, and one the answer copies becomes `{{jsonPath request.body '$.field'}}`. Values in
     * arrays, and booleans, keep the example's value. See spec 0067.
     */
    fun <I, E : Any, T : Any> stub(endpoint: Endpoint<I, Outcome<E, T>>, example: I, answer: (I) -> Outcome<E, T>) {
        templated(endpoint, answer, requests.request(endpoint, example))
    }

    private fun <I, E : Any, T : Any> templated(
        endpoint: Endpoint<I, Outcome<E, T>>,
        answer: (I) -> Outcome<E, T>,
        example: RequestSpec?,
    ) {
        val where = endpoint.pathSpec.template
        val body = example?.body?.let { text ->
            val parsed = try {
                codecs.readTree(text)
            } catch (_: BodyDecodeFailure) {
                null
            }
            require(parsed is JsonObj) {
                "$where's example body is not a JSON object, and only a JSON object's fields can be " +
                    "found again in an answer and templated. Name the inputs you need with " +
                    "stub(endpoint, input) instead."
            }
            ExampleBody(parsed, example.headers.filter { (name, _) -> name.equals(CONTENT_TYPE, true) })
        }
        val captures = endpoint.pathSpec.segments.withIndex()
            .mapNotNull { (at, segment) -> (segment as? PathSegment.Capture)?.let { at to it.param } }

        val probes = listOf(0, 1).map { round -> probe(endpoint, answer, round, body) }
        val templates = probes.map { it.templated(captures) }
        val answers = probes.map { it.answer }

        require(answers[0].status == answers[1].status) {
            "$where answers from its input with a status that changes with it — " +
                "${answers[0].status} and then ${answers[1].status}. One mapping carries one status, " +
                "so name each input with stub(endpoint, input) instead."
        }
        require(templates[0] == templates[1]) {
            "$where answers from something other than a path parameter or a value copied from the " +
                "body — a query parameter, a header, the clock, or a body value it computed with. Only " +
                "a value copied as it is can be filled into a template, so this stub cannot be exported " +
                "as one. Name the inputs you need with stub(endpoint, input) instead."
        }

        declared += Declared(
            root = endpoint.pathRoot(),
            request = jsonObj {
                "method" to endpoint.method.name
                // The path only, and nothing about the query: the answer was proved not to depend on
                // one, so constraining it here would stop the mapping answering a call that differs.
                "urlPathPattern" to endpoint.pathPattern()
            },
            response = response(answers[0], templates[0]) + jsonObj {
                "transformers" to jsonStrings(listOf(TEMPLATE_TRANSFORMER))
            },
            priority = TEMPLATED_PRIORITY,
        )
    }

    /** One stub, waiting to be told what it answers with. */
    inner class Stubbing<I, E : Any, T : Any> internal constructor(
        private val endpoint: Endpoint<I, Outcome<E, T>>,
        private val input: I,
    ) {
        /** `ok(value)` or a failure the endpoint declares; nothing else compiles. */
        infix fun answers(outcome: Outcome<E, T>) {
            val request = requests.request(endpoint, input)
            val answer = served(endpoint) { outcome }.answer(request.asClientRequest())
            declared += Declared(
                root = endpoint.pathRoot(),
                request = jsonObj {
                    "method" to request.method.name
                    "urlPath" to request.path
                    put("queryParameters", equalTo(request.query))
                    put("headers", equalTo(request.headers))
                    request.body?.let { "bodyPatterns" to jsonArr(listOf(jsonObj { "equalToJson" to it })) }
                },
                response = response(answer, answer.body),
                priority = FIXED_PRIORITY,
            )
        }
    }

    /**
     * Writes one file per path root into [dir], and compares rather than overwrites: a contract
     * change that moves a demo's answer fails here, in review, instead of at demo time.
     */
    fun writeTo(dir: Path) {
        dir.createDirectories()
        declared.groupBy { it.root }.forEach { (root, group) ->
            val file = dir.resolve("$root.json")
            val rendered = jsonObj { "mappings" to jsonArr(group.map { it.mapping() }) }.renderPretty() + "\n"
            when {
                !file.exists() || System.getProperty(STUB_FILE_UPDATE_PROPERTY) == "true" ->
                    file.writeText(rendered)

                file.readText() != rendered -> throw AssertionError(
                    "$file no longer matches the contract these stubs are written in. Read the diff and " +
                        "decide whether the change is intended; rewrite with " +
                        "-D$STUB_FILE_UPDATE_PROPERTY=true.",
                )
            }
        }
    }

    /**
     * The endpoint served in memory, so an answer is rendered by the code a server answers with and
     * its status, content type and body are the ones the contract's own service would send.
     */
    private fun <I, E : Any, T : Any> served(
        endpoint: Endpoint<I, Outcome<E, T>>,
        answer: (I) -> Outcome<E, T>,
    ): InMemoryClientTransport = InMemoryClientTransport(
        api(
            listOf(
                ServerEndpoint(endpoint) { p ->
                    CompletableFuture.completedFuture(answer(endpoint.inputs.extract(p)))
                },
            ),
            codecs,
        ),
    )

    /**
     * Calls [endpoint] once with values distinctive enough to be found again in the answer.
     *
     * Whether the handler ran is checked rather than assumed. A probe value the endpoint refuses —
     * an `int32` parameter handed an `int64`, a pattern a refinement rejects — is answered by
     * Pelican before the handler is reached, and that refusal quotes the value, so it reads exactly
     * like an answer that moved with its input. Saying which of the two happened is the difference
     * between a fixable message and a wrong one.
     */
    private fun <I, E : Any, T : Any> probe(
        endpoint: Endpoint<I, Outcome<E, T>>,
        answer: (I) -> Outcome<E, T>,
        round: Int,
        body: ExampleBody?,
    ): Probe {
        val salt = { at: Int -> round * SALT_STRIDE + at }
        val sentinels = endpoint.pathSpec.segments.withIndex().associate { (at, segment) ->
            at to (segment as? PathSegment.Capture)?.param?.let { sentinel(it.codec, salt(at)) }
        }
        val path = "/" + endpoint.pathSpec.segments.withIndex().joinToString("/") { (at, segment) ->
            when (segment) {
                is PathSegment.Literal -> segment.value
                is PathSegment.Capture -> checkNotNull(sentinels[at])
            }
        }
        // Supplied and varied per round, so an answer that reads one is caught rather than exported.
        val query = endpoint.queries.withIndex()
            .map { (at, q) -> q.name to sentinel(q.codec, salt(QUERY_SALT_BASE + at)) }
        val headers = endpoint.headerParams.withIndex()
            .map { (at, h) -> h.name to sentinel(h.codec, salt(HEADER_SALT_BASE + at)) }

        val varied = body?.varied { at -> salt(BODY_SALT_BASE + at) }
        val spec = RequestSpec(
            endpoint.method,
            path,
            query,
            headers + body?.headers.orEmpty(),
            varied?.first?.render(),
        )
        val captured = sentinels.mapNotNull { (at, value) -> value?.let { at to it } }.toMap()
        val reached = AtomicBoolean(false)
        val response = served(endpoint) { input -> reached.set(true); answer(input) }
            .answer(spec.asClientRequest())
        require(reached.get()) {
            "${endpoint.pathSpec.template} refused the values this export probes it with, answering " +
                "${response.status} before the stub was asked: ${response.body.take(PROBE_BODY_SHOWN)}. " +
                "The probe builds a value from each parameter's declared type, so a parameter that " +
                "narrows its type further than that needs stub(endpoint, input) instead."
        }
        return Probe(captured, varied?.second.orEmpty(), response)
    }

    private class Probe(
        private val sentinels: Map<Int, String>,
        /** JSON path within the body -> the value this round put there. */
        private val bodySentinels: Map<String, String>,
        val answer: ResponseSpec,
    ) {
        /** The answer with every value this round sent replaced by where in the request it came from. */
        fun templated(captures: List<Pair<Int, PathParam<*>>>): String {
            val fills = captures.map { (at, _) -> sentinels.getValue(at) to "{{request.pathSegments.[$at]}}" } +
                bodySentinels.map { (path, value) -> value to "{{jsonPath request.body '$path'}}" }
            // Longest first: `PelicanProbe3` is a prefix of `PelicanProbe301`, and replacing it first
            // would leave a template with `01` stuck to its end.
            return fills.sortedByDescending { it.first.length }
                .fold(answer.body) { text, (value, template) -> text.replace(value, template) }
        }
    }

    /** The example's body, and the header that says how it is encoded. */
    private class ExampleBody(private val json: JsonObj, val headers: List<Pair<String, String>>) {
        /**
         * The body with each string and number field replaced by a sentinel, and where each went.
         * Only fields named so that `$.a.b` reaches them are varied; anything else keeps the
         * example's value, and so is never templated.
         */
        fun varied(salt: (Int) -> Int): Pair<JsonValue, Map<String, String>> {
            val placed = linkedMapOf<String, String>()
            fun vary(value: JsonValue, path: String): JsonValue = when (value) {
                is JsonObj -> JsonObj(
                    value.fields.mapValues { (name, field) ->
                        if (SIMPLE_NAME.matches(name)) vary(field, "$path.$name") else field
                    },
                )

                is JsonStr -> JsonStr("PelicanProbe${salt(placed.size)}").also { placed[path] = it.value }

                is JsonNum -> JsonNum(BODY_NUMBER_BASE + salt(placed.size)).also { placed[path] = it.value.toString() }

                else -> value
            }
            return vary(json, "$") to placed
        }
    }

    /**
     * WireMock's matcher shape, which is the request side only: a response header is the value
     * itself, and wrapping one in a matcher reads back as a null header rather than as an error.
     */
    private fun equalTo(pairs: List<Pair<String, String>>): JsonObj? =
        pairs.asJsonObj { value -> jsonObj { "equalTo" to value } }

    private fun literal(pairs: List<Pair<String, String>>): JsonObj? =
        pairs.asJsonObj { value -> JsonStr(value) }

    private fun List<Pair<String, String>>.asJsonObj(value: (String) -> JsonValue): JsonObj? =
        takeIf { it.isNotEmpty() }?.let { JsonObj(it.associate { (name, v) -> name to value(v) }) }

    private fun response(answer: ResponseSpec, body: String): JsonObj = jsonObj {
        "status" to answer.status
        put(
            "headers",
            literal(answer.headers.filterNot { (name, _) -> FRAMING_HEADERS.any { it.equals(name, true) } }),
        )
        "body" to body
    }

    private class Declared(
        val root: String,
        private val request: JsonObj,
        private val response: JsonObj,
        private val priority: Int,
    ) {
        fun mapping(): JsonObj = jsonObj {
            "priority" to priority
            "request" to request
            "response" to response
        }
    }

    /** `request` never sends, and a transport that cannot is clearer than one that silently could. */
    private object UnusedTransport : Transport {
        override fun send(request: RequestSpec): ResponseSpec =
            error("stubFile builds requests and never sends them; this transport exists to say so.")
    }
}

// Round 1's values must not collide with round 0's, and a parameter's must not collide with its
// neighbour's; one stride per round over one slot per parameter keeps every sentinel its own.
private const val SALT_STRIDE = 1000
private const val QUERY_SALT_BASE = 100
private const val HEADER_SALT_BASE = 200
private const val BODY_SALT_BASE = 300

/** A body number sentinel: a whole number inside an `int32`, distinct from the path's `700000 + salt`. */
private const val BODY_NUMBER_BASE = 800000L

private const val CONTENT_TYPE = "Content-Type"

/** A field `$.a.b` can name without quoting; others are left as the example has them. */
private val SIMPLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Enough of a refused body to name the parameter, without pasting a page into an exception. */
private const val PROBE_BODY_SHOWN = 300

/**
 * A value the declared codec accepts and the answer can be searched for afterwards.
 *
 * Taken from the codec's own OpenAPI type rather than guessed at, so an integer parameter is probed
 * with a number and a string one with text. Deliberately unlikely shapes: a probe value that also
 * occurred naturally in the answer would be templated where it should have been left alone.
 */
private fun sentinel(codec: PlainCodec<*>, salt: Int): String {
    val enumerated = codec.enumValues
    return when {
        enumerated != null -> enumerated[salt % enumerated.size]

        codec.openApiFormat == "uuid" -> "pe71ca40-0000-4000-8000-%012d".format(salt)

        // Inside the declared width: an int32 parameter handed an int64 value is refused before the
        // handler runs, and the refusal — which quotes the value — reads as an answer that moved.
        codec.openApiType == "integer" && codec.openApiFormat == "int64" ->
            (70000000000000000L + salt).toString()

        codec.openApiType == "integer" -> (700000 + salt).toString()

        codec.openApiType == "number" -> "${700000 + salt}.5"

        codec.openApiType == "boolean" -> (salt % 2 == 0).toString()

        else -> "PelicanProbe$salt"
    }
}

/**
 * The first literal segment, which is the resource these stubs are about: both of a registry's
 * chip endpoints land in `chips.json`, the way a hand-written mapping directory is organised.
 */
private fun Endpoint<*, *>.pathRoot(): String =
    pathSpec.segments.filterIsInstance<PathSegment.Literal>().firstOrNull()?.value ?: "root"

private val REGEX_META = Regex("""[\\^$.|?*+()\[\]{}]""")

/** The path as WireMock matches it: literals as themselves, a capture as one segment of anything. */
private fun Endpoint<*, *>.pathPattern(): String =
    "/" + pathSpec.segments.joinToString("/") { segment ->
        when (segment) {
            is PathSegment.Literal -> REGEX_META.replace(segment.value) { "\\" + it.value }
            is PathSegment.Capture -> "[^/]+"
        }
    }

private fun InMemoryClientTransport.answer(request: ClientRequest): ResponseSpec =
    send(request).toCompletableFuture().get().let { ResponseSpec(it.status, it.headers, it.text()) }

private fun RequestSpec.asClientRequest(): ClientRequest = ClientRequest(
    method = method,
    url = "http://stub$target",
    headers = headers,
    body = body?.let { ClientRequest.Body.Text(it) } ?: ClientRequest.Body.Empty,
)
