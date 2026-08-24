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
 * `jackson-module-kotlin` makes the *parser* honour data-class defaults and
 * nullability, but neither survives into schema generation: Jackson's
 * `hasRequiredMarker` has no way to say "optional because it has a default", so
 * swagger marks every non-null property required. Left alone, the document
 * would declare `{"item":"rope"}` invalid for a body the server happily
 * accepts — the docs contradicting the service they describe.
 *
 * So the primary constructor is consulted directly, and it is the same source
 * of truth Jackson itself uses when it binds the body.
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

        // With resolveAsRef the returned schema is only a pointer; the model
        // being described is the one the context has just defined.
        val component = resolved.`$ref`?.substringAfterLast('/')
        val target = component?.let { context.definedModels[it] } ?: resolved

        remember(component, target, context, kClass)
        patch(target, kClass)
        return resolved
    }

    /**
     * Which class swagger-core described under which component name.
     *
     * Recorded here because here is the only place both halves are in one
     * hand. A component is a name and a lump of JSON by the time it reaches
     * [JacksonCodecs], and the union rewrite has to read Jackson's annotations
     * off the class it came from — including for types nobody named at the top
     * level, a branch of a hierarchy or a property four levels down, which is
     * exactly the set this resolver is handed one at a time.
     *
     * Rederiving the name instead would mean reimplementing swagger's own
     * naming, and the first thing it would get wrong is the one this repository
     * already documents: an instantiated generic is `BoxInner`, not `Box`.
     *
     * A model reached through a `$ref` names itself; one being defined right
     * now does not, and is found by identity among the models the context has
     * just gained. Both happen — a branch resolved on the way through its
     * hierarchy takes the second path.
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

        // A parameter with no matching property was renamed by @JsonProperty;
        // leave that one be.
        val described = parameters.mapNotNull { parameter ->
            val name = parameter.name ?: return@mapNotNull null
            val property = properties[name] ?: return@mapNotNull null
            Triple(name, parameter, property)
        }

        described.forEach { (name, parameter, property) ->
            properties[name] = property.matching(parameter.type)
        }

        // Nullable, or defaulted — a default means the field may be omitted,
        // whatever its type.
        val optional = described
            .filter { (_, parameter, _) -> parameter.type.isMarkedNullable || parameter.isOptional }
            .map { (name, _, _) -> name }
            .toSet()
        if (optional.isEmpty()) return

        // Rebuilt in declaration order rather than swagger's alphabetical one,
        // so the list reads like the class it describes.
        val required = properties.keys.filter { it in schema.required.orEmpty() && it !in optional }
        schema.required = required.ifEmpty { null }
    }

    /**
     * The schema, made to agree with [type] about null — at every depth [type]
     * has, not only at the top.
     *
     * `List<Inner?>` is the shape that makes this necessary. The property is
     * not nullable, so nothing about the property says anything is wrong, and
     * swagger-core's own resolver has erased the element's nullability long
     * before we see it — `List<Inner?>` and `List<Inner>` reach it as the same
     * Java type. The Kotlin type is the only witness left, so the two are
     * walked together: `items` against the element type, `additionalProperties`
     * against the value type, as deep as the generics go.
     *
     * This is core's `JsonObj.withNullabilityOf` written against `Schema`, and
     * the duplication is the price of the split: that one runs over a finished
     * schema, where a body type's own generics are still readable, and this one
     * has to run *here*, because a property's Kotlin type is only visible from
     * the constructor and never reaches `JacksonCodecs`. Keeping them in step
     * is `CodecAgreementTest`'s job, which now covers both.
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
     * The property, widened to admit null, in swagger's own object model.
     *
     * swagger-core has no notion of Kotlin nullability at all — that is the
     * reason this resolver exists — so the null in the union is written here
     * rather than taken from anything the library worked out. Asked for 3.1 a
     * property carries a `types` set, and adding `"null"` to it is the whole
     * change; a property that is only a `$ref` has no `types` to add to, and
     * goes under `anyOf` beside a bare null schema instead. That is core's
     * `JsonObj.orNull` spelled against `Schema`, and the two are held together
     * by `CodecAgreementTest` comparing the documents they end up in.
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
