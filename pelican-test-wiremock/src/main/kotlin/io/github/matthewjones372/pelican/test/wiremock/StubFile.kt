package io.github.matthewjones372.pelican.test.wiremock

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.InMemoryClientTransport
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.PathSegment
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jsonArr
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.renderPretty
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
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

/**
 * The stubs a demo needs, written in endpoints and exported as WireMock mapping files.
 *
 * The point is that the answer is written once. [PelicanWireMock] renders a stub's answer through
 * the codecs a server would use; so does this, so a `404` carries the `Problem` its contract
 * declares rather than whatever a hand-written file happened to say. See spec 0062.
 *
 * ```kotlin
 * stubFile(JacksonCodecs) {
 *     stub(lookupChip, In3(3L, "eu", 1)) answers noSuchChip(Problem("never chipped"))
 * }.writeTo(Path.of("demo/registry/mappings"))
 * ```
 */
fun stubFile(codecs: Codecs, declare: StubFile.() -> Unit): StubFile =
    StubFile(codecs).apply(declare)

/** A set of stubs with fixed inputs, ready to be written. Build one with [stubFile]. */
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

    /** One stub, waiting to be told what it answers with. */
    inner class Stubbing<I, E : Any, T : Any> internal constructor(
        private val endpoint: Endpoint<I, Outcome<E, T>>,
        private val input: I,
    ) {
        /** `ok(value)` or a failure the endpoint declares; nothing else compiles. */
        infix fun answers(outcome: Outcome<E, T>) {
            val request = requests.request(endpoint, input)
            declared += Declared(endpoint.pathRoot(), request, render(endpoint, request, outcome))
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
     * Rendered by the code a server answers with, so the status, content type and body are the ones
     * the contract's own service would send.
     */
    private fun <I, E : Any, T : Any> render(
        endpoint: Endpoint<I, Outcome<E, T>>,
        request: RequestSpec,
        outcome: Outcome<E, T>,
    ): ResponseSpec {
        val served = InMemoryClientTransport(
            api(
                listOf(ServerEndpoint(endpoint) { CompletableFuture.completedFuture(outcome) }),
                codecs,
            ),
        )
        val response = served.send(request.asClientRequest()).toCompletableFuture().get()
        return ResponseSpec(response.status, response.headers, response.text())
    }

    private class Declared(val root: String, val request: RequestSpec, val answer: ResponseSpec) {

        fun mapping(): JsonObj = jsonObj {
            "request" to request()
            "response" to response()
        }

        private fun request(): JsonObj = jsonObj {
            "method" to request.method.name
            "urlPath" to request.path
            put("queryParameters", equalTo(request.query))
            put("headers", equalTo(request.headers))
            request.body?.let { "bodyPatterns" to jsonArr(listOf(jsonObj { "equalToJson" to it })) }
        }

        private fun response(): JsonObj = jsonObj {
            "status" to answer.status
            put(
                "headers",
                literal(answer.headers.filterNot { (name, _) -> FRAMING_HEADERS.any { it.equals(name, true) } }),
            )
            "body" to answer.body
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
    }

    /** `request` never sends, and a transport that cannot is clearer than one that silently could. */
    private object UnusedTransport : Transport {
        override fun send(request: RequestSpec): ResponseSpec =
            error("stubFile builds requests and never sends them; this transport exists to say so.")
    }
}

/**
 * The first literal segment, which is the resource these stubs are about: both of a registry's
 * chip endpoints land in `chips.json`, the way a hand-written mapping directory is organised.
 */
private fun Endpoint<*, *>.pathRoot(): String =
    pathSpec.segments.filterIsInstance<PathSegment.Literal>().firstOrNull()?.value ?: "root"

private fun RequestSpec.asClientRequest(): ClientRequest = ClientRequest(
    method = method,
    url = "http://stub$target",
    headers = headers,
    body = body?.let { ClientRequest.Body.Text(it) } ?: ClientRequest.Body.Empty,
)
