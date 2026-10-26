package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.BodySink
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.Handlers
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.queryParam
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.reflect.KType
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.start as jettyStart

/**
 * Spec 0090: on the carrier a `json<T>()` success is encoded straight into Gannet's buffer. A codec that throws
 * partway is still answered as Jetty answers it, wherever the handler ran, on a connection that is still usable.
 */
@RequiresGannet
class EncodedIntoGannetTest {

    data class Fragile(val text: String)

    /** Jackson, but a [Fragile] saying "break" writes half of itself and throws, as a broken serializer would. */
    private object Breaking : Codecs by JacksonCodecs {
        override fun <T> codec(type: KType, mediaType: String): BodyCodec<T> = codec(type)

        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: KType): BodyCodec<T> {
            val inner = JacksonCodecs.codec<Fragile>(type)
            return object : BodyCodec<Fragile> {
                override fun encodeToString(value: Fragile): String =
                    if (value.text == "break") error("the serializer broke") else inner.encodeToString(value)

                override fun decodeFromString(text: String): Fragile = inner.decodeFromString(text)

                override fun encodeTo(value: Fragile, sink: BodySink) {
                    if (value.text != "break") return inner.encodeTo(value, sink)
                    sink.write("{\"text\":".toByteArray(), 0, HALF)
                    error("the serializer broke")
                }
            } as BodyCodec<T>
        }
    }

    companion object {
        private const val HALF = 8

        private val fragile = endpoint {
            get("fragile")
            json<Fragile>()
        }

        private val word = queryParam<String>("text")
        private val said = endpoint(word) {
            get("said")
            json<Fragile>()
        }

        private val gannetRoutes: List<ServerEndpoint> = listOf(
            fragile handledNow { Fragile("break") },
            said handledNow { text -> Fragile(text) },
        )

        private val gannet = mapOf(
            "dispatcher" to api(gannetRoutes, Breaking).start(port = 0, handlers = Handlers.onDispatcher),
            "virtual" to api(gannetRoutes, Breaking).start(port = 0),
            "non-blocking" to api(gannetRoutes.map { it.nonBlocking() }, Breaking).start(port = 0),
        )

        private val jetty = api(
            listOf(fragile jettyHandledNow { Fragile("break") }, said jettyHandledNow { text -> Fragile(text) }),
            Breaking,
        ).jettyStart(port = 0)

        private val asking = Asking(listOf("Content-Type"))

        @JvmStatic
        @AfterAll
        fun stop() {
            asking.close()
            gannet.values.forEach { it.stop() }
            jetty.stop()
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["dispatcher", "virtual", "non-blocking"])
    fun `a codec that throws partway is answered as jetty answers it, and the connection lives`(handlers: String) {
        val base = gannet.getValue(handlers).baseUrl
        val onGannet = asking.ask(base, "GET", "/fragile")
        val onJetty = asking.ask(jetty.baseUrl, "GET", "/fragile")
        withClue("gannet $onGannet, jetty $onJetty") {
            onGannet.status shouldBe 500
            onGannet.headers shouldBe onJetty.headers
            onGannet.body.contains("text") shouldBe false
        }
        asking.ask(base, "GET", "/said?text=caf%C3%A9") shouldBe
            asking.ask(jetty.baseUrl, "GET", "/said?text=caf%C3%A9")
        asking.ask(base, "GET", "/said?text=fine").body shouldStartWith """{"text":"fine"}"""
    }
}
