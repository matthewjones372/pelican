package io.github.matthewjones372.pelican

/**
 * The typed input list for an endpoint, in both directions.
 */
class Inputs<I>(
    val keys: List<ParamKey<*>>,
    val extract: (Params) -> I,
    val inject: (I) -> Map<ParamKey<*>, Any?>,
)

/**
 * Lens style: the handler gets the whole bag and reads it by key.
 *
 * The way past the sixth arity, at the cost of the compile-time guarantee — an
 * undeclared key throws at request time. Asked for by name, because
 * `endpoint { }` says no inputs and that is what it means.
 */
val lensInputs: Inputs<Params> = Inputs(emptyList(), { it }, { it.asMap() })

data class In2<A, B>(val a: A, val b: B)
data class In3<A, B, C>(val a: A, val b: B, val c: C)
data class In4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
data class In5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
data class In6<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)
