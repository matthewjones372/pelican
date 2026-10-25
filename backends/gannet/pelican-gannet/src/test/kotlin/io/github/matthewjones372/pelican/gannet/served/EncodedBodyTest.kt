package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.gannet.RequiresUring
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.reflect.KType
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.start as jettyStart

/**
 * A codec may already hold the bytes. Null is today's path. An array is the
 * body, including when it is not what `encodeToString` would have produced.
 */
@RequiresUring
class EncodedBodyTest {

    companion object {
        private val plain = endpoint {
            get("plain")
            json<String>()
        }

        private val held = endpoint {
            get("held")
            json<String>()
        }

        private val sheet = endpoint {
            get("sheet")
            media<String>("text/csv")
        }

        private val uring = api(
            listOf(
                plain handledNow { "plain" },
                held handledNow { "held" },
                sheet handledNow { "held" },
            ),
            Holding,
        ).start(port = 0)

        private val jetty = api(
            listOf(
                plain jettyHandledNow { "plain" },
                held jettyHandledNow { "held" },
                sheet jettyHandledNow { "held" },
            ),
            Holding,
        ).jettyStart(port = 0)

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopBoth() {
            client.close()
            uring.stop()
            jetty.stop()
        }
    }

    @Test
    fun `the default returns null`() {
        val codec = object : BodyCodec<String> {
            override fun encodeToString(value: String) = value
            override fun decodeFromString(text: String) = text
        }
        codec.encoded("plain").shouldBeNull()
    }

    @Test
    fun `a json response with nothing held is the utf-8 of encodeToString`() {
        val text = Holding.strings.encodeToString("plain").toByteArray().toList()
        ask(uring.baseUrl, "/plain") shouldBe text
        ask(jetty.baseUrl, "/plain") shouldBe text
    }

    @Test
    fun `a held array is the body, and it is not written`() {
        val before = Holding.fixed.copyOf().toList()
        val text = Holding.strings.encodeToString("held").toByteArray().toList()
        text shouldNotBe before
        ask(uring.baseUrl, "/held") shouldBe before
        ask(jetty.baseUrl, "/held") shouldBe before
        ask(uring.baseUrl, "/sheet") shouldBe before
        ask(jetty.baseUrl, "/sheet") shouldBe before
        Holding.fixed.toList() shouldBe before
    }

    private fun ask(baseUrl: String, path: String): List<Byte> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        res.statusCode() shouldBe 200
        return res.body().toList()
    }
}

private object Holding : Codecs {
    val fixed = byteArrayOf(0xC3.toByte(), 0x28, 0x00, 0xFF.toByte())

    val strings: BodyCodec<String> = object : BodyCodec<String> {
        override fun encodeToString(value: String) = "text:$value"
        override fun decodeFromString(text: String) = text.removePrefix("text:")
        override fun encoded(value: String): ByteArray? = if (value == "held") fixed else null
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> codec(type: KType): BodyCodec<T> = strings as BodyCodec<T>

    override fun <T> codec(type: KType, mediaType: String): BodyCodec<T> = codec(type)

    override fun schema(type: KType, components: SchemaComponents): JsonObj = jsonObj { "type" to "string" }
}
