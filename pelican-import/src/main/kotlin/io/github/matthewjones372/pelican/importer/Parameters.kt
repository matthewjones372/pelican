package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.ListStyle
import io.github.matthewjones372.pelican.defaultExplodeFor
import io.github.matthewjones372.pelican.defaultStyleAt

/**
 * The inputs that travel outside the body.
 *
 * Two lists become one: the path item's parameters apply to every operation,
 * and the operation's own override them by name and location.
 *
 * Each value is one string on the wire decoded into one Kotlin value, so what
 * has no such reading is refused — an object, a list of objects, a whole JSON
 * document under `content`.
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

        val declared = normaliseSchema(param["schema"] ?: unsupported(path, "Parameter '$name' declares no schema."))
        val listStyle = serialisation(param, declared, name, location, path)

        // For a list it is the element that becomes a codec, and the array
        // around it is spelled by the modifier the style chose.
        val element = if (listStyle == null) declared else itemSchema(declared, name, path)
        checkScalar(element, name, listStyle != null, path)

        val required = param.bool("required")
        if (location == "path" && !required) {
            unsupported(path, "Path parameter '$name' is optional, and a route cannot leave a segment out.")
        }

        return IrParam(
            name = name,
            location = location,
            required = required,
            schema = element,
            description = param.str("description"),
            default = declared["default"],
            example = param.str("example") ?: if (listStyle == null) null else element.str("example"),
            listStyle = listStyle,
        )
    }

    /**
     * How this parameter's values reach the wire, or null where it carries one.
     * `style` and `explode` are only about boundaries between several values,
     * so a single-valued parameter setting either is refused rather than read
     * as the default it contradicts.
     */
    private fun serialisation(
        param: JsonObj,
        schema: JsonObj,
        name: String,
        location: String,
        path: JsonPath,
    ): ListStyle? {
        val fallback = defaultStyleAt(location)
        val style = param.str("style") ?: fallback
        val explode = if (param["explode"] == null) defaultExplodeFor(style) else param.bool("explode")

        if (schema.scalarType() != "array") {
            if (style != fallback) {
                unsupported(
                    path,
                    "Parameter '$name' is encoded as '$style', which says how a value with parts is spread out, " +
                        "and its schema says it has none. Declare it as `type: array`, or drop the `style`.",
                )
            }
            if (param["explode"] != null && explode != defaultExplodeFor(fallback)) {
                unsupported(path, "Parameter '$name' sets `explode`, which only matters for values with parts.")
            }
            return null
        }

        return listStyle(style, explode, name, location, path)
    }

    /**
     * The four encodings a list has an honest reading as. The refusals are not
     * gaps: `deepObject` describes an object spread over several names, and the
     * others are a keyword contradicting the one beside it.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per (location, style, explode) reading; the list is the rule.
    private fun listStyle(
        style: String,
        explode: Boolean,
        name: String,
        location: String,
        path: JsonPath,
    ): ListStyle = when {
        location == "path" -> unsupported(
            path,
            "Parameter '$name' is a list, and it is in the path. A route matches one segment per capture, " +
                "so take it as a query parameter, or exclude the operation.",
        )

        style == "deepObject" -> unsupported(
            path,
            "Parameter '$name' is encoded as 'deepObject', which spreads an object over several names. " +
                "Pelican describes a list of values; take the object as a request body instead.",
        )

        location == "header" && style != "simple" -> unsupported(
            path,
            "Parameter '$name' is a header encoded as '$style', and a header carries its values comma-separated.",
        )

        location == "header" && explode -> unsupported(
            path,
            "Parameter '$name' is a header that sets `explode`, which puts each value under its own name — " +
                "and a header field has one name. Drop `explode`, or take it as a query parameter.",
        )

        location == "header" -> ListStyle.COMMA

        location == "cookie" && style != "form" -> unsupported(
            path,
            "Parameter '$name' is a cookie encoded as '$style', and a cookie is written as `form`.",
        )

        location == "cookie" && explode -> ListStyle.REPEATED

        location == "cookie" -> unsupported(
            path,
            "Parameter '$name' is a cookie whose values are joined by a comma, and RFC 6265 excludes the comma " +
                "from a cookie value. Say `explode: true` for one pair per value, or take it as a query parameter.",
        )

        style == "form" && explode -> ListStyle.REPEATED

        style == "form" -> ListStyle.COMMA

        explode -> unsupported(
            path,
            "Parameter '$name' is encoded as '$style' and exploded, which puts each value under its own name " +
                "and leaves the separator meaning nothing. Drop `explode`, or drop the `style`.",
        )

        style == "spaceDelimited" -> ListStyle.SPACE

        style == "pipeDelimited" -> ListStyle.PIPE

        else -> unsupported(
            path,
            "Parameter '$name' is encoded as '$style', which is not a way Pelican reads a list.",
        )
    }

    /** What one element of a list parameter is, which is where its codec comes from. */
    private fun itemSchema(schema: JsonObj, name: String, path: JsonPath): JsonObj {
        // A refinement in Pelican narrows what one value decodes to, and there
        // is nothing it can say about how many of them arrived. `minItems: 1`
        // would therefore be written into the document again and enforced by
        // nobody, which is the silent weakening a strict import exists to
        // rule out. Required already says "at least one" and is enforced.
        val unenforceable = schema.fields.keys - listKeywords
        if (unenforceable.isNotEmpty()) {
            unsupported(
                path,
                "Parameter '$name' constrains the list itself with ${unenforceable.joinToString(", ")}. " +
                    "Pelican refines an element, not the list around it, so nothing would enforce that — " +
                    "put the constraint on `items`, or exclude the operation.",
            )
        }
        return schema["items"] as? JsonObj
            ?: unsupported(path, "Parameter '$name' is a list, and its schema does not say what one element is.")
    }

    /** What a list's own schema may say and still be described in full. */
    private val listKeywords = setOf("type", "items", "default", "description", "title", "example", "deprecated")

    private fun checkScalar(schema: JsonObj, name: String, ofAList: Boolean, path: JsonPath) {
        // "Parameter 'tags'" for a scalar, "An element of parameter 'tags'"
        // for a list — so the position the message names is the position the
        // reader has to go and change.
        val subject = if (ofAList) "An element of parameter '$name'" else "Parameter '$name'"

        when {
            schema.scalarType() == "array" -> unsupported(
                path,
                "$subject is itself a list. A parameter carries values, not lists of them; " +
                    "flatten it in the document, or exclude the operation.",
            )

            schema.scalarType() == "object" || schema["properties"] != null -> unsupported(
                path,
                "$subject is an object, and Pelican decodes a value from one string.",
            )

            schema["\$ref"] != null -> unsupported(
                path,
                "$subject points at a named schema. Named schemas describe bodies here; " +
                    "a parameter's type is written on the parameter.",
            )

            schema.scalarType() == null -> unsupported(
                path,
                "$subject has a schema that does not say what type it is.",
            )
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
            // A webhook has no template at all rather than one missing a
            // capture, and saying so is the difference between an edit somebody
            // can make and a message about an empty string.
            if (operation.webhookName != null) {
                unsupported(
                    operation.path,
                    "Path parameter '$it' is declared on a webhook, and a webhook has no path: it is sent " +
                        "to a URL a subscriber registered. Carry it in the body or in a header instead.",
                )
            }
            unsupported(operation.path, "Path parameter '$it' is declared, and ${operation.template} has no {$it}.")
        }
    }

    private val locations = setOf("path", "query", "header", "cookie")

    private val capturePattern = Regex("\\{([^}]+)}")
}
