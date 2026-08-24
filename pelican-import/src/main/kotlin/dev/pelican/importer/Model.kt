package dev.pelican.importer

import dev.pelican.JsonObj
import dev.pelican.JsonValue
import dev.pelican.ListStyle

/*
 * What an imported document looks like once the OpenAPI-shaped noise is gone.
 *
 * This sits between the two halves on purpose. The reader's job is to decide
 * what a document says and to refuse what Pelican cannot describe; the
 * emitter's job is to write Kotlin. Neither has to know how the other works,
 * and the version differences — 2.0's `produces`, 3.0's `nullable` — are gone
 * by the time anything here exists.
 *
 * Schemas are carried through as `JsonObj` rather than modelled. They are
 * handed to `pelican-codegen`'s type generator, which is the same code the
 * client generator uses, so the types an imported document produces and the
 * types a client generator produces cannot drift apart.
 */

internal class IrApi(
    val title: String,
    val version: String,
    val description: String?,
    val servers: List<String>,
    val security: List<IrRequirement>,
    val schemes: List<IrScheme>,
    val schemas: JsonObj,
    val endpoints: List<IrEndpoint>,
)

@Suppress("LongParameterList") // A description record, as core's Endpoint is: every parameter is a facet.
internal class IrEndpoint(
    val operationId: String,
    val method: String,
    val path: String,
    val summary: String?,
    val description: String?,
    val tags: List<String>,
    val deprecated: Boolean,
    val params: List<IrParam>,
    /** Where this operation is served, where the document says that is not where the API is. */
    val servers: List<String>,
    val body: IrBody?,
    /** Every documented 2xx, in status order. The first is what a bare `ok(...)` means. */
    val successes: List<IrSuccess>,
    val failures: List<IrFailure>,
    val responseHeaders: List<IrResponseHeader>,
    /** Null means "whatever the document requires"; empty means deliberately public. */
    val security: List<IrRequirement>?,
) {
    override fun toString() = "$operationId ($method $path)"
}

internal class IrParam(
    val name: String,
    val location: String,
    val required: Boolean,
    /**
     * The schema for one value. For a list that is the element's, since the
     * array around it is said by [listStyle] rather than by a schema.
     */
    val schema: JsonObj,
    val description: String?,
    val default: JsonValue?,
    /** What the document offers as a sample value, for the ones that are strings. */
    val example: String?,
    /** Null where the parameter carries one value; otherwise how several are spread. */
    val listStyle: ListStyle? = null,
)

internal sealed class IrBody {
    abstract val description: String?

    class Json(val schema: JsonObj, override val description: String?) : IrBody()
    class Form(val schema: JsonObj, override val description: String?) : IrBody()
    class Multipart(val parts: List<IrPart>, override val description: String?) : IrBody()
    class Raw(override val description: String?) : IrBody()
}

internal sealed class IrPart {
    abstract val name: String
    abstract val required: Boolean
    abstract val description: String?

    class Text(
        override val name: String,
        val schema: JsonObj,
        override val required: Boolean,
        override val description: String?,
    ) : IrPart()

    class File(
        override val name: String,
        val contentType: String?,
        override val required: Boolean,
        override val description: String?,
    ) : IrPart()
}

/**
 * One 2xx response, as the output that describes it.
 *
 * [headers] are the ones belonging to this response alone, and are empty for an
 * operation with a single success — there they are the endpoint's, and travel
 * as [IrEndpoint.responseHeaders]. See `Responses.read`.
 */
internal sealed class IrSuccess {
    abstract val status: Int
    abstract val headers: List<IrResponseHeader>

    class Json(
        override val status: Int,
        val schema: JsonObj,
        override val headers: List<IrResponseHeader> = emptyList(),
    ) : IrSuccess()

    class Ndjson(
        override val status: Int,
        val schema: JsonObj,
        override val headers: List<IrResponseHeader> = emptyList(),
    ) : IrSuccess()

    class Sse(
        override val status: Int,
        val schema: JsonObj,
        override val headers: List<IrResponseHeader> = emptyList(),
    ) : IrSuccess()

    class Text(
        override val status: Int,
        override val headers: List<IrResponseHeader> = emptyList(),
    ) : IrSuccess()

    class Bytes(
        override val status: Int,
        val mediaType: String,
        override val headers: List<IrResponseHeader> = emptyList(),
    ) : IrSuccess()

    class Empty(
        override val status: Int,
        override val headers: List<IrResponseHeader> = emptyList(),
    ) : IrSuccess()
}

/** Whether producing this response means handing over a stream rather than a value. */
internal fun IrSuccess.streams(): Boolean =
    this is IrSuccess.Ndjson || this is IrSuccess.Sse || this is IrSuccess.Bytes

/**
 * The same response with the headers it carries. Read after the response
 * itself, because whether they belong here or to the endpoint depends on how
 * many successes there turned out to be.
 */
internal fun IrSuccess.with(headers: List<IrResponseHeader>): IrSuccess = when (this) {
    is IrSuccess.Json -> IrSuccess.Json(status, schema, headers)
    is IrSuccess.Ndjson -> IrSuccess.Ndjson(status, schema, headers)
    is IrSuccess.Sse -> IrSuccess.Sse(status, schema, headers)
    is IrSuccess.Text -> IrSuccess.Text(status, headers)
    is IrSuccess.Bytes -> IrSuccess.Bytes(status, mediaType, headers)
    is IrSuccess.Empty -> IrSuccess.Empty(status, headers)
}

/** A documented non-2xx. One with a JSON body becomes a typed failure; one without is documented only. */
internal class IrFailure(
    /** Null is the document's `default` — "and anything else", which has no status of its own. */
    val status: Int?,
    val schema: JsonObj?,
    val description: String,
    val headers: List<IrResponseHeader>,
)

/**
 * Whether a handler could answer with this, which decides both the binder the
 * stub is written against and whether the failure is named on the output.
 *
 * Two ways of not being returnable, and they are not the same thing. A failure
 * with no payload is a response the handler *throws* — there is nothing to
 * carry, so `errorResponse(...)` documents it and `notFound(...)` produces it.
 * A `default` is a response nothing produces at all, whatever it carries.
 */
internal val IrFailure.returnable: Boolean get() = status != null && schema != null

internal class IrResponseHeader(
    val name: String,
    val schema: JsonObj,
    val description: String?,
    val required: Boolean,
    val example: String?,
)

internal class IrRequirement(val scheme: String, val scopes: List<String>)

internal sealed class IrScheme {
    abstract val name: String
    abstract val description: String?

    class ApiKey(
        override val name: String,
        val location: String,
        val paramName: String,
        override val description: String?,
    ) : IrScheme()

    class Http(
        override val name: String,
        val scheme: String,
        val bearerFormat: String?,
        override val description: String?,
    ) : IrScheme()

    class OpenId(
        override val name: String,
        val url: String,
        override val description: String?,
    ) : IrScheme()

    class OAuth2(
        override val name: String,
        val flows: List<IrFlow>,
        override val description: String?,
    ) : IrScheme()
}

/**
 * Every schema this endpoint carries, wherever it sat. Two readers want the
 * same list — the one checking what can be described, and the one working out
 * which named schemas are reached — and a list assembled twice is a list that
 * ends up different.
 */
internal fun IrEndpoint.schemas(): List<JsonObj> = buildList {
    params.forEach { add(it.schema) }
    when (val declared = body) {
        is IrBody.Json -> add(declared.schema)
        is IrBody.Form -> add(declared.schema)
        is IrBody.Multipart -> declared.parts.filterIsInstance<IrPart.Text>().forEach { add(it.schema) }
        is IrBody.Raw, null -> Unit
    }
    successes.forEach { answered ->
        when (answered) {
            is IrSuccess.Json -> add(answered.schema)
            is IrSuccess.Ndjson -> add(answered.schema)
            is IrSuccess.Sse -> add(answered.schema)
            else -> Unit
        }
    }
    failures.forEach { failure -> failure.schema?.let { add(it) } }
    responseHeaders.forEach { add(it.schema) }
    successes.flatMap { it.headers }.forEach { add(it.schema) }
    failures.flatMap { it.headers }.forEach { add(it.schema) }
}

internal class IrFlow(
    val kind: String,
    val authorizationUrl: String?,
    val tokenUrl: String?,
    val refreshUrl: String?,
    val scopes: Map<String, String>,
)
