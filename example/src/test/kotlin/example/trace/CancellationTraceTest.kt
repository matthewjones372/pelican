package example.trace

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The instrumentation spec 0052 entry one rests on. A trace that does not fire,
 * or fires without frames, would leave the next CI failure as uninformative as
 * the four before it.
 */
class CancellationTraceTest {

    private val stageWasCompleted: Throwable =
        Class.forName("org.apache.pekko.stream.SubscriptionWithCancelException\$StageWasCompleted\$")
            .getField("MODULE\$")
            .get(null) as Throwable

    private fun throwingOnRead(failure: Throwable): InputStream = object : InputStream() {
        override fun read(): Int = throw failure
    }

    @Test
    fun `a cancellation while reading is reported with the reader's frames`() {
        val seen = mutableListOf<String>()
        val body = watched("body GET /orders", throwingOnRead(stageWasCompleted)) { seen += it }

        runCatching { body.read() }

        seen.size shouldBe 1
        seen.single() shouldContain "0052-TRACE body GET /orders"
        seen.single() shouldContain "StageWasCompleted"
        // The point of the whole exercise: frames the exception itself lacks.
        seen.single() shouldContain "CancellationTraceTest"
    }

    @Test
    fun `the cancellation is rethrown untouched, so the caller sees what it always saw`() {
        val body = watched("body", throwingOnRead(stageWasCompleted)) { }

        val thrown = runCatching { body.read() }.exceptionOrNull()

        thrown shouldBe stageWasCompleted
    }

    @Test
    fun `an ordinary read failure is not a cancellation and is left alone`() {
        val seen = mutableListOf<String>()
        val body = watched("body", throwingOnRead(IOException("connection reset"))) { seen += it }

        runCatching { body.read() }

        seen.shouldBeEmpty()
    }

    @Test
    fun `a read that succeeds reports nothing`() {
        val seen = mutableListOf<String>()
        val body = watched("body", ByteArrayInputStream(byteArrayOf(7))) { seen += it }

        body.read() shouldBe 7

        seen.shouldBeEmpty()
    }

    @Test
    fun `a cancellation nested under another failure is still found`() {
        val wrapped = IllegalStateException("materialisation failed", stageWasCompleted)

        val described = describeCancellation("send POST /orders", wrapped)

        described.shouldNotBeNullAndContain("StageWasCompleted")
        described.shouldNotBeNullAndContain("IllegalStateException <- ")
    }

    @Test
    fun `a failure with no cancellation in it describes nothing`() {
        describeCancellation("send", IOException("connection reset")) shouldBe null
    }

    private fun String?.shouldNotBeNullAndContain(text: String) {
        (this ?: error("expected a description")) shouldContain text
    }
}
