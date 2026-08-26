package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.renderError
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * The refusal envelope is configuration, and the one thing configuration must
 * not do is change the answer nobody configured. Every refusal core can classify
 * is rendered here by the default and compared against the bytes the fixed
 * envelope wrote before there was a choice.
 */
class RefusalRendererTest {

    private val everyRefusal: List<Throwable> = listOf(
        ApiException(403, "Forbidden", "the gate said no"),
        ApiException(405, "Method not allowed", "OPTIONS /users"),
        DecodeFailure("limit", raw = "0", expected = "a value between 1 and 100"),
        BodyDecodeFailure("Unexpected character '{'"),
        NotAcceptable(setOf("application/json")),
        PayloadTooLarge(limit = 4_096L),
        IllegalStateException("connection to db-primary.internal refused"),
    )

    private fun String.bytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    // ------------------------------------------------- the default, byte for byte

    @Test
    fun `the default writes the bytes the fixed envelope wrote`() {
        everyRefusal.forEach { raw ->
            val rendered = renderError(raw, api = null)
            val envelope = ApiError(
                rendered.error.status,
                rendered.error.error,
                rendered.error.detail,
            ).toJson().render()

            withClue(raw.toString()) {
                rendered.body.bytes.toList() shouldContainExactly envelope.bytes().toList()
                rendered.body.mediaType shouldBe "application/json"
            }
        }
    }

    @Test
    fun `and it is what an unconfigured Api renders with`() {
        val unconfigured = api(endpoints = emptyList())

        unconfigured.refusals shouldBe ApiErrorEnvelope
    }

    @Test
    fun `a renderer named on the builder is the one the Api carries`() {
        val configured = api(endpoints = emptyList()) { refusals(ProblemDetails) }

        configured.refusals shouldBe ProblemDetails
    }

    // --------------------------------------------------------------- RFC 9457

    @Test
    fun `problem details answers its own media type`() {
        val rendered = renderError(PayloadTooLarge(limit = 4_096L), api = problemApi)

        rendered.body.mediaType shouldBe "application/problem+json"
    }

    @Test
    fun `and maps the classified refusal onto the members RFC 9457 names`() {
        val rendered = renderError(
            DecodeFailure("limit", raw = "0", expected = "a value between 1 and 100"),
            api = problemApi,
        )
        val body = String(rendered.body.bytes, StandardCharsets.UTF_8)

        body shouldBe """{"type":"about:blank","title":"Invalid parameter","status":400,""" +
            """"detail":"Cannot decode '0' for 'limit': expected a value between 1 and 100"}"""
    }

    @Test
    fun `instance is the path template of the route that refused`() {
        val rendered = renderError(PayloadTooLarge(limit = 4L), api = problemApi, endpoint = upload)
        val body = String(rendered.body.bytes, StandardCharsets.UTF_8)

        body shouldContain """"instance":"/users/{userId}/uploads""""
    }

    @Test
    fun `and is absent where nothing matched`() {
        val rendered = renderError(ApiException(404, "Not found"), api = problemApi)
        val body = String(rendered.body.bytes, StandardCharsets.UTF_8)

        withClue(body) { body shouldContain """"status":404""" }
        body.contains("instance") shouldBe false
    }

    /** The 500 path renders through the renderer too, and still says nothing else. */
    @Test
    fun `an unexpected failure keeps its reference and gives up nothing else`() {
        val rendered = renderError(IllegalStateException(SECRET), api = problemApi)
        val body = String(rendered.body.bytes, StandardCharsets.UTF_8)

        withClue(body) {
            body shouldContain """"status":500"""
            body shouldContain """"title":"Internal server error""""
            body shouldContain "Reference: ${rendered.reference}"
            body.contains(SECRET) shouldBe false
        }
    }

    /** Nothing the renderer does changes what a filter measuring the service is told. */
    @Test
    fun `the classified status is the same whichever dialect writes it`() {
        everyRefusal.forEach { raw ->
            withClue(raw.toString()) {
                renderError(raw, api = problemApi).error.status shouldBe renderError(raw, api = null).error.status
            }
        }
    }

    @Test
    fun `a described refusal mints no reference under either dialect`() {
        renderError(ApiException(403, "Forbidden"), api = problemApi).reference.shouldBeNull()
    }

    private companion object {
        const val SECRET = "connection to db-primary.internal:5432 refused"

        val userId = pathParam<Long>("userId")

        val upload = endpoint(userId) {
            post("users" / userId / "uploads")
            operationId = "upload"
            empty(201)
        }

        val problemApi = api(endpoints = emptyList()) { refusals(ProblemDetails) }
    }
}
