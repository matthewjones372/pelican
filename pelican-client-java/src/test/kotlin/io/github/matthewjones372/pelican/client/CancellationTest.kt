package io.github.matthewjones372.pelican.client

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/**
 * Which future a cancellation reaches, asked of a stand-in `HttpClient` because
 * a server can only be asked afterwards what it thinks happened.
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

    @Test
    fun `an exchange cancelled by anyone but the caller crosses as an IOException`() {
        val exchange = CompletableFuture<HttpResponse<Any>>()
        val answer = JavaHttpTransport(NeverAnswering(exchange))
            .send(ClientRequest(Method.GET, "https://orders.internal/orders/1"))
            .toCompletableFuture()

        exchange.cancel(true)

        val failure = shouldThrow<ExecutionException> { answer.get() }
        failure.cause.shouldBeInstanceOf<IOException>().cause.shouldBeInstanceOf<CancellationException>()
    }

    /** Answers with the future the test holds; the rest is what `HttpClient` requires. */
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
