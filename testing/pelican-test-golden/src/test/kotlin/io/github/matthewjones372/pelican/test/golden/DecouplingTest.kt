package io.github.matthewjones372.pelican.test.golden

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import org.junit.jupiter.api.Test

/**
 * The claim this module makes about its own classpath, stated as a test — the
 * same guard `pelican-test` and each backend carry.
 *
 * Recording a document and recording a request are both things a description
 * can do on its own, so this module takes `pelican-openapi` and `pelican-test`
 * and no server. A suite of goldens should cost a JVM and nothing else.
 */
class DecouplingTest {

    @Test
    fun `no server library is reachable from the golden helpers`() {
        listOf(
            "org.apache.pekko.http.javadsl.server.Directives",
            "org.http4k.core.Request",
            "io.ktor.server.application.Application",
        ).forEach { name ->
            withClue("$name is on pelican-test-golden's classpath; a backend crept in") {
                shouldThrow<ClassNotFoundException> { Class.forName(name) }
            }
        }
    }
}
