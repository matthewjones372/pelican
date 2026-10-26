package io.github.matthewjones372.pelican

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class IdempotencyStoreTest {

    /** A clock the test moves, so an expiry is a step rather than a sleep. */
    private class SteppedClock(var now: Instant = Instant.parse("2026-10-26T12:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        fun advance(by: Duration) { now = now.plus(by) }
    }

    private val clock = SteppedClock()
    private val store = InMemoryIdempotencyStore(clock)

    private val scope = IdempotencyScope(operation = "placeOrder", subject = "ada", key = "k-1")
    private val lease = Duration.ofSeconds(30)
    private val ttl = Duration.ofHours(24)
    private val created = StoredResponse(201, listOf("Location" to "/orders/1"), "application/json", "{}".toByteArray())

    @Test
    fun `the first request with a key reserves it`() {
        store.reserve(scope, "fp-a", lease) shouldBe Reservation.Reserved
    }

    @Test
    fun `a repeat while the first is running is told it is in flight`() {
        store.reserve(scope, "fp-a", lease)

        store.reserve(scope, "fp-a", lease) shouldBe Reservation.InFlight
    }

    @Test
    fun `a repeat after the first completed gets the stored response`() {
        store.reserve(scope, "fp-a", lease)
        store.complete(scope, created, ttl)

        val done = store.reserve(scope, "fp-a", lease).shouldBeInstanceOf<Reservation.Done>()
        done.response.status shouldBe 201
        done.response.headers shouldBe listOf("Location" to "/orders/1")
        done.response.mediaType shouldBe "application/json"
        String(done.response.body) shouldBe "{}"
    }

    @Test
    fun `the same key for a different request is a mismatch whether or not the first has finished`() {
        store.reserve(scope, "fp-a", lease)
        store.reserve(scope, "fp-b", lease) shouldBe Reservation.Mismatch

        store.complete(scope, created, ttl)
        store.reserve(scope, "fp-b", lease) shouldBe Reservation.Mismatch
    }

    @Test
    fun `a key is scoped to its operation and its caller`() {
        store.reserve(scope, "fp-a", lease)

        store.reserve(scope.copy(subject = "grace"), "fp-a", lease) shouldBe Reservation.Reserved
        store.reserve(scope.copy(operation = "cancelOrder"), "fp-a", lease) shouldBe Reservation.Reserved
    }

    @Test
    fun `a released key can be reserved again`() {
        store.reserve(scope, "fp-a", lease)
        store.release(scope)

        store.reserve(scope, "fp-b", lease) shouldBe Reservation.Reserved
    }

    @Test
    fun `release leaves a completed response alone`() {
        store.reserve(scope, "fp-a", lease)
        store.complete(scope, created, ttl)
        store.release(scope)

        store.reserve(scope, "fp-a", lease).shouldBeInstanceOf<Reservation.Done>()
    }

    @Test
    fun `a lease that ran out without completing is taken over by the next request`() {
        store.reserve(scope, "fp-a", lease)
        clock.advance(lease.plusSeconds(1))

        store.reserve(scope, "fp-a", lease) shouldBe Reservation.Reserved
    }

    @Test
    fun `a stored response is forgotten once its ttl has passed`() {
        store.reserve(scope, "fp-a", lease)
        store.complete(scope, created, ttl)

        clock.advance(ttl)
        store.reserve(scope, "fp-a", lease).shouldBeInstanceOf<Reservation.Done>()

        clock.advance(Duration.ofSeconds(1))
        store.lookup(scope).shouldBeNull()
        store.reserve(scope, "fp-b", lease) shouldBe Reservation.Reserved
    }

    @Test
    fun `lookup reports what is held without taking it`() {
        store.lookup(scope).shouldBeNull()

        store.reserve(scope, "fp-a", lease)
        store.lookup(scope) shouldBe Reservation.InFlight
        store.reserve(scope, "fp-a", lease) shouldBe Reservation.InFlight

        store.complete(scope, created, ttl)
        store.lookup(scope).shouldBeInstanceOf<Reservation.Done>().response.status shouldBe 201
    }

    @Test
    fun `of many concurrent requests with one key exactly one reserves it`() {
        val answers = (1..64).toList().parallelStream().map { store.reserve(scope, "fp-a", lease) }.toList()

        answers.count { it == Reservation.Reserved } shouldBe 1
        answers.count { it == Reservation.InFlight } shouldBe 63
    }
}
