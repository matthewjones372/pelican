package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.junit.jupiter.api.Test

class ConnectorSettingsTest {
    private val hello = endpoint {
        get("hello")
        text()
    }

    private fun connector(settings: String) = pelicanConnector(Server(), ConnectorSettings.parse(settings))

    private fun http(settings: String): HttpConfiguration =
        connector(settings).getConnectionFactory(HttpConnectionFactory::class.java).httpConfiguration

    @Test
    fun `no settings keep jetty's defaults`() {
        val jetty = HttpConfiguration()
        val jettys = ServerConnector(Server(), HttpConnectionFactory())
        val connector = connector("")
        val http = http("")
        http.outputBufferSize shouldBe jetty.outputBufferSize
        http.outputAggregationSize shouldBe jetty.outputAggregationSize
        http.sendDateHeader shouldBe true
        http.sendServerVersion shouldBe true
        http.uriCompliance shouldBe PELICAN_URI_COMPLIANCE
        connector.acceptors shouldBe jettys.acceptors
        connector.selectorManager.selectorCount shouldBe jettys.selectorManager.selectorCount
    }

    @Test
    fun `each setting reaches the connector`() {
        val connector = connector(
            "outputBufferSize=65536, outputAggregationSize=4096, inputBufferSize=16384, acceptors=2, selectors=3",
        )
        val http = connector.getConnectionFactory(HttpConnectionFactory::class.java).httpConfiguration
        http.outputBufferSize shouldBe 65_536
        http.outputAggregationSize shouldBe 4_096
        connector.getConnectionFactory(HttpConnectionFactory::class.java).inputBufferSize shouldBe 16_384
        connector.acceptors shouldBe 2
        connector.selectorManager.selectorCount shouldBe 3
    }

    @Test
    fun `an unknown key or a bare word is refused`() {
        shouldThrow<IllegalArgumentException> { ConnectorSettings.parse("outputBuferSize=1024") }
        shouldThrow<IllegalArgumentException> { ConnectorSettings.parse("selectors") }
    }

    /** The headers named [Answer.headers] on a GET to a server with [settings]. */
    private fun headersWith(settings: String): Map<String, String?> {
        val server = Server()
        val connector = pelicanConnector(server, ConnectorSettings.parse(settings)).also { it.port = 0 }
        server.addConnector(connector)
        server.handler = api(listOf(hello handledNow { "hi" }), JacksonCodecs).toHandler(Handlers.default)
        server.start()
        try {
            return Asking(listOf("Date", "Server")).use { asking ->
                asking.ask("http://127.0.0.1:${connector.localPort}", "GET", "/hello").headers
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `with the date and server headers off a response carries neither`() {
        val headers = headersWith("sendDateHeader=false,sendServerVersion=false")
        headers["Date"].shouldBeNull()
        headers["Server"].shouldBeNull()
    }

    @Test
    fun `by default a response carries the date and server headers`() {
        val headers = headersWith("")
        headers["Date"].shouldNotBeNull()
        headers["Server"].shouldNotBeNull()
    }
}
