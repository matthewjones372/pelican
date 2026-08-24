package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.Codecs
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Talks to a real server over a real socket, with the JDK's own client.
 *
 * The point of this existing alongside [InMemoryTransport] is that the *same*
 * suite runs on both. Assertions are written once against [ApiClient]; which
 * transport is underneath decides whether a test is a fast unit-level check or
 * a genuine integration test — including against something already deployed,
 * which the in-memory transport by definition cannot reach.
 */
class HttpClientTransport(
    baseUrl: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
    private val timeout: Duration = Duration.ofSeconds(30),
) : Transport {

    private val base = baseUrl.trimEnd('/')

    override fun send(request: RequestSpec): ResponseSpec {
        val builder = HttpRequest.newBuilder(URI.create(base + request.target))
            .timeout(timeout)
            .method(
                request.method.name,
                request.body
                    ?.let { HttpRequest.BodyPublishers.ofString(it) }
                    ?: HttpRequest.BodyPublishers.noBody(),
            )

        if (request.body != null && request.headers.none { it.first.equals("Content-Type", true) }) {
            builder.header("Content-Type", "application/json")
        }
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        val res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val headers = res.headers().map().flatMap { (name, values) -> values.map { name to it } }
        return ResponseSpec(res.statusCode(), headers, res.body())
    }
}

/**
 * A client for an API already running somewhere. [codecs] must be the ones the
 * server was built with — they decode the responses.
 */
fun apiClient(baseUrl: String, codecs: Codecs): ApiClient =
    ApiClient(HttpClientTransport(baseUrl), codecs)
