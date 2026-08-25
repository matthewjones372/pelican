package io.github.matthewjones372.pelican

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.reflect.KType

/**
 * A codec for an `application/x-www-form-urlencoded` body carrying [type].
 *
 * Going through JSON is what keeps the codec modules agreeing — Jackson would
 * coerce `"3"` to an `Int` and kotlinx.serialization would refuse. Resolved
 * once at route-build time, so an undescribable form is a startup failure.
 */
@Suppress("UNCHECKED_CAST")
fun <T> Codecs.formCodec(type: KType): BodyCodec<T> =
    FormCodec(codec<Any?>(type), FormShape.of(type, this)) as BodyCodec<T>

/** The pairs an `application/x-www-form-urlencoded` body carries, in order. */
fun parseFormBody(text: String): List<Pair<String, String>> =
    text.split('&')
        .filter { it.isNotEmpty() }
        .map { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) decodeFormValue(pair) to ""
            else decodeFormValue(pair.substring(0, separator)) to decodeFormValue(pair.substring(separator + 1))
        }

/** The body those pairs travel as. */
fun renderFormBody(pairs: List<Pair<String, String>>): String =
    pairs.joinToString("&") { (name, value) -> encodeFormValue(name) + "=" + encodeFormValue(value) }

private fun decodeFormValue(raw: String): String = URLDecoder.decode(raw, StandardCharsets.UTF_8)

private fun encodeFormValue(raw: String): String = URLEncoder.encode(raw, StandardCharsets.UTF_8)

private class FormCodec(
    private val json: BodyCodec<Any?>,
    private val shape: FormShape,
) : BodyCodec<Any?> {

    override fun decodeFromString(text: String): Any? =
        json.decodeFromString(shape.toJson(parseFormBody(text)).render())

    override fun encodeToString(value: Any?): String =
        renderFormBody(shape.toPairs(parseJson(json.encodeToString(value))))
}

private enum class Kind { STRING, INTEGER, NUMBER, BOOLEAN }

private class Field(val kind: Kind, val repeated: Boolean)

/**
 * What each field of a form means, read off the published schema once.
 */
private class FormShape(private val fields: Map<String, Field>) {

    fun toJson(pairs: List<Pair<String, String>>): JsonObj {
        val byName = LinkedHashMap<String, MutableList<String>>()
        // Dropped rather than rejected: browsers send more than the form
        // declares — the submit button's name, a CSRF token a filter handled.
        pairs.filter { it.first in fields }
            .forEach { (name, value) -> byName.getOrPut(name) { mutableListOf() } += value }

        return JsonObj(
            byName.mapNotNull { (name, values) ->
                val field = fields.getValue(name)
                when {
                    field.repeated -> name to JsonArr(values.map { scalar(name, field.kind, it) })

                    // An untouched HTML field is submitted empty, and "" is
                    // not a number. Absent lets the type's own default apply.
                    values.first().isEmpty() && field.kind != Kind.STRING -> null

                    else -> name to scalar(name, field.kind, values.first())
                }
            }.toMap(),
        )
    }

    fun toPairs(value: JsonValue): List<Pair<String, String>> {
        require(value is JsonObj) {
            "A form body has to be an object, and this one encoded as ${value::class.simpleName}"
        }
        return value.fields.flatMap { (name, field) ->
            when (field) {
                is JsonNull -> emptyList()
                is JsonArr -> field.items.map { name to plain(name, it) }
                else -> listOf(name to plain(name, field))
            }
        }
    }

    private fun plain(name: String, value: JsonValue): String = when (value) {
        is JsonStr -> value.value
        is JsonNum -> value.value.toString()
        is JsonBool -> value.value.toString()
        else -> error("A form field cannot carry a nested value, and '$name' does")
    }

    /**
     * One field, as the type the schema says it is. A value that does not parse
     * gets the same [DecodeFailure] a query parameter gets, rather than the
     * JSON library's complaint about a document the caller never wrote.
     */
    private fun scalar(name: String, kind: Kind, raw: String): JsonValue = when (kind) {
        Kind.STRING -> JsonStr(raw)
        Kind.INTEGER -> JsonNum(raw.toLongOrNull() ?: throw DecodeFailure(name, raw, "a whole number"))
        Kind.NUMBER -> JsonNum(raw.toDoubleOrNull() ?: throw DecodeFailure(name, raw, "a number"))
        Kind.BOOLEAN -> JsonBool(BooleanCodec.decode(name, raw))
    }

    companion object {
        fun of(type: KType, schemas: SchemaSource): FormShape {
            val components = SchemaRegistry()
            val schema = resolve(schemas.schema(type, components), components)
            val properties = schema["properties"] as? JsonObj
                ?: error(
                    "A form body has to be an object with properties, and the schema for $type is not: " +
                        schema.render(),
                )

            return FormShape(
                properties.fields.mapValues { (name, property) ->
                    field(name, type, resolve(property as JsonObj, components), components)
                },
            )
        }

        private fun field(name: String, owner: KType, schema: JsonObj, components: SchemaRegistry): Field {
            val type = schema.declaredType()
            if (type == "array") {
                val items = resolve(schema["items"] as? JsonObj ?: emptyJsonObj, components)
                val element = field(name, owner, items, components)
                require(!element.repeated) { "A form field cannot carry an array of arrays, and '$name' does" }
                return Field(element.kind, repeated = true)
            }
            val kind = when (type) {
                "string", null -> Kind.STRING

                "integer" -> Kind.INTEGER

                "number" -> Kind.NUMBER

                "boolean" -> Kind.BOOLEAN

                else -> error(
                    "A form body carries strings, so the property '$name' of $owner cannot have " +
                        "type '$type'. Take the body as a jsonBody instead, or flatten the property.",
                )
            }
            return Field(kind, repeated = false)
        }

        /**
         * What a schema calls itself, reading past the `"null"` a nullable
         * property adds. OpenAPI 3.1 spells nullable as a type array, and all
         * three schema sources emit it, so reading only the string spelling
         * left an `Int?` field a string — a form value that then reached the
         * codec quoted.
         */
        private fun JsonObj.declaredType(): String? = when (val type = this["type"]) {
            is JsonStr -> type.value
            is JsonArr -> type.items.filterIsInstance<JsonStr>().map { it.value }.firstOrNull { it != "null" }
            else -> null
        }

        /** A `$ref` followed to the schema it names; a form needs the properties. */
        private fun resolve(schema: JsonObj, components: SchemaRegistry): JsonObj {
            val ref = (schema["\$ref"] as? JsonStr)?.value ?: return schema
            val name = ref.substringAfterLast('/')
            val target = components.all()[name] as? JsonObj
                ?: error("A form body's schema refers to '$ref', which nothing defined")
            return resolve(target, components)
        }
    }
}
