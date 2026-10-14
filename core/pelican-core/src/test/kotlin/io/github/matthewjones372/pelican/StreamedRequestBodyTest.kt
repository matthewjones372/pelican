package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** What an endpoint taking a streamed body may and may not be described as. */
class StreamedRequestBodyTest {

    private data class Row(val id: Long)

    private val userId = pathParam<Long>("userId")
    private val dryRun = queryParam<Boolean>("dryRun").optional()
    private val apiKey = headerParam<String>("X-Api-Key")

    @Test
    fun `the stream composes with the parameters that do not travel in the body`() {
        val ingest = endpoint(userId, dryRun, apiKey, ndjsonIn<Row>()) {
            post("users" / userId / "rows")
            text()
        }

        ingest.queries.map { it.name } shouldBe listOf("dryRun")
        ingest.headerParams.map { it.name } shouldBe listOf("X-Api-Key")
        ingest.pathSpec.captures.map { it.name } shouldBe listOf("userId")
        ingest.bodyInput.shouldBeNdjsonOf<Row>()
    }

    @Test
    fun `the stream is the body, so a second body is refused where it is declared`() {
        val failure = shouldThrow<IllegalArgumentException> {
            endpoint(ndjsonIn<Row>(), jsonBody<Row>()) {
                post("rows")
                text()
            }
        }

        failure.message shouldContain "reads one request body"
        failure.message shouldContain "body:ndjson"
    }

    /** Parts are the body too, and the check that catches them already existed. */
    @Test
    fun `multipart parts beside a stream are refused as any other body would be`() {
        val failure = shouldThrow<IllegalArgumentException> {
            endpoint(ndjsonIn<Row>(), textPart<String>("label")) {
                post("rows")
                text()
            }
        }

        failure.message shouldContain "the parts are the body"
    }

    @Test
    fun `it is declared and documented as application slash x-ndjson`() {
        ndjsonIn<Row>().mediaType shouldBe "application/x-ndjson"
        ndjsonIn<Row>().payloadType shouldBe null
    }

    private inline fun <reified T> BodyInput<*>?.shouldBeNdjsonOf() {
        val body = this as? NdjsonBody<*> ?: error("$this is not a streamed body")
        body.type.classifier shouldBe T::class
    }
}
