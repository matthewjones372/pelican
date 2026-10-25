package io.github.matthewjones372.pelican.uring.served

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** What a caller can see of an answer: its status, the headers a test names, and its body. */
data class Answer(val status: Int, val headers: Map<String, String?>, val body: String)

/** Asks any server a question the same way, reporting only the headers named in [seen]. */
class Asking(private val seen: List<String>) : AutoCloseable {
    private val client: HttpClient = HttpClient.newHttpClient()

    fun ask(
        baseUrl: String,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Answer {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).method(method, publisher)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Answer(res.statusCode(), seen.associateWith { res.headers().firstValue(it).orElse(null) }, res.body())
    }

    override fun close() = client.close()
}
