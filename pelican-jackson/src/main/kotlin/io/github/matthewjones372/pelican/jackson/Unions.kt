package io.github.matthewjones372.pelican.jackson

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.jsonArr
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import java.lang.reflect.Modifier
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
 *
 * A hierarchy under a hierarchy is published flat, for the same reason and with
 * the same answer: Jackson resolves the declared base type's type id and no
 * other, following `@JsonSubTypes` transitively to find what it selects. So a
 * leaf two levels down is chosen by the root's property under its own name, and
 * the level between them is a Kotlin relation with nothing on the wire. The
 * document says exactly that — one `oneOf` over the leaves — which is also what
 * `KotlinxCodecs` publishes for the same classes, kotlinx.serialization
 * flattening a sealed hierarchy the same way. The two levels of `@JsonTypeInfo`
 * that would be needed to say anything else are refused; see [nestedTypeInfo].
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

private class Hierarchies(private val schemas: Map<String, JsonObj>, private val classes: Map<String, KClass<*>>) {

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

    /** Every component that is a branch of some hierarchy. */
    private val branches: Set<String> =
        unions.values.flatMap { union -> union.branches.map { it.component } }.toSet()

    val none: Boolean get() = unions.isEmpty()

    fun rewrite(name: String, schema: JsonObj): JsonObj = when {
        name in unions -> parent(schema, unions.getValue(name))
        name in branches -> branch(name, schema)
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
        val above = above(name)
        val carried = above.mapNotNull { unions[it]?.property }.toSet()
        val inherited = above.mapNotNull { schemas[it] }
        val properties = (
            inherited.fold(emptyMap<String, JsonValue>()) { all, level -> all + level.ownProperties() } +
                schema.ownProperties()
            ) - carried
        val required = (inherited.flatMap { it.ownRequired() } + schema.ownRequired()).toSet() - carried

        // Whatever else the branch carried — a description, a title, an example
        // — kept as it was. Only the four keys this rewrites are rebuilt.
        val kept = schema.fields - "allOf" - "properties" - "required" - "discriminator" - "type"
        return JsonObj(
            buildMap {
                put("type", JsonStr("object"))
                putAll(kept)
                if (properties.isNotEmpty()) put("properties", JsonObj(properties))
                if (required.isNotEmpty()) put("required", jsonStrings(required.toList()))
            },
        )
    }

    /**
     * The described types above this branch, outermost first.
     *
     * Taken from the classes rather than from the hierarchies, because a
     * middle level is not a branch of anything — it is flattened away by
     * [leaves] — and its share of the properties still has to reach the leaf
     * that will be on the wire. The class chain is the one place that relation
     * survives the flattening.
     */
    private fun above(name: String): List<String> {
        val kClass = classes[name] ?: return emptyList()

        // Each parent's own ancestors before the parent itself, so the list
        // reads outermost first and a property declared high up is overwritten
        // by a level that redeclares it rather than the other way round.
        fun walk(type: Class<*>): List<String> =
            (type.interfaces.toList() + listOfNotNull(type.superclass))
                .flatMap { parent -> walk(parent) + listOfNotNull(nameOf[parent.kotlin]) }

        return walk(kClass.java).distinct()
    }

    /**
     * The hierarchy [kClass] declares, or null where Jackson declares none.
     *
     * A `discriminator` with no `@JsonTypeInfo` under it is somebody describing
     * a hierarchy through swagger's own annotations, and what they wrote is
     * left exactly as they wrote it. A `@JsonTypeInfo` Jackson *does* act on is
     * the case this exists for, and the shapes of it OpenAPI cannot describe
     * are refused rather than published as something else — see [refuse].
     *
     * A `@JsonSubTypes` under a hierarchy is a hierarchy too, on the same
     * property: Jackson walks the annotation transitively and registers what it
     * finds under the *root's* type id, so a middle level's branches are
     * selected by the root's property, and the middle level itself is selected
     * by nothing. That is why the type info is looked for above as well as
     * here.
     */
    private fun unionOf(name: String, schema: JsonObj, kClass: KClass<*>): Union? {
        val declared = kClass.java.getAnnotation(JsonTypeInfo::class.java)
        val inherited = typeInfoAbove(kClass)
        if (declared != null && inherited != null) refuse(name, ownTypeInfoUnder(inherited.second))
        val typeInfo = declared ?: inherited?.first ?: return null
        val subtypes = kClass.java.getAnnotation(JsonSubTypes::class.java)?.value?.toList()
            ?: if (declared != null) refuse(name, NO_SUBTYPES) else return null

        if (typeInfo.include !in CARRIED_AS_A_PROPERTY) refuse(name, notAProperty(typeInfo.include))
        if (typeInfo.use !in NAMEABLE) refuse(name, notNameable(typeInfo.use))

        val branches = leaves(name, typeInfo.use, subtypes, setOf(kClass))

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

    /**
     * The classes a payload can actually be, with the middle levels walked
     * through rather than published.
     *
     * Jackson resolves one type id and one only — the declared base type's —
     * and finds subtypes through `@JsonSubTypes` transitively, so a leaf two
     * levels down is selected by the root's property under its own name and the
     * level between them is never a value on the wire at all. Listing that
     * level as a branch is what this used to do, and it published a document
     * saying payloads carry `kind: "electronic"` for a service that writes
     * `kind: "card"` — the confidently-wrong document this file exists to stop.
     *
     * [seen] is the classes on the way down to here — the path, not everything
     * met so far — so a `@JsonSubTypes` that points back up is a refusal with a
     * class name in it rather than a stack overflow inside a build. Two
     * branches that legitimately reach one class are a different complaint, and
     * the repeated-value check above is where it is made.
     */
    private fun leaves(
        root: String,
        use: JsonTypeInfo.Id,
        subtypes: List<JsonSubTypes.Type>,
        seen: Set<KClass<*>>,
    ): List<Branch> = subtypes.flatMap { subtype ->
        val kClass = subtype.value
        if (kClass in seen) refuse(root, circular(kClass))
        if (kClass.java.getAnnotation(JsonTypeInfo::class.java) != null) refuse(root, nestedTypeInfo(kClass, root))

        val below = kClass.java.getAnnotation(JsonSubTypes::class.java)?.value?.toList()
        when {
            below == null && kClass.cannotBeAPayload() -> refuse(root, abstractBranch(kClass))

            below == null -> listOf(Branch(wireName(use, subtype), nameOf[kClass] ?: refuse(root, unresolved(kClass))))

            // A concrete class that is also a level of the hierarchy is both a
            // branch and a parent, and publishing it would mean a `oneOf`
            // branch that is itself a `oneOf` — a payload whose type is spread
            // over two values, which nothing reads back. See [concreteLevel].
            !kClass.cannotBeAPayload() -> refuse(root, concreteLevel(kClass))

            else -> leaves(root, use, below, seen + kClass)
        }
    }

    /** The nearest `@JsonTypeInfo` above [kClass], with the component that carries it. */
    private fun typeInfoAbove(kClass: KClass<*>): Pair<JsonTypeInfo, String>? {
        (kClass.java.interfaces.toList() + listOfNotNull(kClass.java.superclass)).forEach { parent ->
            parent.getAnnotation(JsonTypeInfo::class.java)?.let { typeInfo ->
                return typeInfo to (nameOf[parent.kotlin] ?: parent.simpleName)
            }
            typeInfoAbove(parent.kotlin)?.let { return it }
        }
        return null
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

/**
 * Whether nothing on the wire can be this class, which is what makes it a level
 * of a hierarchy rather than a branch of one.
 *
 * Asked of the JVM rather than of `KClass.isAbstract`, which answers false for
 * a `sealed interface` — sealed is its own modality in Kotlin — and would have
 * made the one shape this is here for look like a concrete branch.
 */
private fun KClass<*>.cannotBeAPayload(): Boolean =
    java.isInterface || java.modifiers and Modifier.ABSTRACT != 0

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
        "reads whichever it registered last; give each branch a name of its own. A name repeated across " +
        "two levels of one hierarchy reads the same way, since Jackson flattens the levels into one set."

/**
 * The two refusals a nested hierarchy earns, and the reason there is no
 * document for either.
 *
 * Jackson resolves the *declared* base type's type id and nothing else:
 * `@JsonSubTypes` is followed transitively, so a leaf below a middle level is
 * still selected by the root's property under its own name, but a second
 * `@JsonTypeInfo` on that middle level is ignored on the way in and preferred
 * on the way out. A hierarchy annotated at two levels therefore writes one type
 * id and reads a different one — `JacksonCodecs` cannot round-trip its own
 * payloads through it — and no document describes that, because there is
 * nothing coherent to describe. It has been a wontfix in jackson-databind since
 * 2013; see FasterXML/jackson-databind#2957 and the four issues it collects.
 *
 * The way out is the flattening Jackson already does: one `@JsonTypeInfo`, at
 * the root, and `@JsonSubTypes` at every level below it. The nesting stays in
 * the Kotlin, where it is a real relation between types; it stops being a
 * second value on the wire, which it never was.
 */
private fun nestedTypeInfo(branch: KClass<*>, root: String): String =
    "its branch `${branch.simpleName}` carries a `@JsonTypeInfo` of its own. Jackson reads one type id " +
        "per payload — the one the declared type asks for — so `${branch.simpleName}`'s is ignored when a " +
        "`$root` is read and preferred when one is written, and a payload written here does not come back. " +
        "Remove it and leave `@JsonSubTypes` alone: the branches below it are already selected by `$root`'s " +
        "property, under their own names, and that is what gets published."

private fun ownTypeInfoUnder(root: String): String =
    "it carries a `@JsonTypeInfo` and so does `$root` above it. Jackson reads one type id per payload, so " +
        "the two disagree about which property carries it. Keep the one on `$root` and remove this one; the " +
        "`@JsonSubTypes` here still names the branches, and they are published under `$root`'s property."

private fun concreteLevel(kClass: KClass<*>): String =
    "its branch `${kClass.simpleName}` is a payload in its own right and also a level of the hierarchy, " +
        "with `@JsonSubTypes` under it. It would have to be published as a branch that is itself a choice " +
        "of branches, and a payload whose type is spread over two values is one nothing reads back. Make " +
        "it abstract, so it is a level and not a payload, or move its subtypes up beside it."

private fun abstractBranch(kClass: KClass<*>): String =
    "its branch `${kClass.simpleName}` is abstract and lists no `@JsonSubTypes` of its own, so no payload " +
        "can be one. Name the concrete branches under it, or leave it out of the hierarchy."

private fun circular(kClass: KClass<*>): String =
    "`${kClass.simpleName}` appears twice on the way down its own `@JsonSubTypes`. A hierarchy that " +
        "contains itself has no set of branches to publish."
