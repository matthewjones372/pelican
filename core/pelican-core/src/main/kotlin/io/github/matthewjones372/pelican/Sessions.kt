package io.github.matthewjones372.pelican

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * A session as a [SessionStore] keeps it: who it is, when it began, when it ends unless used again, and the tenant
 * it was started in, or null for one started in none.
 */
class StoredSession(val identity: Identity, val created: Instant, val expires: Instant, val tenant: String? = null)

/**
 * Where sessions live. Keyed by the SHA-256 of a session's id, never the id itself, so a store that leaks is not a
 * set of live sessions. The in-memory one is [InMemorySessionStore]; a persistent one is the service's.
 */
interface SessionStore {
    fun create(key: String, identity: Identity, expires: Instant, tenant: String? = null)

    /** The session under [key], or null when there is none or it has expired. */
    fun lookup(key: String): StoredSession?

    /** Moves an existing session's expiry; does nothing for one that is gone. */
    fun touch(key: String, expires: Instant)

    fun revoke(key: String)

    /** Ends every session of [subject]: sign out everywhere. */
    fun revokeAll(subject: String)
}

/** A [SessionStore] in this process's memory, for tests and a single instance. */
class InMemorySessionStore(private val clock: Clock = Clock.systemUTC()) : SessionStore {

    private val sessions = ConcurrentHashMap<String, StoredSession>()

    override fun create(key: String, identity: Identity, expires: Instant, tenant: String?) {
        sessions[key] = StoredSession(identity, clock.instant(), expires, tenant)
    }

    override fun lookup(key: String): StoredSession? {
        val held = sessions[key] ?: return null
        if (clock.instant().isBefore(held.expires)) return held
        sessions.remove(key, held)
        return null
    }

    override fun touch(key: String, expires: Instant) {
        sessions.computeIfPresent(key) { _, held -> StoredSession(held.identity, held.created, expires, held.tenant) }
    }

    override fun revoke(key: String) {
        sessions.remove(key)
    }

    override fun revokeAll(subject: String) {
        sessions.values.removeIf { it.identity.subject == subject }
    }
}

/**
 * A session held in a cookie and kept in a [SessionStore]: [start] it once the service knows who someone is, and it
 * is an [Authenticator] for every request after, so `authenticatedBy` and `requires` work on it unchanged.
 *
 * A session ends after [idle] without use or [absolute] after it began, whichever is first. Use slides the idle
 * expiry, at most once a minute so a busy page is not a store write per request. A cookie naming a session that has
 * ended reads as nobody rather than a refusal, since a browser keeps sending it, and so does one started in another
 * tenant than the request is for.
 */
class SessionCookie internal constructor(
    val name: String,
    private val store: SessionStore,
    val idle: Duration,
    val absolute: Duration,
    private val clock: Clock,
) : Authenticator {

    /** What the document names: an API key carried in the cookie. */
    val scheme: ApiKeyScheme = apiKeyCookie(name, name = "session", description = "A session cookie")

    private val random = SecureRandom()

    /** Starts a session for [identity] in [tenant], or in none, and the cookie that carries it. */
    fun start(identity: Identity, tenant: Tenant? = null): ResponseCookie {
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(ID_BYTES).also(random::nextBytes))
        val now = clock.instant()
        store.create(keyOf(id), identity, expiryFrom(now, created = now), tenant?.id)
        return ResponseCookie(name, id, maxAge = absolute)
    }

    /** Ends the session [credentials] carry, if any, and the cookie that clears it from the browser. */
    fun end(credentials: Credentials): ResponseCookie {
        credentials.cookie(name)?.let { store.revoke(keyOf(it)) }
        return ResponseCookie.expired(name)
    }

    /** Ends every session of [subject]: a stolen laptop, a changed password. */
    fun endAll(subject: String) = store.revokeAll(subject)

    override fun authenticate(credentials: Credentials): Identity? {
        val key = keyOf(credentials.cookie(name) ?: return null)
        val held = store.lookup(key)?.takeIf { it.servesTenantOf(credentials) } ?: return null
        val now = clock.instant()
        val slid = expiryFrom(now, held.created)
        if (!slid.isBefore(held.expires.plus(SLIDE_EVERY.toJavaDuration()))) store.touch(key, slid)
        return held.identity
    }

    private fun expiryFrom(now: Instant, created: Instant): Instant =
        minOf(now.plus(idle.toJavaDuration()), created.plus(absolute.toJavaDuration()))

    override fun toString() = "SessionCookie($name)"
}

/** A [SessionCookie] named [name], kept in [store]. */
fun sessionCookie(
    name: String = "sid",
    store: SessionStore = InMemorySessionStore(),
    idle: Duration = 30.minutes,
    absolute: Duration = 12.hours,
    clock: Clock = Clock.systemUTC(),
): SessionCookie {
    require(idle.isPositive() && absolute.isPositive()) { "A session's idle and absolute lifetimes are both positive" }
    ResponseCookie(name, "") // the name is checked once, here, rather than on the first sign-in
    return SessionCookie(name, store, idle, absolute, clock)
}

/** Whether a session may be read for this request: one started in a tenant is that tenant's alone. */
private fun StoredSession.servesTenantOf(credentials: Credentials): Boolean {
    val requested = (credentials as? TenantNamed)?.tenant
    return tenant == null || requested == null || tenant == requested
}

/** The hex SHA-256 of a session id: what a store is keyed by. */
private fun keyOf(id: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)))

private const val ID_BYTES = 32

private val SLIDE_EVERY = 1.minutes
