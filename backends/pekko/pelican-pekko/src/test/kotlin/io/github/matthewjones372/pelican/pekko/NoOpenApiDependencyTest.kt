package io.github.matthewjones372.pelican.pekko

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
        withClue("pelican-pekko must not see pelican-openapi; docs live in pelican-pekko-docs") {
            shouldThrow<ClassNotFoundException> { Class.forName("io.github.matthewjones372.pelican.openapi.OpenApiKt") }
        }
    }
}
