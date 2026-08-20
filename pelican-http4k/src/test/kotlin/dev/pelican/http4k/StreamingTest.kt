package dev.pelican.http4k

import dev.pelican.Api
import dev.pelican.jackson.JacksonCodecs
import org.http4k.core.Method
import org.http4k.core.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A streamed endpoint must not assemble its stream before answering.
 *
 * On this backend that reduces to one question — is the handler's sequence
 * pulled as the response body is read, or before it? — and that question can
 * be asked without a socket, a clock or a thread. The handler counts what it
 * has produced; the test reads one frame and looks at the count.
 */
class StreamingTest {

    private fun handlerCounting(produced: AtomicInteger) = Api(
        endpoints = listOf(
            streamItems streamedNow { max ->
                (1..max).asSequence().map { i ->
                    produced.incrementAndGet()
                    Item(i.toLong(), "item-$i")
                }
            },
        ),
        codecs = JacksonCodecs,
    ).toHttpHandler()

    @Test
    fun `no element is produced before the body is read`() {
        val produced = AtomicInteger()
        val response = handlerCounting(produced)(Request(Method.GET, "/items/stream?limit=5"))

        assertEquals(0, produced.get(), "the handler ran, but nothing should have been produced yet")

        val stream = response.body.stream
        val first = readFrame(stream)

        assertEquals("""{"id":1,"name":"item-1"}""" + "\n", first)
        assertEquals(1, produced.get(), "reading one frame should have produced exactly one element")

        readFrame(stream)
        assertEquals(2, produced.get())
    }

    @Test
    fun `the whole stream still arrives when it is read to the end`() {
        val produced = AtomicInteger()
        val response = handlerCounting(produced)(Request(Method.GET, "/items/stream?limit=5"))

        assertEquals(5, response.bodyString().lines().count { it.isNotBlank() })
        assertEquals(5, produced.get())
    }

    /**
     * One read, one frame. The buffer is deliberately far larger than a frame:
     * a stream that filled it would be pulling elements nobody has asked for.
     */
    private fun readFrame(stream: java.io.InputStream): String {
        val buffer = ByteArray(8192)
        val n = stream.read(buffer)
        return String(buffer, 0, n)
    }
}
