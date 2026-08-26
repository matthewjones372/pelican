package io.github.matthewjones372.pelican.jsoniter

import com.jsoniter.JsonIterator
import com.jsoniter.ValueType
import com.jsoniter.output.EncodingMode
import com.jsoniter.output.JsonStream
import com.jsoniter.spi.Config
import com.jsoniter.spi.Decoder
import com.jsoniter.spi.DecodingMode
import com.jsoniter.spi.Encoder
import com.jsoniter.spi.JsonException
import com.jsoniter.spi.JsoniterSpi
import com.jsoniter.spi.OmitValue
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaGetter

/**
 * jsoniter's `Config`, taught how to read and write Kotlin.
 *
 * jsoniter was finished in 2018 and binds a JSON object to a Java bean: a
 * no-argument constructor, then a field or setter per property. A data class
 * has no no-argument constructor, so jsoniter refuses one outright — `no
 * constructor for: class Line`. Its own answer to that is `@JsonCreator` on the
 * constructor, which builds the object and loses every Kotlin default: an
 * absent `quantity: Int = 1` arrives as a null that the constructor call throws
 * on, because the defaults live in a synthetic constructor nothing here calls.
 *
 * So the binding is done here instead, through [Extension][com.jsoniter.spi.Extension]'s
 * two hooks, which jsoniter consults before it reaches for a bean binder. Every
 * decoder below ends at `callBy`, which is the one call that applies Kotlin's
 * defaults; everything else — the parser, the printer, collections, maps,
 * numbers, strings — is jsoniter's.
 *
 * A `Config` rather than a globally registered extension because
 * `JsoniterSpi.registerExtension` mutates a process-wide list: a library that
 * registered one would change how a program's *other* jsoniter calls behave.
 * A config is passed per call and applies to that call only, which is what
 * `GsonCompatibilityMode`, jsoniter's own compatibility layer, does too.
 */
internal class JsoniterConfig private constructor(name: String, builder: Builder) : Config(name, builder) {

    init {
        // The codegen modes compile decoders with javassist, which is optional
        // in jsoniter's own pom and not a dependency here. Refused at assembly
        // rather than as a NoClassDefFoundError on the first request. The mode
        // also arrives from JSONITER_DECODING_MODE, so the check is on the
        // config rather than on the builder call.
        require(decodingMode() == DecodingMode.REFLECTION_MODE) {
            "pelican-jsoniter needs jsoniter's REFLECTION_MODE: ${decodingMode()} generates decoders with javassist"
        }
        require(encodingMode() == EncodingMode.REFLECTION_MODE) {
            "pelican-jsoniter needs jsoniter's REFLECTION_MODE: ${encodingMode()} generates encoders with javassist"
        }
    }

    /** jsoniter's own rule for what `omitDefaultValue` leaves out, or null when it is off. */
    fun omitted(type: Type): OmitValue? = if (omitDefaultValue()) createOmitValue(type) else null

    class Builder : Config.Builder() {
        override fun doBuild(configName: String): Config = JsoniterConfig(configName, this)

        // jsoniter names a config after this string and caches every decoder it
        // builds under that name, so two configs that print the same are one
        // config. The marker is what keeps a Kotlin-aware config from being
        // handed the decoders of a plain one with the same settings.
        override fun toString(): String = "pelican-jsoniter{${super.toString()}}"
    }

    override fun createDecoder(cacheKey: String, type: Type): Decoder? = decoderFor(type, this)

    override fun createEncoder(cacheKey: String, type: Type): Encoder? = encoderFor(type, this)
}

/**
 * The config a [JsoniterCodecs] reads and writes through.
 *
 * The settings are jsoniter's own — `escapeUnicode`, `indentionStep` and the
 * rest are on the builder this hands you:
 *
 * Decoding and encoding stay in jsoniter's reflection mode, the one that needs
 * no bytecode generation: the codegen modes want javassist, which this module
 * does not depend on, and they are refused rather than accepted and then failed
 * on.
 */
fun jsoniterConfig(configure: Config.Builder.() -> Unit = {}): Config =
    JsoniterConfig.Builder().apply(configure).build()

// ------------------------------------------------------------------ decoding

/**
 * The decoder for [type], or null to let jsoniter decide.
 *
 * Null is the common answer and the important one: a `String`, an `Int`, a
 * `List` or a `Map` is jsoniter's to read, and taking those over would be
 * reimplementing a JSON library rather than binding one. Only the shapes Kotlin
 * spells differently from Java are answered here.
 */
private fun decoderFor(type: Type, config: JsoniterConfig): Decoder? {
    generic(type)?.let { refusal -> return Decoder { throw JsonException(refusal) } }
    val java = type as? Class<*> ?: return null
    val scalar = scalars[java]
    val kclass = java.kotlin
    return when {
        scalar != null -> Decoder { iter -> iter.readString()?.let(scalar.read) }
        !java.isKotlin -> null
        kclass.java.isEnum -> enumDecoder(kclass)
        kclass.objectInstance != null -> Decoder { iter -> iter.readAny(); kclass.objectInstance }
        kclass.isValue -> valueDecoder(kclass)
        kclass.isSealed -> unionDecoder(kclass, config)
        kclass.primaryConstructor != null -> ObjectDecoder(kclass)
        else -> null
    }
}

/**
 * A Kotlin class, read through its primary constructor.
 *
 * The properties present in the payload are collected as they arrive and the
 * constructor is called once, at the end, with `callBy` — which is what makes a
 * missing property with a default *take* that default rather than a zero. A
 * missing property that is merely nullable becomes null, and a missing property
 * that is neither is the error, named, rather than an exception from inside a
 * constructor call that mentions no field at all.
 */
private class ObjectDecoder(private val kclass: KClass<*>) : Decoder {

    private val ctor = requireNotNull(kclass.primaryConstructor).also { it.isAccessible = true }
    private val byName = ctor.parameters.associateBy { it.name }
    private val types = ctor.parameters.associateWith { it.type.toJsoniterType() }
    private val wrappers = ctor.parameters.associateWith { it.wrapper() }

    override fun decode(iter: JsonIterator): Any? {
        if (iter.readNull()) return null

        val arguments = HashMap<KParameter, Any?>()
        var field = iter.readObject()
        while (field != null) {
            val parameter = byName[field]
            if (parameter == null) {
                iter.skip()
            } else {
                val read = iter.read(types.getValue(parameter))
                // `callBy` wants the wrapper where a parameter is a value
                // class, and the type jsoniter was given is the one the JVM
                // signature carries — which, for most of them, is what is
                // inside the wrapper rather than the wrapper itself.
                arguments[parameter] = wrappers.getValue(parameter)?.box(read) ?: read
            }
            field = iter.readObject()
        }

        for (parameter in ctor.parameters) {
            if (parameter in arguments || parameter.isOptional) continue
            if (!parameter.type.isMarkedNullable) {
                throw JsonException("${kclass.simpleName} needs a value for '${parameter.name}'")
            }
            arguments[parameter] = null
        }

        return ctor.callBy(arguments)
    }
}

/**
 * A value class, read as the value inside it and wrapped.
 *
 * The null is checked here rather than left to what is inside: a nullable
 * value class over a primitive keeps its wrapper in the JVM signature, so this
 * is the decoder a null arrives at, and `readInt` on one reports a parse error.
 */
private fun valueDecoder(kclass: KClass<*>): Decoder {
    val underlying = kclass.underlying()
    return Decoder { iter -> if (iter.readNull()) null else kclass.box(iter.read(underlying)) }
}

/** An enum, read by constant name, and told what the names were when it is not one. */
private fun enumDecoder(kclass: KClass<*>): Decoder {
    val byName = kclass.java.enumConstants.filterIsInstance<Enum<*>>().associateBy { it.name }
    return Decoder { iter ->
        val name = iter.readString()
        when {
            name == null -> null
            byName.containsKey(name) -> byName[name]
            else -> throw JsonException("'$name' is not one of ${byName.keys.joinToString()}")
        }
    }
}

/**
 * A sealed hierarchy, read by looking at its discriminator first.
 *
 * Which branch to build is a property of the payload, and JSON does not promise
 * that property arrives before the ones it decides how to read — so the object
 * is read into jsoniter's own tree, the discriminator is taken from there, and
 * the branch is decoded from the same text. That is a second parse of one
 * value, paid only by payloads that are actually unions, and the alternative is
 * to require callers to put a field first.
 */
private fun unionDecoder(kclass: KClass<*>, config: JsoniterConfig): Decoder {
    val branches = kclass.leaves().associateBy { it.discriminatorValue() }
    return Decoder { iter ->
        if (iter.readNull()) {
            null
        } else {
            val payload = iter.readAny()
            // Taken before the discriminator is read out: an `Any` hands back
            // the text it was parsed from until a key is looked up, and prints
            // the parsed values back into JSON from then on.
            val text = payload.toString()
            val name = payload.get(UNION_DISCRIMINATOR).takeIf { it.valueType() == ValueType.STRING }
                ?.toString()
                ?: throw JsonException("A ${kclass.simpleName} needs a '$UNION_DISCRIMINATOR' saying which one it is")
            val branch = branches[name]
                ?: throw JsonException("'$name' is not one of ${branches.keys.joinToString()}")
            branchOf(text, branch, config)
        }
    }
}

/**
 * One branch of a union, read from the text the union was read from.
 *
 * The config is put back by hand because jsoniter does not: every
 * `deserialize(config, …)` ends at `clearCurrentConfig`, which restores the
 * *default* config rather than the one that was current — so a nested call
 * leaves the rest of the enclosing payload being read by a jsoniter that has
 * never heard of Kotlin.
 */
private fun branchOf(text: String, branch: KClass<*>, config: JsoniterConfig): Any? {
    val enclosing = JsoniterSpi.getCurrentConfig()
    try {
        return JsonIterator.deserialize(config, text.toByteArray(), branch.java)
    } finally {
        JsoniterSpi.setCurrentConfig(enclosing)
    }
}

// ------------------------------------------------------------------ encoding

/** The encoder for [type], or null where jsoniter's own is right. Mirrors [decoderFor]. */
private fun encoderFor(type: Type, config: JsoniterConfig): Encoder? {
    generic(type)?.let { refusal -> return Encoder { _, _ -> throw JsonException(refusal) } }
    val java = type as? Class<*> ?: return null
    val scalar = scalars[java]
    val kclass = java.kotlin
    return when {
        scalar != null -> Encoder { value, stream -> stream.writeVal(scalar.write(value)) }
        !java.isKotlin -> null
        kclass.java.isEnum -> Encoder { value, stream -> stream.writeVal((value as Enum<*>).name) }
        kclass.objectInstance != null -> Encoder { _, stream -> stream.writeEmptyObject() }
        kclass.isValue -> valueEncoder(kclass)
        kclass.isSealed -> unionEncoder(kclass, config)
        kclass.primaryConstructor != null -> ObjectEncoder(kclass, config)
        else -> null
    }
}

/**
 * A Kotlin class, written as its constructor declares it.
 *
 * The constructor rather than the class's fields, because the schema is read
 * off the constructor too: a `val` declared in the body has a backing field
 * jsoniter's own encoder would write and no property in the document to match.
 * Its order is the declared one, which `Class.getDeclaredFields` does not
 * promise.
 *
 * A property holding null is left out, which is the one spelling the three
 * codec modules write — see `defaultMapper()` and `defaultJson()`. The schema
 * marks a nullable property optional and all three read an absent one back as
 * null, so writing the word would be a second spelling of one fact. A null
 * *inside* a list or a map is a value there and is written, which is where
 * jsoniter's own encoders take over anyway.
 *
 * A property holding its default is still written unless the config asks for
 * `omitDefaultValue` — jsoniter's own setting, applied by jsoniter's own rule.
 */
private class ObjectEncoder(
    kclass: KClass<*>,
    config: JsoniterConfig,
    private val discriminator: String? = null,
) : Encoder {

    private class Property(val name: String, val type: Type, val omit: OmitValue?, val read: (Any) -> Any?)

    private val properties = requireNotNull(kclass.primaryConstructor).parameters.mapNotNull { parameter ->
        val property = kclass.memberProperties.firstOrNull { it.name == parameter.name } ?: return@mapNotNull null
        val type = parameter.type.toJsoniterType()
        Property(requireNotNull(parameter.name), type, config.omitted(type), readerOf(property))
    }

    override fun encode(value: Any?, stream: JsonStream) {
        if (value == null) {
            stream.writeNull()
            return
        }
        stream.writeObjectStart()
        var first = true
        if (discriminator != null) {
            stream.writeIndention()
            stream.writeObjectField(UNION_DISCRIMINATOR)
            stream.writeVal(discriminator)
            first = false
        }
        for (property in properties) {
            val held = property.read(value)
            if (held == null || property.omit?.shouldOmit(held) == true) continue
            if (first) stream.writeIndention() else stream.writeMore()
            first = false
            stream.writeObjectField(property.name)
            stream.writeVal(property.type, held)
        }
        stream.writeObjectEnd()
    }
}

/**
 * How a property is read out of a value.
 *
 * The JVM getter where there is one, for two reasons. It is faster —
 * `KProperty.getter.call` goes through kotlin-reflect for every value encoded.
 * And it agrees with the type jsoniter was handed: both come from the JVM
 * signature, so a value-class property yields what is inside the wrapper,
 * which is what `KParameter.type.javaType` said the property was.
 */
private fun readerOf(property: KProperty1<out Any, *>): (Any) -> Any? {
    val getter = property.javaGetter
    if (getter == null) {
        property.isAccessible = true
        return { value -> property.getter.call(value) }
    }
    getter.isAccessible = true
    return { value -> getter.invoke(value) }
}

/**
 * A value class, written as the value inside it.
 *
 * Reached only where the wrapper survives to the JVM — a nullable one over a
 * primitive, or a payload that is a value class in its own right. Everywhere
 * else the signature carries the value itself and jsoniter never asks about
 * the wrapper at all.
 */
private fun valueEncoder(kclass: KClass<*>): Encoder {
    val property = kclass.valueProperty()
    val type = property.returnType.toJsoniterType()
    val read = readerOf(property)
    return Encoder { value, stream ->
        if (value == null) stream.writeNull() else stream.writeVal(type, read(value))
    }
}

/**
 * A sealed hierarchy, written as its branch plus the marker saying which branch
 * it was. The branches are resolved once, here, rather than per value.
 */
private fun unionEncoder(kclass: KClass<*>, config: JsoniterConfig): Encoder {
    val branches = kclass.leaves().associateWith { ObjectEncoder(it, config, it.discriminatorValue()) }
    return Encoder { value, stream ->
        if (value == null) {
            stream.writeNull()
        } else {
            val encoder = branches[value::class]
                ?: throw JsonException("${value::class.simpleName} is not a branch of ${kclass.simpleName}")
            encoder.encode(value, stream)
        }
    }
}

// ------------------------------------------------------------------- shared

/**
 * What a union's branch marker is called, and it is not configurable on
 * purpose: `pelican-kotlinx` publishes `type` too, and a document that
 * describes one wire format is worth more than two modules each with their own.
 */
internal const val UNION_DISCRIMINATOR = "type"

/**
 * Why a payload type cannot be bound, when it is a Kotlin class with type
 * parameters — or null, which is every other type.
 *
 * `Page<Order>` is the shape, and the problem is that nothing carries the
 * argument to where the binding happens: a constructor parameter typed `T`
 * reflects as `T`, and jsoniter would read it as whatever the JSON happened to
 * look like — a `LinkedHashMap` where an `Order` was declared. Refused rather
 * than bound, because a payload silently read as a map is worse than one that
 * is not read at all, and the other two codec modules bind this shape properly.
 */
private fun generic(type: Type): String? {
    val raw = (type as? ParameterizedType)?.rawType as? Class<*> ?: return null
    if (!raw.isKotlin || raw.kotlin.primaryConstructor == null) return null
    return "pelican-jsoniter cannot read ${raw.simpleName}<>: jsoniter has no view of a type argument. " +
        "Use a payload type without type parameters, or JacksonCodecs or KotlinxCodecs, which bind one."
}

/**
 * Whether a class was written in Kotlin, which is the line between what this
 * module binds and what it leaves to jsoniter.
 */
private val Class<*>.isKotlin: Boolean get() = isAnnotationPresent(Metadata::class.java)

/** The single property a value class wraps. */
internal fun KClass<*>.valueProperty(): KProperty1<out Any, *> {
    val name = requireNotNull(primaryConstructor).parameters.single().name
    return memberProperties.first { it.name == name }
}

/** What is inside a value class, as jsoniter's type. */
private fun KClass<*>.underlying(): Type = requireNotNull(primaryConstructor).parameters.single().type.toJsoniterType()

/**
 * [value] in this value class's wrapper.
 *
 * Already-wrapped is left alone: a nullable value class over a primitive keeps
 * its wrapper in the JVM signature, so that one arrives wrapped already.
 */
private fun KClass<*>.box(value: Any?): Any? = when {
    value == null || isInstance(value) -> value
    else -> requireNotNull(primaryConstructor).also { it.isAccessible = true }.call(value)
}

/** The value class this parameter is declared as, or null where it is not one. */
private fun KParameter.wrapper(): KClass<*>? = (type.classifier as? KClass<*>)?.takeIf { it.isValue }

/** The branches of a sealed hierarchy, flattened — a sealed branch is not a branch. */
internal fun KClass<*>.leaves(): List<KClass<*>> =
    sealedSubclasses.flatMap { if (it.isSealed) it.leaves() else listOf(it) }

/** What a branch is called on the wire. Its own name, which is what the document says too. */
internal fun KClass<*>.discriminatorValue(): String = requireNotNull(simpleName)

/**
 * The types that travel as a string and that jsoniter has no reading of: it
 * was written before `java.time` was common and it treats a `UUID` as an object
 * with fields. Kept to the ones a payload actually carries, and written the way
 * the Jackson module's `JavaTimeModule` writes them, so a service that swaps
 * codecs keeps the same wire format.
 */
private class Scalar(val read: (String) -> Any, val write: (Any?) -> String)

private val scalars: Map<Class<*>, Scalar> = mapOf(
    UUID::class.java to Scalar({ UUID.fromString(it) }, { it.toString() }),
    Instant::class.java to Scalar({ Instant.parse(it) }, { it.toString() }),
    LocalDate::class.java to Scalar({ LocalDate.parse(it) }, { it.toString() }),
    LocalTime::class.java to Scalar({ LocalTime.parse(it) }, { it.toString() }),
    LocalDateTime::class.java to Scalar({ LocalDateTime.parse(it) }, { it.toString() }),
    OffsetDateTime::class.java to Scalar({ OffsetDateTime.parse(it) }, { it.toString() }),
    ZonedDateTime::class.java to Scalar({ ZonedDateTime.parse(it) }, { it.toString() }),
    Duration::class.java to Scalar({ Duration.parse(it) }, { it.toString() }),
    // Keyed by Java class, so both spellings of a `Char` — the primitive a
    // non-null property compiles to and the boxed one a nullable property does
    // — find the same reading.
    Char::class.javaObjectType to Scalar({ it.single() }, { it.toString() }),
    requireNotNull(Char::class.javaPrimitiveType) to Scalar({ it.single() }, { it.toString() }),
)

/** Whether [kclass] is one of the string-shaped scalars above. Read by the schema walker. */
internal fun isScalar(kclass: KClass<*>): Boolean = kclass.javaObjectType in scalars

/** Whether [kclass] is a collection jsoniter writes as an array. Read by the schema walker. */
internal fun isCollection(kclass: KClass<*>): Boolean = kclass.isSubclassOf(Collection::class)
