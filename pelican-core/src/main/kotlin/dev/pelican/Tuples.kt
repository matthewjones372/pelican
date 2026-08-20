package dev.pelican

/**
 * The typed input list for an endpoint, in both directions.
 *
 * [keys] is what the endpoint declares — the interpreter decodes exactly these
 * and no more. [extract] projects the decoded bag into the shape the handler
 * receives. Because the endpoint carries `Inputs<I>` and the binder demands
 * `(I) -> R`, a handler cannot read an input the endpoint never declared: the
 * value simply isn't in scope.
 *
 * [inject] is the inverse: given what a handler would have received, put the
 * values back under the keys they came from. A server only ever needs
 * [extract]; a *client* needs [inject], because building the request means
 * turning typed values back into a path, a query string and a body. Supplying
 * both is what lets a client be type-checked against the same description the
 * server is interpreted from.
 *
 * You rarely name this type. `endpoint(userId, limit) { }` builds it from the
 * keys it is handed; the two constants below cover the cases that have no keys
 * to build it from.
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
