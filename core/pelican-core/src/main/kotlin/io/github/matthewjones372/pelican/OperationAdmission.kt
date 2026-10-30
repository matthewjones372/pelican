package io.github.matthewjones372.pelican

import java.util.Collections

/** Decides whether a library-selected operation may proceed before application decoding or authentication. */
fun interface OperationAdmission {
    fun check(request: AdmissionRequest): AdmissionDecision
}

/** Only native headers and the selected description are available before access checks. */
class AdmissionRequest internal constructor(
    val endpoint: Endpoint<*, *>,
    private val fieldLines: (String) -> List<String>,
) {
    /** The first field line; authority checks must use [headers] to detect ambiguous values. */
    fun header(name: String): String? = headers(name).firstOrNull()

    /** An immutable snapshot of every field line, using the native case-insensitive lookup. */
    fun headers(name: String): List<String> = immutableCopy(fieldLines(name))
}

sealed interface AdmissionDecision {
    data object Continue : AdmissionDecision

    class Refuse(
        val status: Int,
        val body: RefusalBody? = null,
        headers: List<Pair<String, String>> = emptyList(),
    ) : AdmissionDecision {
        val headers: List<Pair<String, String>> = immutableCopy(headers)

        init {
            require(status in FIRST_FAILURE..LAST_FAILURE) { "Admission refusal requires a 4xx or 5xx status" }
            this.headers.forEach { (name, value) ->
                require(FIELD_NAME.matches(name) && !isServerOwned(name)) { "Invalid admission refusal header name" }
                require(value.all { it == '\t' || (it >= ' ' && it != '\u007f') }) {
                    "Invalid admission refusal header value"
                }
            }
        }
    }
}

private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

private const val FIRST_FAILURE = 400
private const val LAST_FAILURE = 599
private val FIELD_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
