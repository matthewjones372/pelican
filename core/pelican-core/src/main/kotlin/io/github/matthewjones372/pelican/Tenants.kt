package io.github.matthewjones372.pelican

/** The tenant a request is for, as the [TenantDirectory] knows it. */
data class Tenant(val id: String)

/** As much of a request as a [TenantResolver] may read: its headers, and who is calling once that is known. */
class TenantRequest internal constructor(private val credentials: Credentials, val caller: Identity?) {
    /** The first line of [name], matched without regard to case. */
    fun header(name: String): String? = credentials.header(name)
}

/** Finds the tenant a request names, or null when it names none. One is bound per `Api` with `tenants(...)`. */
fun interface TenantResolver {
    fun resolve(request: TenantRequest): String?

    companion object {
        /** The tenant in header [name]. */
        fun header(name: String): TenantResolver = HeaderTenant(name)

        /** The tenant as the one label in front of [of]: `acme.example.com` is `acme`, `example.com` is none. */
        fun subdomain(of: String): TenantResolver = SubdomainTenant(of.lowercase().trimStart('.'))

        /** The tenant in the caller's claim [name]; the endpoint declares a caller for it to read. */
        fun claim(name: String): TenantResolver = ClaimTenant(name)
    }
}

/** Whether an id names a tenant at all. A persistent one is the service's; [InMemoryTenantDirectory] is for tests. */
fun interface TenantDirectory {
    fun exists(id: String): Boolean
}

/** A [TenantDirectory] of a fixed set of ids. */
class InMemoryTenantDirectory(private val ids: Set<String>) : TenantDirectory {
    override fun exists(id: String) = id in ids
}

/**
 * Which tenant a request is for, as [resolver] finds it. Declared on an endpoint with `tenanted(...)`, bound on the
 * `Api` with `tenants(...)`, and read in the handler as `this[tenant]`.
 */
class TenantInput internal constructor(val resolver: TenantResolver) : ParamKey<Tenant> {
    /** The 400 for a request that names no tenant. */
    val missing: ErrorSpec = ErrorSpec(BAD_REQUEST, "The request names no tenant.", type = null)

    /** The 404 for a tenant the directory does not know. */
    val unknown: ErrorSpec = ErrorSpec(NOT_FOUND, "No tenant by that name.", type = null)

    /** Whether [resolver] reads the caller, so the endpoint must declare one. */
    internal val readsCaller: Boolean get() = resolver is ClaimTenant

    internal fun decode(credentials: Credentials, caller: Identity?, directory: TenantDirectory): Tenant {
        val id = resolver.resolve(TenantRequest(credentials, caller))?.takeIf { it.isNotBlank() }
            ?: throw ApiException(BAD_REQUEST, "This endpoint is for one tenant, and the request names none")
        if (!directory.exists(id)) throw ApiException(NOT_FOUND, "No tenant by that name")
        return Tenant(id)
    }

    override fun toString() = "tenant"
}

/** A tenant found by [resolver]. */
fun tenantFrom(resolver: TenantResolver): TenantInput = TenantInput(resolver)

/** What an `Api` resolves tenants with. */
class Tenants internal constructor(val input: TenantInput, val directory: TenantDirectory)

private class HeaderTenant(val name: String) : TenantResolver {
    override fun resolve(request: TenantRequest) = request.header(name)?.trim()
}

private class SubdomainTenant(val parent: String) : TenantResolver {
    override fun resolve(request: TenantRequest): String? {
        val host = request.header("Host")?.substringBefore(':')?.lowercase() ?: return null
        val label = host.removeSuffix(".$parent").takeIf { it != host } ?: return null
        return label.takeIf { it.isNotEmpty() && '.' !in it }
    }
}

private class ClaimTenant(val name: String) : TenantResolver {
    override fun resolve(request: TenantRequest) = request.caller?.claims?.get(name) as? String
}

private const val BAD_REQUEST = 400
private const val NOT_FOUND = 404
