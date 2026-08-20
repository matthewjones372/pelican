package dev.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * A form body carries strings and says nothing about types, so what it decodes
 * to is decided by the schema the document publishes. These tests hold the
 * shaping step on its own: the "codec" underneath hands back the JSON it was
 * given, so what is asserted on is exactly the document the real codec would
 * be asked to read.
 *
 * That isolation is the point. Whether Jackson can turn `{"visits":3}` into a
 * data class is Jackson's business and is tested where Jackson is; whether
 * `visits=3` becomes `3` rather than `"3"` is this module's, and is the part
 * that decides the two codec libraries still agree.
 */
class FormBodyTest {

    data class SignIn(val user: String, val remember: Boolean, val visits: Int, val tags: List<String>)

    data class Nested(val inner: SignIn)

    /**
     * Hands back whatever JSON it is given, so a test can read the shaping
     * step's output directly. Schemas are hand-written for the same reason the
     * OpenAPI tests write theirs: no codec module is needed to state what a
     * form field means.
     */
    private object Schemas : Codecs {
        override fun <T> codec(type: KType): BodyCodec<T> {
            @Suppress("UNCHECKED_CAST")
            return object : BodyCodec<String> {
                override fun encodeToString(value: String) = value
                override fun decodeFromString(text: String) = text
            } as BodyCodec<T>
        }

        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            if (!components.isRegistered("SignIn")) {
                components.register(
                    "SignIn",
                    jsonObj {
                        "type" to "object"
                        put(
                            "properties",
                            jsonObj {
                                put("user", jsonObj { "type" to "string" })
                                put("remember", jsonObj { "type" to "boolean" })
                                put("visits", jsonObj { "type" to "integer"; "format" to "int32" })
                                put(
                                    "tags",
                                    jsonObj {
                                        "type" to "array"
                                        put("items", jsonObj { "type" to "string" })
                                    },
                                )
                            },
                        )
                    },
                )
                components.register(
                    "Nested",
                    jsonObj {
                        "type" to "object"
                        put("properties", jsonObj { put("inner", components.ref("SignIn")) })
                    },
                )
            }
            return components.ref(if (type == typeOf<Nested>()) "Nested" else "SignIn")
        }
    }

    private val codec = Schemas.formCodec<String>(typeOf<SignIn>())

    @Test
    fun `each field becomes the JSON type its schema declares`() {
        codec.decodeFromString("user=ada&remember=true&visits=3") shouldBe
            """{"user":"ada","remember":true,"visits":3}"""
    }

    @Test
    fun `what a browser posts for a checkbox is the boolean the schema asked for`() {
        // `on` is what an HTML checkbox sends, and the same spellings a query
        // parameter accepts are accepted here — one codec, one answer.
        codec.decodeFromString("remember=on") shouldBe """{"remember":true}"""
        codec.decodeFromString("remember=0") shouldBe """{"remember":false}"""
    }

    @Test
    fun `a repeated field becomes the array its schema declares`() {
        codec.decodeFromString("tags=red&tags=green") shouldBe """{"tags":["red","green"]}"""
    }

    @Test
    fun `values are percent-decoded, and a plus is a space`() {
        codec.decodeFromString("user=ada+lovelace") shouldBe """{"user":"ada lovelace"}"""
        codec.decodeFromString("user=a%26b") shouldBe """{"user":"a&b"}"""
    }

    @Test
    fun `a field the schema does not describe is dropped rather than refused`() {
        // A browser sends the name of the button that was clicked, and a CSRF
        // token a filter has already dealt with. Refusing those would make an
        // ordinary HTML form impossible to point at an endpoint.
        codec.decodeFromString("user=ada&_csrf=deadbeef&submit=Sign+in") shouldBe """{"user":"ada"}"""
    }

    @Test
    fun `an empty value for a non-string field is absence, not a decode failure`() {
        // An untouched number input submits "", which is not a number. Leaving
        // the property out is what lets the type's own default apply.
        codec.decodeFromString("user=&visits=") shouldBe """{"user":""}"""
    }

    @Test
    fun `a value that will not decode is the same 400 a query parameter would give`() {
        val failure = shouldThrow<DecodeFailure> { codec.decodeFromString("visits=lots") }

        failure.paramName shouldBe "visits"
        failure.raw shouldBe "lots"
    }

    @Test
    fun `encoding is the same trip backwards`() {
        codec.encodeToString("""{"user":"ada","remember":true,"visits":3,"tags":["red","green"]}""") shouldBe
            "user=ada&remember=true&visits=3&tags=red&tags=green"
    }

    @Test
    fun `encoding leaves out a null rather than sending the word`() {
        codec.encodeToString("""{"user":"ada","visits":null}""") shouldBe "user=ada"
    }

    @Test
    fun `encoding percent-encodes what a form field cannot carry literally`() {
        codec.encodeToString("""{"user":"a&b"}""") shouldBe "user=a%26b"
    }

    @Test
    fun `a shape a form cannot carry fails when the codec is resolved, not on a request`() {
        val failure = shouldThrow<IllegalStateException> { Schemas.formCodec<String>(typeOf<Nested>()) }

        withClue(failure.message) { (failure.message ?: "") shouldContain "cannot have type 'object'" }
    }
}
