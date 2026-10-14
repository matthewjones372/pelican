package io.github.matthewjones372.pelican.openapi

/**
 * Which revision of the OpenAPI Specification a document is written against.
 *
 * There are two, and the reason there are two is that the number at the top of
 * the document is not decoration: the specification says the `openapi` field
 * "SHOULD be used by tooling to interpret the OpenAPI document", and its
 * versioning policy promises compatibility only within one `major.minor`
 * feature set — "Tooling which supports OAS 3.1 SHOULD be compatible with all
 * OAS 3.1.\* versions" — while reserving the right to make non-backwards
 * compatible changes in a minor release. So a consumer that reads 3.1 is not
 * promised anything about a document that says 3.2, and in practice is not
 * given it either: swagger-parser 2.1.47, the newest release at the time of
 * writing and the parser that `openapi-generator` and most of the JVM
 * ecosystem is built on, models `SpecVersion.V30` and `SpecVersion.V31` and
 * nothing else, and turns a 3.2.0 document into `null` without reporting a
 * single message. That silence is why the version is a choice rather than a
 * number that moved.
 *
 * [V3_1_0] is the default because the caller who does not choose is best served
 * by the version their tooling can actually read. [V3_2_0] is the version in
 * which Pelican's document is *true* about two things 3.1 has no vocabulary
 * for — see [io.github.matthewjones372.pelican.openapi.openApi] and the
 * reference manual — and the default will move to it once the parsers a reader
 * is likely to point at have caught up.
 *
 * There is no 3.0 entry, and there will not be one; the reasoning is in the
 * reference manual under "What isn't here".
 */
enum class OpenApiVersion(
    /** What goes in the document's `openapi` field, and what tooling switches on. */
    val field: String,
) {
    /**
     * OpenAPI 3.1.0, published 15 February 2021. The whole JVM tooling
     * ecosystem reads it.
     */
    V3_1_0("3.1.0"),

    /**
     * OpenAPI 3.2.0, published 19 September 2025 and the current specification.
     * Everything 3.1 says is still said the same way; what it adds, and what
     * Pelican now says with it, is listed on [openApi].
     */
    V3_2_0("3.2.0"),
    ;

    override fun toString(): String = field
}
