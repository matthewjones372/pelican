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
    val body: IrBody?,
    val success: IrSuccess,
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

/** The one 2xx response, as the output that describes it. */
internal sealed class IrSuccess {
    abstract val status: Int

    class Json(override val status: Int, val schema: JsonObj) : IrSuccess()
    class Ndjson(override val status: Int, val schema: JsonObj) : IrSuccess()
    class Sse(override val status: Int, val schema: JsonObj) : IrSuccess()
    class Text(override val status: Int) : IrSuccess()
    class Bytes(override val status: Int, val mediaType: String) : IrSuccess()
    class Empty(override val status: Int) : IrSuccess()
}

/** A documented non-2xx. One with a JSON body becomes a typed failure; one without is documented only. */
internal class IrFailure(
    val status: Int,
    val schema: JsonObj?,
    val description: String,
    val headers: List<IrResponseHeader>,
)

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
    when (val answered = success) {
        is IrSuccess.Json -> add(answered.schema)
        is IrSuccess.Ndjson -> add(answered.schema)
        is IrSuccess.Sse -> add(answered.schema)
        else -> Unit
    }
    failures.forEach { failure -> failure.schema?.let { add(it) } }
    responseHeaders.forEach { add(it.schema) }
    failures.flatMap { it.headers }.forEach { add(it.schema) }
}

internal class IrFlow(
    val kind: String,
    val authorizationUrl: String?,
    val tokenUrl: String?,
    val refreshUrl: String?,
    val scopes: Map<String, String>,
)
