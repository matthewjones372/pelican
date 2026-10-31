package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.component.LifeCycle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CancellationException
import kotlin.reflect.KType

class OwnedNativeServerTest {
    private val server = OwnedServer()

    @AfterEach
    fun closeTestOwnedServer() {
        server.stopFailure = null
        server.stop()
    }

    @ParameterizedTest
    @ValueSource(strings = ["codec", "decorator", "interrupt", "cancel"])
    fun `preparation failures preserve identity without binding`(kind: String) {
        val failure = ownerFailure(kind)
        val codecs = if (kind == "codec") object : Codecs by JacksonCodecs {
            override fun <T> codec(type: KType): BodyCodec<T> = throw failure
        } else JacksonCodecs
        var decorations = 0
        shouldThrow<Throwable> {
            ownedApi(codecs).ownedNativeServer(server, 0, "127.0.0.1", Handlers.default) {
                decorations++
                throw failure
            }
        } shouldBeSameInstanceAs failure
        decorations shouldBe if (kind == "codec") 0 else 1
        server.isStopped shouldBe true
        (server.threadPool as LifeCycle).isStopped shouldBe true
        server.connectors.all { !(it as ServerConnector).isOpen } shouldBe true
        server.stops shouldBe 0
    }

    @ParameterizedTest
    @ValueSource(strings = ["plain", "distinct", "same", "interrupt", "cancel"])
    fun `failed start closes actual resources and preserves the primary failure`(kind: String) {
        val failure = ownerFailure(kind)
        server.startFailure = failure
        server.stopFailure = when (kind) {
            "distinct" -> AssertionError("cleanup")
            "same" -> failure
            else -> null
        }
        shouldThrow<Throwable> {
            ownedApi().ownedNativeServer(server, 0, "127.0.0.1", Handlers.default) { it }
        } shouldBeSameInstanceAs failure
        server.openedPort shouldBeGreaterThan 0
        (server.connectors.single() as ServerConnector).isOpen shouldBe false
        (server.threadPool as LifeCycle).isStopped shouldBe true
        server.stops shouldBe 1
        server.isStopped shouldBe (server.stopFailure == null)
        failure.suppressed.toList() shouldBe if (kind == "distinct") listOf(server.stopFailure) else emptyList()
    }

    @Test
    fun `successful assembly retains the native handle and its stop ownership`() {
        val service = ownedApi()
        var decorations = 0
        val handle = service.ownedNativeServer(server, 0, "127.0.0.1", Handlers.default) {
            decorations++
            it
        }
        decorations shouldBe 1
        handle.api shouldBeSameInstanceAs service
        handle.server shouldBeSameInstanceAs server
        handle.host shouldBe "127.0.0.1"
        handle.port shouldBe server.openedPort
        handle.port shouldBeGreaterThan 0
        handle.baseUrl shouldBe "http://127.0.0.1:${handle.port}"
        handle.close()
        server.isStopped shouldBe true
        server.stops shouldBe 1
    }
}

private fun ownedApi(codecs: Codecs = JacksonCodecs) = api(
    listOf(
        endpoint {
            get("owner")
            json<String>()
        } handledNow { "ok" },
    ),
    codecs,
)

private fun ownerFailure(kind: String): Throwable = when (kind) {
    "interrupt" -> InterruptedException("owner")
    "cancel" -> CancellationException("owner")
    else -> AssertionError("owner")
}

private class OwnedServer : Server(pelicanPool(Handlers.default)) {
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null
    var openedPort = -1
    var stops = 0

    override fun doStart() {
        super.doStart()
        openedPort = (connectors.single() as ServerConnector).localPort
        startFailure?.let { throw it }
    }

    override fun doStop() {
        super.doStop()
        stops++
        stopFailure?.let { throw it }
    }
}
