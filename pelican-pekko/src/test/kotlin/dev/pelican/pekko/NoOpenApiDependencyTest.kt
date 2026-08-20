package dev.pelican.pekko

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Serving documentation is opt-in, and this is that claim stated as a test: a
 * service that depends on the server interpreter alone does not get the OpenAPI
 * generator with it. Adding `pelican-openapi` back as a dependency of this
 * module — for convenience, to serve one page — fails here.
 */
class NoOpenApiDependencyTest {

    @Test
    fun `the openapi module is genuinely absent from this module's classpath`() {
        val loaded = runCatching { Class.forName("dev.pelican.openapi.OpenApiKt") }
        assertTrue(
            loaded.isFailure,
            "pelican-pekko must not see pelican-openapi; docs live in pelican-pekko-docs",
        )
    }
}
