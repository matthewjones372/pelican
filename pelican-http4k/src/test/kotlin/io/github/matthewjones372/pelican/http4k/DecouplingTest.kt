package io.github.matthewjones372.pelican.http4k

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
        withClue(
            "pelican-http4k must not see Pekko: the point of a second backend is that it " +
                "shares no server library with the first",
        ) {
            shouldThrow<ClassNotFoundException> { Class.forName("org.apache.pekko.http.javadsl.server.Directives") }
        }
    }

    @Test
    fun `the openapi module is genuinely absent from this module's classpath`() {
        withClue("pelican-http4k must not see pelican-openapi; docs live in pelican-http4k-docs") {
            shouldThrow<ClassNotFoundException> { Class.forName("io.github.matthewjones372.pelican.openapi.OpenApiKt") }
        }
    }
}
