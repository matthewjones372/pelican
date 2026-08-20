package dev.pelican.arrow

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * This module says its binders work on any backend. That is only worth saying
 * if it cannot see one — a binder that quietly reached for Pekko would still
 * pass every test in here, right up until someone put it in front of Ktor.
 */
class DecouplingTest {

    private fun absent(className: String, why: String) {
        val loaded = runCatching { Class.forName(className) }
        assertTrue(loaded.isFailure, why)
    }

    @Test
    fun `no server library is on this module's classpath`() {
        absent("org.apache.pekko.http.javadsl.server.Directives", "pelican-arrow must not see Pekko")
        absent("org.http4k.core.Request", "pelican-arrow must not see http4k")
        absent("io.ktor.server.application.Application", "pelican-arrow must not see Ktor")
    }

    /** Bodies go through whatever `Codecs` the Api was built with, here as everywhere. */
    @Test
    fun `no JSON library is on this module's classpath`() {
        absent("com.fasterxml.jackson.databind.ObjectMapper", "pelican-arrow must not see Jackson")
        absent("kotlinx.serialization.json.Json", "pelican-arrow must not see kotlinx.serialization")
    }
}
