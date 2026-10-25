package example.backends

import io.github.matthewjones372.pelican.ApiErrorEnvelope
import io.github.matthewjones372.pelican.ProblemDetails
import io.github.matthewjones372.pelican.RefusalRenderer
import io.github.matthewjones372.pelican.test.ResponseSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One shipped refusal envelope, and how a test reads a body written in it.
 *
 * A suite parameterised over these asks the same question of both dialects
 * without knowing either one's field names, which is the point: what the
 * refusal *says* — the status, the sentence, the detail — is the agreement, and
 * how it is spelled is the configuration.
 */
class Dialect(
    val name: String,
    val renderer: RefusalRenderer,
    val mediaType: String,
    private val statusField: String,
    private val reasonField: String,
) {
    fun status(response: ResponseSpec): Int = field(response, statusField).toInt()

    fun reason(response: ResponseSpec): String = field(response, reasonField)

    /** Present in both, under the same name, and the one field neither renames. */
    fun detail(response: ResponseSpec): String? =
        Json.parseToJsonElement(response.body).jsonObject["detail"]?.jsonPrimitive?.content

    /**
     * Everything the refusal says in words. Which half names the parameter is a
     * property of the refusal — a missing header has no detail beyond its
     * sentence, a value that would not decode has both — and not of the dialect,
     * so a test asking whether the caller was told reads them together.
     */
    fun says(response: ResponseSpec): String = reason(response) + " " + detail(response).orEmpty()

    private fun field(response: ResponseSpec, name: String): String =
        Json.parseToJsonElement(response.body).jsonObject.getValue(name).jsonPrimitive.content

    override fun toString(): String = name
}

/** Every dialect core ships. A fourth is one entry here and no new assertions. */
val allDialects: List<Dialect> = listOf(
    Dialect("envelope", ApiErrorEnvelope, "application/json", statusField = "status", reasonField = "error"),
    Dialect("problem", ProblemDetails, "application/problem+json", statusField = "status", reasonField = "title"),
)

/**
 * Where Jetty answers otherwise than Pekko, and why that is accepted rather than closed. Everything not listed
 * here is held identical by the suites in this package; `RecordedDifferencesTest` holds all but the stalled
 * upload true on the wire, so a change on either server fails there before it reaches a reader.
 */
enum class JettyDifference(val reason: String) {
    /**
     * `/%2E%2E/x`, `/hello/%2E%2E/%2e%2e/x`, `//x` and a raw `/../x` are Jetty's own HTML 400; Pekko answers 404.
     */
    ESCAPING_PATHS(
        "Jetty's URI parser refuses a path that climbs above the root, or starts with an empty segment, before any " +
            "handler runs, and no UriCompliance setting lifts it. Both refuse: nothing is served from outside.",
    ),

    /** A stalled NDJSON upload is a 500 after five seconds on Jetty; Pekko waits for its idle timeout. */
    STALLED_NDJSON_UPLOAD(
        "Jetty reads frames with a blocking reader, which needs a read timeout of its own; it uses the five " +
            "seconds Pekko's asInputStream applies to multipart. Pekko reads NDJSON as a Source, which has none.",
    ),

    /** A page asked for with `Range` is the whole file, 200, on Jetty; Pekko answers 206 with the range. */
    NO_RANGES_ON_PAGES(
        "Pages are small static assets; serving a slice of one is a resume feature nobody asked of Pelican. " +
            "A client that asks for a range and gets 200 takes the whole body, as RFC 9110 allows.",
    ),
}

/**
 * Where Gannet answers otherwise than Jetty's, which it was copied from, and why. Everything
 * [JettyDifference] lists that is not here, it answers as Jetty does. `RecordedDifferencesTest` holds these true.
 */
enum class GannetDifference(val reason: String) {
    /** The paths of [JettyDifference.ESCAPING_PATHS] are a 404, as on Pekko, not Jetty's 400. */
    ESCAPING_PATHS(
        "The runtime hands the interpreter the path as it arrived, and nothing between them refuses one. A path " +
            "that climbs above the root, or starts with an empty segment, is routed nowhere, which is Pekko's answer.",
    ),
}
