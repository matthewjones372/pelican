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
