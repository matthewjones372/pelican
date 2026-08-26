package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.classifyError
import io.github.matthewjones372.pelican.spi.renderError
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A refusal is observed where it is rendered, so the counter cannot fall behind
 * the wire: the interpreters render every refusal through `renderError`, and
 * that is the one place this hook is called from.
 *
 * The classification is narrower than the one the renderer is handed. A 500
 * nobody described and a status a filter chose are both rendered here, and
 * neither is a refusal in this sense — the chain ran, so a request metric has
 * already counted them, and counting them twice under two names is how two
 * graphs come to disagree.
 */
class RefusalObserverTest {

    private val seen = mutableListOf<Observed>()

    private val watched = api(endpoints = emptyList()) {
        onRefusal { reason, status, pathTemplate -> seen += Observed(reason, status, pathTemplate) }
    }

    private fun refuse(raw: Throwable, endpoint: Endpoint<*, *>? = null) {
        renderError(raw, watched, endpoint)
    }

    // ------------------------------------------------------- one reason per refusal

    @Test
    fun `every refusal core raises itself is observed under the reason that names it`() {
        refuse(Unrouted(404, "Not found", "GET /nope"))
        refuse(DecodeFailure("limit", raw = "x", expected = "an integer"))
        refuse(BodyDecodeFailure("Unexpected character '{'"))
        refuse(UnsupportedMediaType("this endpoint reads application/json"))
        refuse(NotAcceptable(setOf("application/json")))
        refuse(PayloadTooLarge(limit = 4_096L))

        seen shouldContainExactly listOf(
            Observed(RefusalReason.UNMATCHED, 404, null),
            Observed(RefusalReason.DECODE, 400, null),
            Observed(RefusalReason.DECODE, 400, null),
            Observed(RefusalReason.CONTENT_TYPE, 415, null),
            Observed(RefusalReason.ACCEPT, 406, null),
            Observed(RefusalReason.BODY_LIMIT, 413, null),
        )
    }

    @Test
    fun `a path some other method describes is the same reason under 405`() {
        refuse(Unrouted(405, "Method not allowed", "PUT /hello/ada"))

        seen shouldContainExactly listOf(Observed(RefusalReason.UNMATCHED, 405, null))
    }

    // ------------------------------------------------------------ what it is told

    @Test
    fun `the route that refused is named by its template, never by the URL that arrived`() {
        refuse(PayloadTooLarge(limit = 4L), upload)

        seen shouldContainExactly listOf(Observed(RefusalReason.BODY_LIMIT, 413, "/users/{userId}/uploads"))
    }

    // -------------------------------------------------- what is deliberately not one

    /**
     * The counter and `http.server.requests` are disjoint by construction. A 500
     * nobody described reached the chain, so the request metric counted it; the
     * same is true of every status a filter or a handler chose.
     */
    @Test
    fun `a failure nobody described is not a refusal here`() {
        refuse(IllegalStateException("connection to db-primary.internal refused"))
        refuse(UndeclaredResponse("error:410 was returned but this declares error:404"))

        seen.shouldBeEmpty()
    }

    @Test
    fun `nor is a status a filter or a handler chose`() {
        refuse(ApiException(403, "Forbidden", "the gate said no"), upload)
        refuse(ApiException(404, "Not found"), upload)
        refuse(ApiException(429, "Too many requests"), upload)

        withClue("these went through the chain, so `http.server.requests` already has them") {
            seen.shouldBeEmpty()
        }
    }

    // ------------------------------------------------------ where it is not called

    @Test
    fun `an Api with no observer renders exactly as before`() {
        val unwatched = api(endpoints = emptyList())

        unwatched.onRefusal shouldBe null
        renderError(PayloadTooLarge(limit = 4L), unwatched).error.status shouldBe 413
    }

    /**
     * `classifyError` is for a caller answering in an envelope the protocol
     * fixes rather than one this service chose — an MCP tool result. Nothing
     * went out over HTTP under the status it names, so nothing is counted.
     */
    @Test
    fun `classifying without rendering observes nothing`() {
        classifyError(PayloadTooLarge(limit = 4L), watched).error.status shouldBe 413

        seen.shouldBeEmpty()
    }

    /** What the observer was told, in the order it was told. */
    private data class Observed(val reason: RefusalReason, val status: Int, val pathTemplate: String?)

    private companion object {
        val userId = pathParam<Long>("userId")

        val upload = endpoint(userId) {
            post("users" / userId / "uploads")
            operationId = "upload"
            empty(201)
        }
    }
}
