package dev.pelican.ktor

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Three claims about this module's classpath, stated as tests.
 *
 * A third backend is only evidence that the abstractions in `pelican-core` work
 * if it is genuinely a third one — so this module must not be able to see
 * either of the others. And serving documentation is opt-in here for the same
 * reason it is everywhere else: a service that only serves endpoints should not
 * compile the document generator in.
 */
class DecouplingTest {

    @Test
    fun `pekko is genuinely absent from this module's classpath`() {
        val loaded = runCatching { Class.forName("org.apache.pekko.http.javadsl.server.Directives") }
        assertTrue(loaded.isFailure, "pelican-ktor must not see Pekko")
    }

    @Test
    fun `http4k is genuinely absent from this module's classpath`() {
        val loaded = runCatching { Class.forName("org.http4k.core.Request") }
        assertTrue(
            loaded.isFailure,
            "pelican-ktor must not see http4k: the point of another backend is that it shares no server library with the others",
        )
    }

    @Test
    fun `the openapi module is genuinely absent from this module's classpath`() {
        val loaded = runCatching { Class.forName("dev.pelican.openapi.OpenApiKt") }
        assertTrue(
            loaded.isFailure,
            "pelican-ktor must not see pelican-openapi; docs live in pelican-ktor-docs",
        )
    }
}
