package io.github.matthewjones372.pelican.health

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

// One client for every check: each owns a selector thread and a connection
// pool, and a client per probe would hold a fresh connection per request.
private val client: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

/** Passes when a `GET` of [url] answers 2xx within [timeout]; another service's ready probe, say. */
fun http(url: String, timeout: Duration = 1.seconds): Status {
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout.toJavaDuration()).GET().build()
    return try {
        val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        if (status in OK) Status.Pass else Status.Fail("GET $url answered $status")
    } catch (e: IOException) {
        Status.Fail("GET $url: ${e::class.java.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
    }
}

private val OK = 200..299
