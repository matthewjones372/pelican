package dev.pelican.http4k

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Two claims about this module's classpath, stated as tests.
 *
 * A second backend is only evidence that the abstractions in `pelican-core`
 * work if it is genuinely a second one — so this module must not be able to see
 * the first. And serving documentation is opt-in here for the same reason it is
 * in `pelican-pekko`: a service that only serves endpoints should not compile
 * the document generator in.
 */
class DecouplingTest {

    @Test
    fun `pekko is genuinely absent from this module's classpath`() {
        val loaded = runCatching { Class.forName("org.apache.pekko.http.javadsl.server.Directives") }
        assertTrue(
            loaded.isFailure,
            "pelican-http4k must not see Pekko: the point of a second backend is that it shares no server library with the first",
        )
    }

    @Test
    fun `the openapi module is genuinely absent from this module's classpath`() {
        val loaded = runCatching { Class.forName("dev.pelican.openapi.OpenApiKt") }
        assertTrue(
            loaded.isFailure,
            "pelican-http4k must not see pelican-openapi; docs live in pelican-http4k-docs",
        )
    }
}
