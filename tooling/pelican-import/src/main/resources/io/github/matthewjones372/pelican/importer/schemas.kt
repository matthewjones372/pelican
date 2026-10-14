/**
 * The payload schemas, exactly as the document declared them.
 *
 * Handed to `ApiSpec` in place of a codec's schema source, so an imported
 * description publishes the document it came from and a client can be generated
 * with no JSON library present. A codec re-deriving these from the generated
 * classes would produce something very close and not the same.
 *
 * Pass a codec's own source — `%SPEC%(JacksonCodecs)` — where the classes are
 * the source of truth instead.
 */
object %NAME% : SchemaSource {

    // In pieces, and joined at runtime rather than by the compiler: a string
    // constant on the JVM cannot exceed 64KB, and a large document would.
    private val document: String = listOf(
%DOCUMENT%
    ).joinToString("")

    private val store: JsonObj = parseJson(document) as JsonObj

    /** The document's named schemas, under the names it knew them by. */
    private val declared: JsonObj = store["schemas"] as JsonObj

    /** Kotlin type name -> the name the document used, for the ones it named. */
    private val named: Map<String, String> = (store["names"] as JsonObj)
        .fields.mapValues { (_, value) -> (value as JsonStr).value }

    /** Kotlin type name -> a schema the document wrote out where it was used. */
    private val inline: JsonObj = store["inline"] as JsonObj

    override fun schema(type: KType, components: SchemaComponents): JsonObj {
        val name = (type.classifier as? KClass<*>)?.simpleName ?: return anything

        named[name]?.let { return reference(it, components) }

        // Written inline in the document, so it is written inline again: a
        // schema hoisted into `components` here would publish a name the
        // document never had.
        (inline[name] as? JsonObj)?.let { schema ->
            registerReferenced(schema, components)
            return schema
        }

        return structural(type, name, components)
    }

    private fun reference(documentName: String, components: SchemaComponents): JsonObj {
        if (!components.isRegistered(documentName)) {
            val schema = declared[documentName] as? JsonObj ?: anything
            // Registered before what it points at, so a type referring to
            // itself terminates.
            components.register(documentName, schema)
            registerReferenced(schema, components)
        }
        return components.ref(documentName)
    }

    /** Everything this schema points at, so no `$ref` is left dangling. */
    private fun registerReferenced(schema: JsonValue, components: SchemaComponents) {
        when (schema) {
            is JsonArr -> schema.items.forEach { registerReferenced(it, components) }

            is JsonObj -> schema.fields.forEach { (key, value) ->
                val target = (value as? JsonStr)?.value
                if (key == "\$ref" && target != null) {
                    reference(target.substringAfterLast('/'), components)
                } else {
                    registerReferenced(value, components)
                }
            }

            else -> Unit
        }
    }

    /**
     * The types no document names: the primitives, and the collections a
     * response is wrapped in. `List<Order>` is an array of whatever `Order`
     * resolves to above, which is the only place this recurses.
     */
    private fun structural(type: KType, name: String, components: SchemaComponents): JsonObj = when (name) {
        "String", "Char" -> jsonObj { "type" to "string" }
        "Int", "Short", "Byte" -> jsonObj { "type" to "integer"; "format" to "int32" }
        "Long" -> jsonObj { "type" to "integer"; "format" to "int64" }
        "Double" -> jsonObj { "type" to "number"; "format" to "double" }
        "Float" -> jsonObj { "type" to "number"; "format" to "float" }
        "Boolean" -> jsonObj { "type" to "boolean" }
        "UUID" -> jsonObj { "type" to "string"; "format" to "uuid" }
        "Instant", "OffsetDateTime", "ZonedDateTime" -> jsonObj { "type" to "string"; "format" to "date-time" }
        "LocalDate" -> jsonObj { "type" to "string"; "format" to "date" }
        "URI", "URL" -> jsonObj { "type" to "string"; "format" to "uri" }

        "List", "Set", "Collection", "Iterable", "Array" -> jsonObj {
            "type" to "array"
            put("items", argument(type, 0, components))
        }

        "Map" -> jsonObj {
            "type" to "object"
            put("additionalProperties", argument(type, 1, components))
        }

        // Nothing the document said and nothing this can work out: the empty
        // schema, which is JSON Schema for "anything" and is the truth here.
        else -> anything
    }

    private fun argument(type: KType, index: Int, components: SchemaComponents): JsonObj {
        val argument = type.arguments.getOrNull(index)?.type ?: return anything
        val schema = schema(argument, components)
        return if (argument.isMarkedNullable) schema.orNull() else schema
    }

    private val anything: JsonObj get() = JsonObj(emptyMap())
}
