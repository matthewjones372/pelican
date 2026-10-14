package io.github.matthewjones372.pelican.importer

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

internal class Stub : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val answers = mutableMapOf<String, Answer>()

    val origin: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val answer = answers[exchange.requestURI.path] ?: Answer(NOT_FOUND, "", "text/plain")
            answer.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.responseHeaders.add("Content-Type", answer.contentType)
            val body = answer.body.toByteArray()
            exchange.sendResponseHeaders(answer.status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun url(path: String) = "$origin$path"

    /** A document at [path], as the far end is serving it *today*. */
    fun serving(path: String, body: String, contentType: String = "application/yaml") = apply {
        answers[path] = Answer(OK, body.trimIndent(), contentType)
    }

    fun answering(path: String, status: Int, headers: Map<String, String> = emptyMap()) = apply {
        answers[path] = Answer(status, "", "text/plain", headers)
    }

    override fun close() = server.stop(0)

    private class Answer(
        val status: Int,
        val body: String,
        val contentType: String,
        val headers: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
    }
}
