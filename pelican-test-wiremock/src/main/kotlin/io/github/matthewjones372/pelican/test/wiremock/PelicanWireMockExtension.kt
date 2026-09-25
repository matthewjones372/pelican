package io.github.matthewjones372.pelican.test.wiremock

import io.github.matthewjones372.pelican.Codecs
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * [PelicanWireMock] under JUnit 5: stubs and requests are forgotten after each test, and the
 * server stops when the test class finishes, on a static field or an instance one.
 */
class PelicanWireMockExtension(codecs: Codecs) :
    PelicanWireMock(codecs),
    BeforeAllCallback,
    BeforeEachCallback,
    AfterEachCallback {

    // Closed by JUnit with the class's store. An instance field has no beforeAll, so beforeEach
    // hands it to the same store, once.
    override fun beforeAll(context: ExtensionContext) = closeWith(context)

    override fun beforeEach(context: ExtensionContext) = closeWith(context.parent.orElse(context))

    override fun afterEach(context: ExtensionContext) = reset()

    private fun closeWith(context: ExtensionContext) {
        context.getStore(ExtensionContext.Namespace.create(PelicanWireMockExtension::class.java))
            .computeIfAbsent(this) { this }
    }
}
