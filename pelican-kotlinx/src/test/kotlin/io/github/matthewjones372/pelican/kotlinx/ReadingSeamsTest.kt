package io.github.matthewjones372.pelican.kotlinx

import io.github.matthewjones372.pelican.BodyDecodeFailure
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.NoCodecs
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.reflect.typeOf

class ReadingSeamsTest {

    @Test
    fun `a body given as bytes is read by kotlinx's stream reader and never becomes a String`() {
        val probe = KotlinxCodecs.codec<Probe>(typeOf<Probe>()).decodeFrom("\"héllo\"".toByteArray())

        probe.text shouldBe "héllo"
        val calls = probe.decodedFrom.map { "${it.className}.${it.methodName}" }
        withClue(calls.joinToString("\n")) {
            calls shouldContain "kotlinx.serialization.json.JvmStreamsKt.decodeFromStream"
            calls.map { it.substringAfterLast('.') } shouldNotContain "decodeFromString"
        }
    }

    @Test
    fun `readTree reads past core's depth of sixty-four, as Jackson's does`() {
        val deep = "[".repeat(100) + "]".repeat(100)

        shouldThrow<BodyDecodeFailure> { NoCodecs.readTree(deep) }
        JacksonCodecs.readTree(deep).shouldBeInstanceOf<JsonArr>()
        KotlinxCodecs.readTree(deep).shouldBeInstanceOf<JsonArr>()
    }

    @Test
    fun `a document past Jackson's depth limit is a BodyDecodeFailure, not a stack overflow`() {
        val arrays = "[".repeat(100_000) + "]".repeat(100_000)
        val objects = "{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000)

        for (deeper in listOf(arrays, objects)) {
            shouldThrow<BodyDecodeFailure> { KotlinxCodecs.readTree(deeper) }
            shouldThrow<BodyDecodeFailure> { JacksonCodecs.readTree(deeper) }
        }
    }

    @Test
    fun `text that is not JSON, and a number past Jackson's length limit, are BodyDecodeFailures`() {
        shouldThrow<BodyDecodeFailure> { KotlinxCodecs.readTree("{\"a\": }") }
        shouldThrow<BodyDecodeFailure> { KotlinxCodecs.readTree("1".repeat(1001)) }
        shouldThrow<BodyDecodeFailure> { KotlinxCodecs.readTree("1e400") }
    }

    @Test
    fun `readTree keeps whole numbers whole and strings as strings`() {
        val tree = KotlinxCodecs.readTree("""{"n": 7, "big": 123456789012345678901, "x": 1.5, "s": "7"}""")

        tree shouldBe JsonObj(
            mapOf(
                "n" to JsonNum(7L),
                "big" to JsonNum(BigInteger("123456789012345678901")),
                "x" to JsonNum(1.5),
                "s" to JsonStr("7"),
            ),
        )
    }
}

/** Records the call stack it was decoded under, which is how the test sees which path was taken. */
@Serializable(with = WhereDecoded::class)
class Probe(val text: String, val decodedFrom: List<StackTraceElement>)

object WhereDecoded : KSerializer<Probe> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Probe", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Probe) = encoder.encodeString(value.text)

    override fun deserialize(decoder: Decoder): Probe =
        Probe(decoder.decodeString(), Thread.currentThread().stackTrace.toList())
}
