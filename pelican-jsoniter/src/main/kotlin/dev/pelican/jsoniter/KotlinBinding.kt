package dev.pelican.jsoniter

import com.jsoniter.JsonIterator
import com.jsoniter.ValueType
import com.jsoniter.output.JsonStream
import com.jsoniter.spi.Config
import com.jsoniter.spi.Decoder
import com.jsoniter.spi.Encoder
import com.jsoniter.spi.JsonException
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
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * jsoniter's `Config`, taught how to read and write Kotlin.
 *
 * jsoniter was finished in 2018 and binds a JSON object to a Java bean: a
 * no-argument constructor, then a field or setter per property. A Kotlin data
 * class has neither. Its constructor takes every property at once, and that
 * constructor is also the only thing that knows which properties have defaults
 * — so a binder that sets fields one at a time does not merely need help, it
 * produces the wrong object silently: an absent `quantity: Int = 1` arrives as
 * `0`, because that is what an unset field holds.
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

    class Builder : Config.Builder() {
        override fun doBuild(configName: String): Config = JsoniterConfig(configName, this)

        // jsoniter names a config after this string and caches every decoder it
        // builds under that name, so two configs that print the same are one
        // config. The marker is what keeps a Kotlin-aware config from being
        // handed the decoders of a plain one with the same settings.
        override fun toString(): String = "pelican-jsoniter{${super.toString()}}"
    }

    override fun createDecoder(cacheKey: String, type: Type): Decoder? = decoderFor(type)

    override fun createEncoder(cacheKey: String, type: Type): Encoder? = encoderFor(type)
}

/**
 * The config a [JsoniterCodecs] reads and writes through.
 *
 * The settings are jsoniter's own — `escapeUnicode`, `indentionStep` and the
 * rest are on the builder this hands you:
 *
 * ```
 * JsoniterCodecs(jsoniterConfig { escapeUnicode(false) })
 * ```
 *
 * Decoding and encoding stay in jsoniter's reflection mode, which is its
 * default here and the mode that needs no bytecode generation — the
 * alternatives generate classes with javassist at startup, which buys a payload
 * type nothing once the binding above is doing the object work anyway.
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
private fun decoderFor(type: Type): Decoder? {
    generic(type)?.let { refusal -> return Decoder { throw JsonException(refusal) } }
    val java = type as? Class<*> ?: return null
    val scalar = scalars[java]
    val kclass = java.kotlin
    return when {
        scalar != null -> Decoder { iter -> iter.readString()?.let(scalar.read) }
        !java.isKotlin -> null
        kclass.java.isEnum -> enumDecoder(kclass)
        kclass.objectInstance != null -> Decoder { iter -> iter.readAny(); kclass.objectInstance }
        kclass.isSealed -> unionDecoder(kclass)
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
 *
 * A property the class does not declare is skipped rather than refused, which
 * is the same lenience `defaultMapper()` and `defaultJson()` are configured
 * for: an unknown field is a caller running ahead of this server, not a bad
 * request.
 */
private class ObjectDecoder(private val kclass: KClass<*>) : Decoder {

    private val ctor = requireNotNull(kclass.primaryConstructor).also { it.isAccessible = true }
    private val byName = ctor.parameters.associateBy { it.name }

    override fun decode(iter: JsonIterator): Any? {
        if (iter.readNull()) return null

        val arguments = HashMap<KParameter, Any?>()
        var field = iter.readObject()
        while (field != null) {
            val parameter = byName[field]
            if (parameter == null) iter.skip() else arguments[parameter] = iter.read(parameter.type.toJsoniterType())
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
private fun unionDecoder(kclass: KClass<*>): Decoder {
    val branches = kclass.leaves().associateBy { it.discriminatorValue() }
    return Decoder { iter ->
        if (iter.readNull()) {
            null
        } else {
            val payload = iter.readAny()
            val name = payload.get(UNION_DISCRIMINATOR).takeIf { it.valueType() == ValueType.STRING }
                ?.toString()
                ?: throw JsonException("A ${kclass.simpleName} needs a '$UNION_DISCRIMINATOR' saying which one it is")
            val branch = branches[name]
                ?: throw JsonException("'$name' is not one of ${branches.keys.joinToString()}")
            JsonIterator.deserialize(payload.toString().toByteArray(), branch.java)
        }
    }
}

// ------------------------------------------------------------------ encoding

/** The encoder for [type], or null where jsoniter's own is right. Mirrors [decoderFor]. */
private fun encoderFor(type: Type): Encoder? {
    generic(type)?.let { refusal -> return Encoder { _, _ -> throw JsonException(refusal) } }
    val java = type as? Class<*> ?: return null
    val scalar = scalars[java]
    val kclass = java.kotlin
    return when {
        scalar != null -> Encoder { value, stream -> stream.writeVal(scalar.write(value)) }
        !java.isKotlin -> null
        kclass.java.isEnum -> Encoder { value, stream -> stream.writeVal((value as Enum<*>).name) }
        kclass.objectInstance != null -> Encoder { _, stream -> stream.writeEmptyObject() }
        kclass.isSealed -> unionEncoder(kclass)
        kclass.primaryConstructor != null -> ObjectEncoder(kclass)
        else -> null
    }
}

/**
 * A Kotlin class, written as its constructor declares it.
 *
 * Constructor order rather than reflection order, because the constructor is
 * the declaration a reader of the class sees, and `Class.getDeclaredFields`
 * makes no promise about order at all.
 *
 * Every property is written, including one that holds null or its default. An
 * absent field and a null one mean different things to a document that says the
 * field is nullable, and this is the same choice `defaultMapper()` and
 * `defaultJson()` make — a payload should not change shape because a value
 * happened to equal a default.
 *
 * [discriminator], when there is one, is the branch marker a union writes ahead
 * of the branch's own properties.
 */
private class ObjectEncoder(kclass: KClass<*>, private val discriminator: String? = null) : Encoder {

    private class Property(val name: String, val type: Type, val read: (Any) -> Any?)

    private val properties = requireNotNull(kclass.primaryConstructor).parameters.mapNotNull { parameter ->
        val property = kclass.memberProperties.firstOrNull { it.name == parameter.name } ?: return@mapNotNull null
        property.isAccessible = true
        val read = { value: Any -> property.getter.call(value) }
        Property(requireNotNull(parameter.name), parameter.type.toJsoniterType(), read)
    }

    override fun encode(value: Any?, stream: JsonStream) {
        if (value == null) {
            stream.writeNull()
            return
        }
        stream.writeObjectStart()
        var first = true
        if (discriminator != null) {
            stream.writeObjectField(UNION_DISCRIMINATOR)
            stream.writeVal(discriminator)
            first = false
        }
        for (property in properties) {
            if (!first) stream.writeMore()
            first = false
            stream.writeObjectField(property.name)
            stream.writeVal(property.type, property.read(value))
        }
        stream.writeObjectEnd()
    }
}

/**
 * A sealed hierarchy, written as its branch plus the marker saying which branch
 * it was. The branches are resolved once, here, rather than per value.
 */
private fun unionEncoder(kclass: KClass<*>): Encoder {
    val branches = kclass.leaves().associateWith { ObjectEncoder(it, it.discriminatorValue()) }
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
 *
 * It has to be asked of the Java class rather than of the Kotlin one, because
 * `KClass` answers for types that have no Kotlin behind them at all: a
 * `java.lang.String` reflects as `kotlin.String`, primary constructor and all,
 * and a binder that trusted that would read every string as an object.
 */
private val Class<*>.isKotlin: Boolean get() = isAnnotationPresent(Metadata::class.java)

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
