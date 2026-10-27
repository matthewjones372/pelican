package io.github.matthewjones372.pelican

/**
 * Something a caller must have to be let in, matched against their [Identity]. Built with [role],
 * [permission] and [anyOf], and declared on an endpoint with `requires(...)`.
 */
sealed interface Requirement {
    /** Whether [identity] has it. */
    fun metBy(identity: Identity): Boolean
}

/** A group the caller is in: one of [Identity.groups]. */
class Role internal constructor(val name: String) : Requirement {
    override fun metBy(identity: Identity) = name in identity.groups

    override fun toString() = "role `$name`"
}

/** A permission the caller's credential grants: one of its `scope` claim's words, or of its `scp` list. */
class Permission internal constructor(val name: String) : Requirement {
    override fun metBy(identity: Identity) = name in permissionsOf(identity)

    override fun toString() = "permission `$name`"
}

/** Any one of [options] is enough. */
class AnyOf internal constructor(val options: List<Requirement>) : Requirement {
    override fun metBy(identity: Identity) = options.any { it.metBy(identity) }

    override fun toString() = options.joinToString(" or ", prefix = "(", postfix = ")")
}

fun role(name: String): Requirement = Role(name.also { require(it.isNotBlank()) { "A role needs a name" } })

fun permission(name: String): Requirement =
    Permission(name.also { require(it.isNotBlank() && ' ' !in it) { "A permission is one word, not '$it'" } })

fun anyOf(first: Requirement, second: Requirement, vararg more: Requirement): Requirement =
    AnyOf(listOf(first, second) + more)

/** RFC 9068 and most providers: a space-separated `scope`, else an `scp` list or string. */
private fun permissionsOf(identity: Identity): Set<String> {
    fun words(claim: Any?): Set<String> = when (claim) {
        is String -> claim.split(' ').filter { it.isNotEmpty() }.toSet()
        is Collection<*> -> claim.filterIsInstance<String>().toSet()
        else -> emptySet()
    }
    return words(identity.claims["scope"]).ifEmpty { words(identity.claims["scp"]) }
}

/**
 * Everything an endpoint `requires(...)`, every one of which the caller must meet, and the 403 a
 * caller who misses one is refused with.
 */
class Requires internal constructor(val all: List<Requirement>) {
    /** The 403 Pelican answers with, declared on the endpoint unless it declares a 403 itself. */
    val refused: ErrorSpec = ErrorSpec(403, "The caller does not have $this.", type = null)

    fun metBy(identity: Identity): Boolean = all.all { it.metBy(identity) }

    /** The permissions every caller needs, which a scheme declaring them as scopes can publish as such. */
    val permissions: List<String> get() = all.filterIsInstance<Permission>().map { it.name }

    override fun toString() = all.joinToString(" and ")
}
