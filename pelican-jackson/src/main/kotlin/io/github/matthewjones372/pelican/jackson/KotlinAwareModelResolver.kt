package io.github.matthewjones372.pelican.jackson

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.jackson.ModelResolver
import io.swagger.v3.oas.models.media.JsonSchema
import io.swagger.v3.oas.models.media.Schema
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor

/**
 * swagger-core's resolver, taught the two things about Kotlin that Jackson's
 * required-marker cannot carry.
 *
 * `jackson-module-kotlin` makes the parser honour data-class defaults and
 * nullability, but `hasRequiredMarker` cannot say "optional because it has a
 * default", so swagger marks every non-null property required — a document
 * declaring invalid a body the server happily accepts.
 *
 * The primary constructor is consulted directly instead, which is the source
 * of truth Jackson itself binds against.
 */
internal class KotlinAwareModelResolver(mapper: ObjectMapper) : ModelResolver(mapper) {

    /** Component name -> the class swagger-core described under it. See [remember]. */
    val described = LinkedHashMap<String, KClass<*>>()

    override fun resolve(
        annotatedType: AnnotatedType,
        context: ModelConverterContext,
        chain: MutableIterator<ModelConverter>,
    ): Schema<*>? {
        val resolved = super.resolve(annotatedType, context, chain) ?: return null

        val kClass = runCatching { _mapper.constructType(annotatedType.type).rawClass.kotlin }
            .getOrNull() ?: return resolved

        // With resolveAsRef the returned schema is a pointer; the model is
        // the one the context has just defined.
        val component = resolved.`$ref`?.substringAfterLast('/')
        val target = component?.let { context.definedModels[it] } ?: resolved

        remember(component, target, context, kClass)
        patch(target, kClass)
        return resolved
    }

    /**
     * Which class swagger-core described under which component name. Here
     * because here is the only place both halves are in one hand: by the time a
     * component reaches [JacksonCodecs] it is a name and a lump of JSON, and the
     * union rewrite has to read Jackson's annotations off the class.
     *
     * Rederiving the name would mean reimplementing swagger's own naming, whose
     * first surprise is that an instantiated generic is `BoxInner`, not `Box`.
     */
    private fun remember(
        component: String?,
        target: Schema<*>,
        context: ModelConverterContext,
        kClass: KClass<*>,
    ) {
        val name = component
            ?: context.definedModels.entries.firstOrNull { (_, model) -> model === target }?.key
            ?: return
        described.putIfAbsent(name, kClass)
    }

    private fun patch(schema: Schema<*>, kClass: KClass<*>) {
        val properties = schema.properties ?: return
        val parameters = kClass.primaryConstructor?.parameters ?: return

        // No matching property means @JsonProperty renamed it; leave it be.
        val described = parameters.mapNotNull { parameter ->
            val name = parameter.name ?: return@mapNotNull null
            val property = properties[name] ?: return@mapNotNull null
            Triple(name, parameter, property)
        }

        described.forEach { (name, parameter, property) ->
            properties[name] = property.matching(parameter.type)
        }

        // A default means the field may be omitted, whatever its type.
        val optional = described
            .filter { (_, parameter, _) -> parameter.type.isMarkedNullable || parameter.isOptional }
            .map { (name, _, _) -> name }
            .toSet()
        if (optional.isEmpty()) return

        // Declaration order rather than swagger's alphabetical one.
        val required = properties.keys.filter { it in schema.required.orEmpty() && it !in optional }
        schema.required = required.ifEmpty { null }
    }

    /**
     * The schema, made to agree with [type] about null at every depth.
     *
     * `List<Inner?>` needs it: the property is not nullable, and swagger-core
     * has erased the element's nullability by the time we see it. The Kotlin
     * type is the only witness left.
     */
    private fun Schema<*>.matching(type: KType): Schema<*> {
        val element = type.arguments.lastOrNull()?.type
        if (element != null) {
            items?.let { items = it.matching(element) }
            (additionalProperties as? Schema<*>)?.let { additionalProperties = it.matching(element) }
        }
        return if (type.isMarkedNullable) orNull() else this
    }

    /**
     * The property, widened to admit null. Asked for 3.1 a property carries a
     * `types` set and adding `"null"` is the whole change; one that is only a
     * `$ref` has no `types`, so it goes under `anyOf` beside a bare null
     * schema. Core's `JsonObj.orNull` against `Schema`.
     */
    private fun Schema<*>.orNull(): Schema<*> {
        val widened = types ?: return anyOfNull()
        types = LinkedHashSet(widened).apply { add("null") }
        return this
    }

    private fun Schema<*>.anyOfNull(): Schema<*> = JsonSchema().apply {
        anyOf = listOf(this@anyOfNull, JsonSchema().apply { types = linkedSetOf("null") })
    }
}
