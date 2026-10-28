package io.github.matthewjones372.pelican

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** The in-memory store: what it keeps, until when, and how a session is ended early. */
class SessionStoreTest {

    private class SteppedClock(var now: Instant = Instant.parse("2026-10-27T12:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        fun advance(by: Duration) { now = now.plus(by.toJavaDuration()) }
    }

    private val clock = SteppedClock()
    private val store = InMemorySessionStore(clock)

    private fun inMinutes(n: Int): Instant = clock.now.plus(n.minutes.toJavaDuration())

    @Test
    fun `a session is kept under its key until it expires`() {
        store.create("k1", Identity("ada"), expires = inMinutes(30))

        val held = store.lookup("k1").shouldNotBeNull()
        held.identity.subject shouldBe "ada"
        held.created shouldBe clock.now
        held.expires shouldBe inMinutes(30)
        held.tenant shouldBe null

        clock.advance(30.minutes)
        store.lookup("k1").shouldBeNull()
    }

    @Test
    fun `a session keeps the tenant it was started in, through a touch`() {
        store.create("k1", Identity("ada"), expires = inMinutes(30), tenant = "acme")
        store.touch("k1", expires = inMinutes(45))

        store.lookup("k1").shouldNotBeNull().tenant shouldBe "acme"
    }

    @Test
    fun `a touch moves the expiry`() {
        store.create("k1", Identity("ada"), expires = inMinutes(30))
        store.touch("k1", expires = inMinutes(45))

        clock.advance(40.minutes)
        store.lookup("k1").shouldNotBeNull()
    }

    @Test
    fun `a touch on a session that is gone brings nothing back`() {
        store.touch("never", expires = inMinutes(45))

        store.lookup("never").shouldBeNull()
    }

    @Test
    fun `a revoked session is gone at once`() {
        store.create("k1", Identity("ada"), expires = inMinutes(30))
        store.revoke("k1")

        store.lookup("k1").shouldBeNull()
    }

    @Test
    fun `revoking a subject ends every session of theirs and nobody else's`() {
        store.create("laptop", Identity("ada"), expires = inMinutes(30))
        store.create("phone", Identity("ada"), expires = inMinutes(30))
        store.create("grace", Identity("grace"), expires = inMinutes(30))

        store.revokeAll("ada")

        store.lookup("laptop").shouldBeNull()
        store.lookup("phone").shouldBeNull()
        store.lookup("grace").shouldNotBeNull()
    }
}
