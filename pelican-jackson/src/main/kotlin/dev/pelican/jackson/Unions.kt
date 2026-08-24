package dev.pelican.jackson

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import dev.pelican.JsonArr
import dev.pelican.JsonObj
import dev.pelican.JsonStr
import dev.pelican.JsonValue
import dev.pelican.jsonArr
import dev.pelican.jsonObj
import dev.pelican.jsonStrings
import kotlin.reflect.KClass

/**
 * Sealed hierarchies, written the way the annotations say rather than the way
 * swagger-core guessed.
 *
 * swagger-core describes a `@JsonTypeInfo` hierarchy the way OpenAPI 3.0 had
 * to: a parent holding the `discriminator`, and one child per branch declaring
 * `allOf: [$ref parent, ...]`. That spelling has no room for a `mapping`, and
 * swagger-core writes none — the names in `@JsonSubTypes.Type(name = ...)`
 * never reach its schema model at all. By OpenAPI's implicit-mapping rule the
 * value selecting a branch is then the branch's *schema* name, so a service
 * whose wire value is `bank_transfer` publishes a document saying
 * `BankTransfer`. Nothing downstream can tell, which makes it the worst kind of
 * wrong: a document that is confidently different from the service it
 * describes.
 *
 * So the hierarchies are rewritten here, from the annotations, into the
 * spelling 3.1 has for them and `KotlinxCodecs` already publishes: `oneOf` over
 * the branches with a `discriminator` naming every one. Two codecs, one
 * spelling — and the second half of that sentence is worth as much as the
 * first, because a document that changes shape when a service swaps JSON
 * libraries is a document its readers cannot depend on.
 *
 * What the parent held is pushed down rather than dropped. A `oneOf` branch is
 * the whole payload, so a property declared on the sealed interface belongs in
 * every branch that inherits it; leaving the `allOf` in place instead would
 * point each branch back at a parent that is now a `oneOf` of those same
 * branches, which is a document nothing can resolve.
 */
internal fun unionsRewritten(schemas: Map<String, JsonObj>, classes: Map<String, KClass<*>>): Map<String, JsonObj> {
    val hierarchies = Hierarchies(schemas, classes + parentsPointedAt(schemas, classes))
    if (hierarchies.none) return schemas
    return schemas.mapValues { (name, schema) -> hierarchies.rewrite(name, schema) }
}

/**
 * The hierarchies swagger-core defined without ever resolving, found the only
 * way the document offers: upwards, from the branch that points at one.
 *
 * A parent normally arrives as a resolved type like any other and
 * `KotlinAwareModelResolver` records the class it came from. One whose
 * `@JsonTypeInfo` puts the type off the payload does not — swagger-core defines
 * the model while writing the branch's `allOf` and never asks the converter
 * chain about the parent itself — and that is exactly the hierarchy this most
 * needs to see, because it is the one whose wire format no `discriminator`
 * can describe. Missing it would mean publishing branches as though the wrapper
 * around them were not there.
 */
private fun parentsPointedAt(
    schemas: Map<String, JsonObj>,
    classes: Map<String, KClass<*>>,
): Map<String, KClass<*>> = buildMap {
    schemas.forEach { (name, schema) ->
        val kClass = classes[name] ?: return@forEach
        val parent = schema.parentReference()?.takeIf { it !in classes } ?: return@forEach
        val declared = (kClass.java.interfaces.toList() + listOfNotNull(kClass.java.superclass))
            .filter { it.getAnnotation(JsonTypeInfo::class.java) != null }
        val match = declared.singleOrNull() ?: declared.firstOrNull { it.simpleName == parent }
        if (match != null) putIfAbsent(parent, match.kotlin)
    }
}

/** The schema this one declares itself part of, in the 3.0 spelling swagger-core writes. */
private fun JsonObj.parentReference(): String? =
    (this["allOf"] as? JsonArr)?.items.orEmpty()
        .firstNotNullOfOrNull { ((it as? JsonObj)?.get(REF) as? JsonStr)?.value }
        ?.substringAfterLast('/')

/** One hierarchy: what tells the branches apart, and what each is called on the wire. */
private class Union(val property: String, val branches: List<Branch>)

private class Branch(val wire: String, val component: String)

private class Hierarchies(private val schemas: Map<String, JsonObj>, classes: Map<String, KClass<*>>) {

    /**
     * The class a branch is, back to the name it was described under.
     *
     * Only the names in this batch, because only they are being rewritten. A
     * class described under two names is an instantiated generic — `Box<Inner>`
     * and `Box<Other>` — and the first one wins, which is as good as any
     * answer: a generic sealed hierarchy instantiated twice is refused below,
     * where the branch cannot be resolved to one schema.
     */
    private val nameOf: Map<KClass<*>, String> =
        classes.entries.filter { (name, _) -> name in schemas }
            .reversed()
            .associate { (name, kClass) -> kClass to name }

    /**
     * Parent component -> its branches, in the order `@JsonSubTypes` listed
     * them.
     *
     * Found from the annotations rather than from the `discriminator`
     * swagger-core wrote, because the annotations are what Jackson acts on and
     * this whole file exists to publish what Jackson acts on. The two differ in
     * both directions: swagger writes a `discriminator` for a shape it cannot
     * name the branches of, and it writes none at all for a `@JsonTypeInfo`
     * that puts the type somewhere other than on the payload — which is a
     * hierarchy Jackson is very much acting on, and one no `discriminator` can
     * describe. [unionOf] refuses that rather than publishing the object it
     * would otherwise leave behind.
     */
    private val unions: Map<String, Union> = schemas.keys.mapNotNull { name ->
        val kClass = classes[name] ?: return@mapNotNull null
        unionOf(name, schemas.getValue(name), kClass)?.let { name to it }
    }.toMap()

    /** Branch component -> the hierarchy it belongs to. */
    private val parentOf: Map<String, String> =
        unions.flatMap { (parent, union) -> union.branches.map { it.component to parent } }.toMap()

    val none: Boolean get() = unions.isEmpty()

    fun rewrite(name: String, schema: JsonObj): JsonObj = when {
        name in unions -> parent(schema, unions.getValue(name))
        name in parentOf -> branch(name, schema)
        else -> schema
    }

    /**
     * The hierarchy as 3.1 spells it. Everything else the parent carried is
     * pushed into the branches by [branch], so what is left here is the choice
     * and the name of each option.
     */
    private fun parent(schema: JsonObj, union: Union): JsonObj = jsonObj {
        putIfNotNull("description", (schema["description"] as? JsonStr)?.value)
        put("oneOf", jsonArr(union.branches.map { reference(it.component) }))
        put(
            "discriminator",
            jsonObj {
                "propertyName" to union.property
                put("mapping", JsonObj(union.branches.associate { Pair(it.wire, JsonStr(REFERENCE + it.component)) }))
            },
        )
    }

    /**
     * A branch as the whole payload it is: what the hierarchy declared above
     * it, then what it declares itself.
     *
     * The discriminator property is left out, of the branches and of the parent
     * alike. It is what the `discriminator` is *about*, and a payload type
     * carrying it as an ordinary property is the shape kotlinx.serialization
     * refuses outright and the generated Kotlin here does not write either — so
     * declaring it would put a field in the document that no class this
     * repository generates from that document has.
     */
    private fun branch(name: String, schema: JsonObj): JsonObj {
        val carried = discriminatorsAbove(name)
        val properties = LinkedHashMap<String, JsonValue>()
        properties += inheritedProperties(name)
        properties += schema.ownProperties()
        carried.forEach { properties.remove(it) }

        val required = (inheritedRequired(name) + schema.ownRequired()) - carried

        val kept = schema.fields - "allOf" - "properties" - "required" - "discriminator" - "type"
        return JsonObj(
            LinkedHashMap<String, JsonValue>().apply {
                put("type", JsonStr("object"))
                putAll(kept)
                if (properties.isNotEmpty()) put("properties", JsonObj(properties))
                if (required.isNotEmpty()) put("required", jsonStrings(required.toList()))
            },
        )
    }

    /**
     * The properties a branch inherits, outermost hierarchy first.
     *
     * Walked rather than read off the branch, because swagger-core wrote them
     * on the parent and left the branch pointing at it — and a hierarchy whose
     * branches are themselves hierarchies has a parent in the middle that ends
     * up holding nothing, so its share has to travel two levels down.
     */
    private fun inheritedProperties(name: String): Map<String, JsonValue> {
        val parent = parentOf[name] ?: return emptyMap()
        val schema = schemas[parent] ?: return emptyMap()
        return inheritedProperties(parent) + schema.ownProperties()
    }

    private fun inheritedRequired(name: String): Set<String> {
        val parent = parentOf[name] ?: return emptySet()
        val schema = schemas[parent] ?: return emptySet()
        return inheritedRequired(parent) + schema.ownRequired()
    }

    /** Every property name that a `discriminator` above this branch claims. */
    private fun discriminatorsAbove(name: String): Set<String> {
        val parent = parentOf[name] ?: return emptySet()
        return discriminatorsAbove(parent) + setOfNotNull(unions[parent]?.property)
    }

    /**
     * The hierarchy [kClass] declares, or null where Jackson declares none.
     *
     * A `discriminator` with no `@JsonTypeInfo` under it is somebody describing
     * a hierarchy through swagger's own annotations, and what they wrote is
     * left exactly as they wrote it. A `@JsonTypeInfo` Jackson *does* act on is
     * the case this exists for, and the shapes of it OpenAPI cannot describe
     * are refused rather than published as something else — see [refuse].
     */
    private fun unionOf(name: String, schema: JsonObj, kClass: KClass<*>): Union? {
        val typeInfo = kClass.java.getAnnotation(JsonTypeInfo::class.java) ?: return null
        val subtypes = kClass.java.getAnnotation(JsonSubTypes::class.java)?.value?.toList()
            ?: refuse(name, NO_SUBTYPES)

        if (typeInfo.include !in CARRIED_AS_A_PROPERTY) refuse(name, notAProperty(typeInfo.include))
        if (typeInfo.use !in NAMEABLE) refuse(name, notNameable(typeInfo.use))

        val branches = subtypes.map { subtype ->
            val component = nameOf[subtype.value]
                ?: refuse(name, unresolved(subtype.value))
            Branch(wireName(typeInfo.use, subtype), component)
        }

        // swagger-core's reading of the same annotation, preferred over the
        // annotation's own field because it is what the rest of the schema was
        // built around — and it fills in the default a bare `@JsonTypeInfo`
        // leaves to Jackson.
        val property = schema.discriminatorProperty()
            ?: typeInfo.property.ifEmpty { typeInfo.use.defaultPropertyName }

        val repeated = branches.groupBy { it.wire }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) refuse(name, repeatedValues(property, repeated))

        return Union(property, branches)
    }
}

// ------------------------------------------------------------------- reading

private fun JsonObj.discriminatorProperty(): String? =
    ((this["discriminator"] as? JsonObj)?.get("propertyName") as? JsonStr)?.value

/**
 * The properties this schema declares itself, wherever it happened to declare
 * them: a branch's own are inside the second member of its `allOf`, and a
 * parent's are at the top.
 */
private fun JsonObj.ownProperties(): Map<String, JsonValue> = buildMap {
    parts().forEach { part -> putAll(((part["properties"] as? JsonObj)?.fields).orEmpty()) }
}

private fun JsonObj.ownRequired(): Set<String> = buildSet {
    parts().forEach { part ->
        (part["required"] as? JsonArr)?.items.orEmpty().forEach { name ->
            (name as? JsonStr)?.let { add(it.value) }
        }
    }
}

/** This schema and the members of its `allOf` that are not references to another. */
private fun JsonObj.parts(): List<JsonObj> =
    (this["allOf"] as? JsonArr)?.items.orEmpty().filterIsInstance<JsonObj>().filter { it[REF] == null } +
        listOf(this)

private fun reference(component: String): JsonObj = jsonObj { REF to REFERENCE + component }

private const val REF = "\u0024ref"

/**
 * What a branch is called on the wire, by the rule Jackson itself uses: the
 * name the hierarchy gave it, then the one the branch gave itself, then the
 * class's simple name.
 */
private fun wireName(use: JsonTypeInfo.Id, subtype: JsonSubTypes.Type): String {
    if (use == JsonTypeInfo.Id.CLASS) return subtype.value.java.name
    val declared = subtype.name.ifEmpty { subtype.names.firstOrNull().orEmpty() }
    if (declared.isNotEmpty()) return declared
    return subtype.value.java.getAnnotation(JsonTypeName::class.java)?.value?.takeIf { it.isNotEmpty() }
        ?: subtype.value.java.simpleName
}

private const val REFERENCE = "#/components/schemas/"

private val CARRIED_AS_A_PROPERTY = setOf(JsonTypeInfo.As.PROPERTY, JsonTypeInfo.As.EXISTING_PROPERTY)

private val NAMEABLE = setOf(JsonTypeInfo.Id.NAME, JsonTypeInfo.Id.CLASS)

// ---------------------------------------------------------- what to say

/**
 * A hierarchy that cannot be described, refused at the point the document is
 * built.
 *
 * The alternative was to publish swagger-core's spelling for these and say
 * nothing, which is what happened before and is the defect this file exists to
 * fix: a document that disagrees with the service is worse than no document,
 * because it is believed. The message names the class, because that is where
 * the annotation is.
 */
private fun refuse(component: String, reason: String): Nothing =
    error("Cannot describe the hierarchy `$component`: $reason")

private const val NO_SUBTYPES =
    "it is annotated `@JsonTypeInfo` but nothing lists its branches, so the document would say a payload " +
        "carries a type property and never say which values it takes. Add `@JsonSubTypes` naming every " +
        "branch, which is also what Jackson needs to read one back."

private fun notAProperty(include: JsonTypeInfo.As): String =
    "`@JsonTypeInfo(include = As.$include)` does not put the type on the payload as a property, and a " +
        "`discriminator` can only name a property of the payload. Use `As.PROPERTY`, or describe the " +
        "wrapper shape as an ordinary class."

private fun notNameable(use: JsonTypeInfo.Id): String =
    "`@JsonTypeInfo(use = Id.$use)` does not give each branch a name this can publish. `Id.NAME` with a " +
        "`@JsonSubTypes` name per branch is the one that travels; `Id.CLASS` is published as it is written."

private fun unresolved(subtype: KClass<*>): String =
    "its branch `${subtype.simpleName}` is not among the schemas described beside it, so nothing in the " +
        "document could be mapped to the value that selects it. A branch has to be a described type — a " +
        "class swagger-core can resolve, and not a second instantiation of a generic one."

private fun repeatedValues(property: String, values: Collection<String>): String =
    "two of its branches carry the same `$property` of ${values.joinToString(", ") { "`$it`" }}. Jackson " +
        "reads whichever it registered last; give each branch a name of its own."
