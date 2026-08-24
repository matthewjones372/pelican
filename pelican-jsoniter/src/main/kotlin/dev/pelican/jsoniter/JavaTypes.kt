package dev.pelican.jsoniter

import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType

/**
 * The Java type jsoniter should be given for a Kotlin type.
 *
 * Not simply [javaType], because Kotlin's collections are covariant and Java's
 * are not: `List<Payment>` reflects as `List<? extends Payment>`, and jsoniter
 * reads a wildcard as `Object` — at which point it encodes each element by
 * whatever class the element turned out to be. For most payloads that lands on
 * the same bytes and nobody notices. For a sealed hierarchy it does not: the
 * branch gets written without the discriminator that says which branch it is,
 * and the document promises something the wire does not carry.
 *
 * So the bounds are taken off before jsoniter sees them. `? extends Payment`
 * is `Payment`, which is what the Kotlin type said in the first place.
 */
internal fun KType.toJsoniterType(): Type = javaType.withoutWildcards()

private fun Type.withoutWildcards(): Type = when (this) {
    // `?` on its own has `Object` as its bound, which is also the right answer.
    is WildcardType -> upperBounds.firstOrNull()?.withoutWildcards() ?: Any::class.java

    is ParameterizedType -> Parameterized(
        rawType,
        ownerType,
        actualTypeArguments.map { it.withoutWildcards() }.toTypedArray(),
    )

    is GenericArrayType -> this

    else -> this
}

/**
 * A [ParameterizedType] with the arguments this module chose.
 *
 * jsoniter caches decoders and encoders per type, so equality matters as much
 * as the arguments do: two of these describing one type have to be one key.
 * The contract is the one `sun.reflect.generics` implements — equal raw type,
 * owner and arguments — because that is what a JDK-made instance will be
 * compared against.
 */
private class Parameterized(
    private val raw: Type,
    private val owner: Type?,
    private val arguments: Array<Type>,
) : ParameterizedType {

    override fun getRawType(): Type = raw
    override fun getOwnerType(): Type? = owner
    override fun getActualTypeArguments(): Array<Type> = arguments.copyOf()

    override fun equals(other: Any?): Boolean = other is ParameterizedType &&
        raw == other.rawType &&
        owner == other.ownerType &&
        arguments.contentEquals(other.actualTypeArguments)

    override fun hashCode(): Int =
        arguments.contentHashCode() xor raw.hashCode() xor (owner?.hashCode() ?: 0)

    override fun toString(): String =
        "${(raw as? Class<*>)?.name ?: raw}<${arguments.joinToString { (it as? Class<*>)?.name ?: it.toString() }}>"
}
