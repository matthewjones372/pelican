package io.github.matthewjones372.pelican.client

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/**
 * What a caller who gives up gets: the exchange stopped, not left running.
 *
 * A cancelled coroutine cancels the `CompletableFuture` it was awaiting, and a
 * `CompletableFuture` derived from another does not pass a cancellation back to
 * the one it came from — so the adapter has to carry it back by hand, and this
 * is the assertion that it does. Stated against a stand-in `HttpClient` rather
 * than a socket because the claim is about which future is cancelled, and a
 * server can only be asked afterwards what it thinks happened.
 */
class CancellationTest {

    @Test
    fun `cancelling the stage cancels the exchange it was derived from`() {
        val exchange = CompletableFuture<HttpResponse<Any>>()
        val transport = JavaHttpTransport(NeverAnswering(exchange))

        val answer = transport.send(ClientRequest(Method.GET, "https://orders.internal/orders/1"))
            .toCompletableFuture()

        answer.cancel(true) shouldBe true
        exchange.isCancelled shouldBe true
    }

    /**
     * An `HttpClient` that accepts a request and never answers, handing out the
     * future the test holds so that it can be asked whether anybody cancelled
     * it. Everything else is what the JDK's own abstract class requires.
     */
    private class NeverAnswering(private val exchange: CompletableFuture<HttpResponse<Any>>) : HttpClient() {

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> sendAsync(
            request: HttpRequest?,
            responseBodyHandler: HttpResponse.BodyHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = exchange as CompletableFuture<HttpResponse<T>>

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> sendAsync(
            request: HttpRequest?,
            responseBodyHandler: HttpResponse.BodyHandler<T>?,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = exchange as CompletableFuture<HttpResponse<T>>

        override fun <T : Any?> send(
            request: HttpRequest?,
            responseBodyHandler: HttpResponse.BodyHandler<T>?,
        ): HttpResponse<T> = error("This client only answers asynchronously, which is all the adapter asks of it.")

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()
    }
}
