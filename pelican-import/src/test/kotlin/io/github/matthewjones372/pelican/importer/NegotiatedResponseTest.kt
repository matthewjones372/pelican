package io.github.matthewjones372.pelican.importer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * A response documented under several media types, read back.
 *
 * The document says one status, one schema and several renderings of it, which
 * is what `negotiated(...)` describes — so the trip out and back closes on an
 * endpoint offering the same choice rather than on one that lost it.
 */
class NegotiatedResponseTest {

    private val report =
        """
        Report:
          type: object
          properties:
            year: { type: integer }
        """

    /**
     * One operation whose 200 is offered under [first] and then [second].
     * Assembled rather than interpolated into one literal: `trimIndent` reads
     * the string after the interpolation, so a block inserted into it would
     * take the whole document's indentation with it.
     */
    private fun exported(first: String, second: String): String = document(
        listOf(
            "/reports:",
            "  get:",
            "    operationId: exportReport",
            "    responses:",
            "      \"200\":",
            "        description: The report",
            "        content:",
        ).joinToString("\n") + "\n" + (first.trim() + "\n" + second.trim()).prependIndent("          "),
        report,
    )

    private val asJson = "application/json:\n  schema: { \$ref: \"#/components/schemas/Report\" }"

    private val asCsv = "text/csv:\n  schema: { \$ref: \"#/components/schemas/Report\" }"

    @Test
    fun `several renderings of one response come back as one negotiated response`() {
        imported(exported(asJson, asCsv)) shouldContain
            """negotiated(json<Report>(200), media<Report>("text/csv", 200))"""
    }

    @Test
    fun `the document's order is kept, since the first is what a caller who says nothing gets`() {
        imported(exported(asCsv, asJson)) shouldContain
            """negotiated(media<Report>("text/csv", 200), json<Report>(200))"""
    }

    @Test
    fun `renderings that describe different values are two responses, and are refused as such`() {
        val refused = shouldThrow<ImportFailure> {
            imported(exported(asJson, "text/csv:\n  schema: { type: string }"))
        }

        withClue(refused.message) {
            refused.message shouldContain "different schema"
            refused.message shouldContain "different statuses"
        }
    }

    @Test
    fun `a stream among the renderings is refused, because it is not a value written a second way`() {
        val refused = shouldThrow<ImportFailure> {
            imported(
                exported(asJson, "application/x-ndjson:\n  schema: { \$ref: \"#/components/schemas/Report\" }"),
            )
        }

        withClue(refused.message) { refused.message shouldContain "application/x-ndjson" }
    }

    /** A failure carries JSON or nothing, whatever the success beside it offers. */
    @Test
    fun `a failure documented two ways is still refused, in words that say why`() {
        val refused = shouldThrow<ImportFailure> {
            imported(
                document(
                    """
                    /reports:
                      get:
                        operationId: exportReport
                        responses:
                          "200":
                            description: ok
                            content:
                              application/json:
                                schema: { type: object }
                          "404":
                            description: No such report
                            content:
                              application/json:
                                schema: { type: object }
                              text/csv:
                                schema: { type: object }
                    """,
                ),
            )
        }

        withClue(refused.message) { refused.message shouldContain "declared failure carries a JSON payload" }
    }
}
