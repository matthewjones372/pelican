package io.github.matthewjones372.pelican.importer

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** What spec 0092's server publishes for a keyed endpoint, read back as `idempotencyKey()` and nothing beside it. */
class IdempotencyImportTest {

    private fun tipping(required: Boolean = true, inFlight: String = IN_FLIGHT) = document(
        paths = """
        /tips:
          post:
            operationId: leaveTip
            parameters:
              - name: Idempotency-Key
                in: header
                required: $required
                description: A key the caller chooses, unique per request.
                schema: { type: string }
            requestBody:
              required: true
              content:
                application/json:
                  schema: { ${'$'}ref: '#/components/schemas/Tip' }
            responses:
              "201":
                description: Success.
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/Tip' }
              "409":
                description: "$inFlight"
                headers:
                  Retry-After:
                    required: false
                    description: Seconds to wait before sending the request again.
                    schema: { type: integer, format: int64 }
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/ApiError' }
              "422":
                description: "$REUSED"
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/ApiError' }
        """,
        components = """
        Tip:
          type: object
          properties: { amount: { type: integer, format: int32 } }
          required: [amount]
        ApiError:
          type: object
          properties:
            status: { type: integer, format: int32 }
            error: { type: string }
            detail: { type: [string, "null"] }
          required: [error, status]
        """,
    )

    @Test
    fun `the header and Pelican's own 409 and 422 come back as one declaration`() {
        val generated = imported(tipping())

        generated shouldContain "idempotencyKey()"
        generated shouldNotContain "Idempotency-Key"
        generated shouldNotContain "409"
        generated shouldNotContain "422"
    }

    @Test
    fun `an optional key comes back optional`() {
        imported(tipping(required = false)) shouldContain "idempotencyKey(required = false)"
    }

    /** Somebody else's 409 under the same header is a contract of its own, kept as written. */
    @Test
    fun `a header whose failures say something else stays a plain header`() {
        val generated = imported(tipping(inFlight = "Somebody else is already doing that."))

        generated shouldNotContain "idempotencyKey("
        generated shouldContain "headerParam<String>(\"Idempotency-Key\""
        generated shouldContain "409"
    }

    private companion object {
        const val IN_FLIGHT = "A request with this Idempotency-Key is still being answered. Retry after the time given."
        const val REUSED = "This Idempotency-Key was first sent with a different request."
    }
}
