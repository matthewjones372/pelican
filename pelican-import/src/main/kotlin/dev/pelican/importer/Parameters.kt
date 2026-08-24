package dev.pelican.importer

import dev.pelican.JsonObj
import dev.pelican.JsonValue

/**
 * The inputs that travel outside the body.
 *
 * Two lists become one: the path item's parameters apply to every operation on
 * it, and the operation's own list overrides them by name and location, which
 * is what OpenAPI says and what a reader of the document would assume.
 *
 * The refusals here are all one refusal in different positions. A Pelican
 * input is one string on the wire decoded into one value, so a parameter that
 * is an array, an object, or a whole JSON document under `content` has no
 * description to become — and the generated handler would have to take
 * something the endpoint never decoded.
 */
internal class Parameters(private val reader: Reader, private val operation: Operation) {

    fun read(): List<IrParam> {
        val merged = LinkedHashMap<Pair<String, String>, IrParam>()

        val shared = operation.shared.mapIndexed { i, node ->
            node to (JsonPath.root / "paths" / operation.template / "parameters" / i)
        }
        val own = operation.node.arr("parameters").mapIndexed { i, node ->
            node to (operation.path / "parameters" / i)
        }

        (shared + own).forEach { (node, path) ->
            val param = parameter(node, path)
            merged[param.name to param.location] = param
        }

        val params = merged.values.toList()
        checkAgainstTemplate(params)
        return params
    }

    private fun parameter(node: JsonValue, at: JsonPath): IrParam {
        val (param, path) = reader.deref(node, at)
        val name = param.str("name")
            ?: unsupported(path, "A parameter with no name.")
        val location = param.str("in")
            ?: unsupported(path, "Parameter '$name' does not say where it travels.")

        if (location !in locations) {
            unsupported(path, "Parameter '$name' travels in '$location', which is not a place Pelican reads from.")
        }
        if (param["content"] != null) {
            unsupported(
                path,
                "Parameter '$name' carries a document rather than a value. Pelican decodes a parameter " +
                    "from one string; take it as a request body instead.",
            )
        }
        checkSerialisation(param, name, location, path)

        val schema = normaliseSchema(param["schema"] ?: unsupported(path, "Parameter '$name' declares no schema."))
        checkScalar(schema, name, path)

        val required = param.bool("required")
        if (location == "path" && !required) {
            unsupported(path, "Path parameter '$name' is optional, and a route cannot leave a segment out.")
        }

        return IrParam(
            name = name,
            location = location,
            required = required,
            schema = schema,
            description = param.str("description"),
            default = schema["default"],
            example = param.str("example"),
        )
    }

    /**
     * Anything but the default encoding is refused rather than approximated.
     *
     * `style` and `explode` say how a value spreads across the wire — repeated
     * keys, bracketed keys, comma-joined. All of them describe values with
     * parts, which is the case already refused above; saying so here as well
     * means the message names the keyword the document actually used.
     */
    private fun checkSerialisation(param: JsonObj, name: String, location: String, path: JsonPath) {
        val style = param.str("style")
        if (style != null && style != defaultStyle[location]) {
            unsupported(path, "Parameter '$name' is encoded as '$style', and Pelican reads the default encoding.")
        }
        val explode = param["explode"]
        if (explode != null && param.bool("explode") != (defaultStyle[location] == "form")) {
            unsupported(path, "Parameter '$name' sets `explode`, which only matters for values with parts.")
        }
    }

    private fun checkScalar(schema: JsonObj, name: String, path: JsonPath) {
        val type = schema.str("type")
            ?: (schema["type"] as? dev.pelican.JsonArr)?.items
                ?.mapNotNull { (it as? dev.pelican.JsonStr)?.value }
                ?.firstOrNull { it != "null" }

        when {
            type == "array" -> unsupported(
                path,
                "Parameter '$name' is a list. A Pelican input decodes one value from one string; " +
                    "take a delimited string and split it in the handler, or exclude the operation.",
            )

            type == "object" || schema["properties"] != null -> unsupported(
                path,
                "Parameter '$name' is an object, and Pelican decodes a parameter from one string.",
            )

            schema["\$ref"] != null -> unsupported(
                path,
                "Parameter '$name' points at a named schema. Named schemas describe bodies here; " +
                    "a parameter's type is written on the parameter.",
            )

            type == null -> unsupported(path, "Parameter '$name' has a schema that does not say what type it is.")
        }
    }

    /**
     * The route and the parameters have to agree, and the document is where
     * they usually do not: a `{userId}` nobody declared, or a path parameter
     * for a segment that was renamed. Pelican's own `endpoint(...)` refuses
     * both at class-init time, so generating them would move a build failure
     * into a startup failure.
     */
    private fun checkAgainstTemplate(params: List<IrParam>) {
        val captures = capturePattern.findAll(operation.template).map { it.groupValues[1] }.toList()
        val declared = params.filter { it.location == "path" }.map { it.name }

        (captures - declared.toSet()).forEach {
            unsupported(operation.path, "The route captures {$it}, and no parameter declares it.")
        }
        (declared - captures.toSet()).forEach {
            unsupported(operation.path, "Path parameter '$it' is declared, and ${operation.template} has no {$it}.")
        }
    }

    private val locations = setOf("path", "query", "header", "cookie")

    private val defaultStyle = mapOf(
        "path" to "simple",
        "query" to "form",
        "header" to "simple",
        "cookie" to "form",
    )

    private val capturePattern = Regex("\\{([^}]+)}")
}
