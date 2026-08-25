package io.github.matthewjones372.pelican

/**
 * The typed input list for an endpoint, in both directions.
 */
class Inputs<I>(
    val keys: List<ParamKey<*>>,
    val extract: (Params) -> I,
    val inject: (I) -> Map<ParamKey<*>, Any?>,
)

/** Lens style: the handler gets the whole bag and reads it by key. */
val lensInputs: Inputs<Params> = Inputs(emptyList(), { it }, { it.asMap() })

/** No inputs at all. */
val noInputs: Inputs<Unit> = Inputs(emptyList(), { }, { emptyMap() })

data class In2<A, B>(val a: A, val b: B)
data class In3<A, B, C>(val a: A, val b: B, val c: C)
data class In4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
data class In5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
data class In6<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)
