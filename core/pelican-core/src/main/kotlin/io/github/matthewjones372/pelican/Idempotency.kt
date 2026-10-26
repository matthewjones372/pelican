package io.github.matthewjones372.pelican

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** What an idempotency key names: one key, on one operation, from one caller. */
data class IdempotencyScope(
    val operation: String,
    /** The caller's subject, or null on an endpoint anyone may call unnamed. */
    val subject: String?,
    val key: String,
)

/** A response as it was answered, kept to answer a repeat with. Plain fields, so a store can persist it as it likes. */
class StoredResponse(
    val status: Int,
    val headers: List<Pair<String, String>>,
    /** Null for a response with no body. */
    val mediaType: String?,
    val body: ByteArray,
) {
    override fun toString() = "StoredResponse($status, ${body.size} bytes)"
}

/** What a store holds under a key, as [IdempotencyStore.reserve] answers it. */
sealed interface Reservation {
    /** This request now holds the key, and runs the handler. */
    data object Reserved : Reservation

    /** Another request holds the key and has not finished. */
    data object InFlight : Reservation

    /** The key was first sent with a different request. */
    data object Mismatch : Reservation

    /** The first request finished, and this is what it answered. */
    class Done(val response: StoredResponse) : Reservation {
        override fun toString() = "Done($response)"
    }
}

/**
 * Where idempotency keys and the responses they answered are kept. Pelican holds none itself, so a service picks
 * where they live; [InMemoryIdempotencyStore] is the one for tests.
 *
 * Calls block, as an [Authenticator]'s do, and every one must be atomic across the nodes sharing the store: two
 * requests reserving one key at once must see one [Reservation.Reserved] between them.
 */
interface IdempotencyStore {
    /**
     * Takes [scope] for a request whose fingerprint is [fingerprint], or says why not. A reservation never completed
     * is given up after [lease], so a node that died mid-request does not hold its key until the ttl.
     */
    fun reserve(scope: IdempotencyScope, fingerprint: String, lease: Duration): Reservation

    /** Keeps [response] for [ttl], answered to every repeat of the request that reserved [scope]. */
    fun complete(scope: IdempotencyScope, response: StoredResponse, ttl: Duration)

    /** What is held under [scope] without taking it: [Reservation.InFlight], [Reservation.Done] or null. */
    fun lookup(scope: IdempotencyScope): Reservation?

    /** Gives up a reservation that was never completed, so a retry runs the handler again. A completed one stays. */
    fun release(scope: IdempotencyScope)
}

/**
 * An [IdempotencyStore] in this process's memory. For tests and a single node: nothing is shared with another
 * process and nothing survives a restart. Expired entries are dropped when their key is next touched.
 */
class InMemoryIdempotencyStore(private val clock: Clock = Clock.systemUTC()) : IdempotencyStore {

    private class Entry(val fingerprint: String, val until: Instant, val response: StoredResponse?)

    private val entries = ConcurrentHashMap<IdempotencyScope, Entry>()

    override fun reserve(scope: IdempotencyScope, fingerprint: String, lease: Duration): Reservation {
        val now = clock.instant()
        var answer: Reservation = Reservation.Reserved
        // compute holds the key's bin for the whole decision, which is the atomicity the interface promises.
        entries.compute(scope) { _, held ->
            val live = held?.takeUnless { now.isAfter(it.until) }
            answer = when {
                live == null -> Reservation.Reserved
                live.fingerprint != fingerprint -> Reservation.Mismatch
                live.response == null -> Reservation.InFlight
                else -> Reservation.Done(live.response)
            }
            if (answer == Reservation.Reserved) Entry(fingerprint, now.plus(lease), null) else live
        }
        return answer
    }

    override fun complete(scope: IdempotencyScope, response: StoredResponse, ttl: Duration) {
        val until = clock.instant().plus(ttl)
        entries.compute(scope) { _, held -> Entry(held?.fingerprint.orEmpty(), until, response) }
    }

    override fun lookup(scope: IdempotencyScope): Reservation? {
        val now = clock.instant()
        val live = entries.computeIfPresent(scope) { _, held -> held.takeUnless { now.isAfter(it.until) } }
        return when {
            live == null -> null
            live.response == null -> Reservation.InFlight
            else -> Reservation.Done(live.response)
        }
    }

    override fun release(scope: IdempotencyScope) {
        entries.computeIfPresent(scope) { _, held -> held.takeIf { it.response != null } }
    }
}
