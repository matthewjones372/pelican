package io.github.matthewjones372.pelican.uring.served

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.lastEventId
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.uring.RequiresUring
import io.github.matthewjones372.pelican.uring.start
import io.github.matthewjones372.pelican.uring.streamedBy
import io.github.matthewjones372.pelican.uring.streamedByOrFail
import io.github.matthewjones372.pelican.uring.streamedNow
import io.github.matthewjones372.pelican.uring.streamedOrFail
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.github.matthewjones372.pelican.jetty.start as jettyStart
import io.github.matthewjones372.pelican.jetty.streamedBy as jettyStreamedBy
import io.github.matthewjones372.pelican.jetty.streamedByOrFail as jettyStreamedByOrFail
import io.github.matthewjones372.pelican.jetty.streamedNow as jettyStreamedNow
import io.github.matthewjones372.pelican.jetty.streamedOrFail as jettyStreamedOrFail

/** Streamed responses on both servers, compared on the bytes a caller reads. */
@RequiresUring
class StreamedAsJettyStreamsTest {

    data class Tick(val seq: Int, val at: String)

    data class Gone(val reason: String)

    data class Seen(val status: Int, val contentType: String?, val body: String)

    companion object {
        private val ticks = listOf(Tick(1, "one"), Tick(2, "two"))

        private val countdown = endpoint {
            get("countdown")
            ndjson<Tick>()
        }

        private val everyone = endpoint {
            get("everyone")
            jsonArray<Tick>()
        }

        private val nobody = endpoint {
            get("nobody")
            jsonArray<Tick>()
        }

        private val ticker = endpoint {
            get("ticker")
            sse<Tick>(eventName = "tick")
        }

        private val replay = endpoint {
            get("replay")
            sse<Tick>(eventName = "tick", id = { it.seq.toString() }, retry = 15.seconds)
        }

        private val quiet = endpoint {
            get("quiet")
            sse<Tick>(keepAlive = 50.milliseconds)
        }

        private val gone = queryParam<Boolean>("gone")

        private val expired = errorJson<Gone>(410, "The stream has expired")

        private val maybe = endpoint(gone) {
            get("maybe")
            ndjson<Tick>() orFail expired
        }

        private val later = endpoint(gone) {
            get("later")
            sse<Tick>() orFail expired
        }

        private val broken = endpoint {
            get("broken")
            ndjson<Tick>()
        }

        private val halfway = endpoint {
            get("halfway")
            ndjson<Tick>()
        }

        /** Longer than several keep-alive intervals, so a missing keep-alive cannot pass for a slow one. */
        private fun quietly() = Thread.sleep(QUIET_MILLIS)

        private fun after(id: String?) = ticks.filter { it.seq > (id?.toInt() ?: 0) }

        private fun served(endpoints: List<ServerEndpoint>) = api(endpoints, JacksonCodecs)

        private val uring = served(
            listOf(
                countdown streamedNow { ticks.asSequence() },
                everyone streamedBy { CompletableFuture.completedFuture(ticks.asSequence()) },
                nobody streamedNow { emptySequence() },
                ticker streamedNow { ticks.asSequence() },
                replay streamedNow { after(lastEventId()).asSequence() },
                quiet streamedNow {
                    sequence {
                        yield(Tick(1, "one"))
                        quietly()
                        yield(Tick(2, "two"))
                    }
                },
                maybe streamedOrFail { isGone ->
                    if (isGone) expired(Gone("expired")) else ok(ticks.asSequence())
                },
                later streamedByOrFail { isGone ->
                    CompletableFuture.completedFuture(
                        if (isGone) expired(Gone("expired")) else ok(ticks.asSequence()),
                    )
                },
                broken streamedNow {
                    sequence<Tick> {
                        failure.get()
                        error("broken")
                    }
                },
                halfway streamedNow {
                    sequence {
                        yield(ticks.first())
                        failure.get()
                        error("halfway")
                    }
                },
            ),
        ).start(port = 0)

        private val jetty = served(
            listOf(
                countdown jettyStreamedNow { ticks.asSequence() },
                everyone jettyStreamedBy { CompletableFuture.completedFuture(ticks.asSequence()) },
                nobody jettyStreamedNow { emptySequence() },
                ticker jettyStreamedNow { ticks.asSequence() },
                replay jettyStreamedNow { after(lastEventId()).asSequence() },
                quiet jettyStreamedNow {
                    sequence {
                        yield(Tick(1, "one"))
                        quietly()
                        yield(Tick(2, "two"))
                    }
                },
                maybe jettyStreamedOrFail { isGone ->
                    if (isGone) expired(Gone("expired")) else ok(ticks.asSequence())
                },
                later jettyStreamedByOrFail { isGone ->
                    CompletableFuture.completedFuture(
                        if (isGone) expired(Gone("expired")) else ok(ticks.asSequence()),
                    )
                },
                broken jettyStreamedNow {
                    sequence<Tick> {
                        failure.get()
                        error("broken")
                    }
                },
                halfway jettyStreamedNow {
                    sequence {
                        yield(ticks.first())
                        failure.get()
                        error("halfway")
                    }
                },
            ),
        ).jettyStart(port = 0)

        /** Released once the caller has read what came before the failure, so a reset cannot discard it unread. */
        @Volatile
        private var failure = CompletableFuture<Unit>()

        private val client: HttpClient = HttpClient.newHttpClient()

        private const val QUIET_MILLIS = 600L

        @JvmStatic
        @AfterAll
        fun stopBoth() {
            client.close()
            uring.stop()
            jetty.stop()
        }
    }

    private fun get(baseUrl: String, path: String, lastEventId: String? = null): Seen {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
            .build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Seen(res.statusCode(), res.headers().firstValue("Content-Type").orElse(null), res.body())
    }

    /**
     * Everything the server sent before it ended or dropped the connection, read off a raw socket. The stream's
     * failure is released once [before] has been read.
     */
    private fun raw(baseUrl: String, path: String, before: String): String {
        failure = CompletableFuture()
        val uri = URI.create(baseUrl)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: ${uri.host}\r\n\r\n".toByteArray())
            val seen = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            try {
                while (true) {
                    val read = socket.getInputStream().read(buffer)
                    if (read < 0) break
                    seen.write(buffer, 0, read)
                    if (before in seen.toString(Charsets.UTF_8)) failure.complete(Unit)
                }
            } catch (ignored: IOException) {
                // A reset is how both servers abandon a stream that failed.
            }
            seen.toString(Charsets.UTF_8)
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "/countdown,",
        "/everyone,",
        "/nobody,",
        "/ticker,",
        "/replay,",
        "/replay, 1",
        "/maybe?gone=false,",
        "/maybe?gone=true,",
        "/later?gone=false,",
        "/later?gone=true,",
    )
    fun `uring streams as jetty does`(path: String, lastEventId: String?) {
        withClue("$path $lastEventId") {
            get(uring.baseUrl, path, lastEventId) shouldBe get(jetty.baseUrl, path, lastEventId)
        }
    }

    @Test
    fun `sse frames are core's, with the retry ahead of the first event`() {
        get(uring.baseUrl, "/replay").body shouldBe
            "retry: 15000\n\n" +
            "event: tick\nid: 1\ndata: {\"seq\":1,\"at\":\"one\"}\n\n" +
            "event: tick\nid: 2\ndata: {\"seq\":2,\"at\":\"two\"}\n\n"
    }

    @Test
    fun `a reconnect carries the last id it saw to the handler`() {
        get(uring.baseUrl, "/replay", lastEventId = "1").body shouldBe
            "retry: 15000\n\nevent: tick\nid: 2\ndata: {\"seq\":2,\"at\":\"two\"}\n\n"
    }

    @Test
    fun `an empty array is still an array`() {
        get(uring.baseUrl, "/nobody") shouldBe Seen(200, "application/json", "[]")
    }

    @Test
    fun `a quiet sse stream is filled with comments`() {
        val body = get(uring.baseUrl, "/quiet").body

        withClue(body) {
            body.windowed(6).count { it.startsWith("data: ") } shouldBe 2
            body.windowed(3).count { it == ":\n\n" } shouldBeGreaterThanOrEqual 1
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["/broken", "/halfway"])
    fun `a stream that fails has already said 200, so the connection is dropped unfinished`(path: String) {
        // The end of the headers, or the one frame sent before the failure.
        val before = if (path == "/broken") "\r\n\r\n" else """"at":"one"}"""
        listOf(uring.baseUrl, jetty.baseUrl).forEach { baseUrl ->
            val seen = raw(baseUrl, path, before)
            withClue("$baseUrl$path: $seen") {
                seen shouldStartWith "HTTP/1.1 200"
                seen shouldContain "Transfer-Encoding: chunked"
                seen shouldNotEndWith "0\r\n\r\n"
            }
        }
    }
}

private const val SOCKET_TIMEOUT_MILLIS = 5_000
private const val BUFFER_BYTES = 4096
