package io.github.matthewjones372.pelican.test.wiremock

import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/** Run through the engine, so the extension meets the callbacks JUnit actually makes. */
class ExtensionLifecycleTest {

    @Test
    fun `an instance field is started, cleared between tests, and stopped with its class`() {
        OnAnInstanceField.seen.clear()

        run(OnAnInstanceField::class.java)

        OnAnInstanceField.seen.size shouldBe 2
        OnAnInstanceField.seen.forEach { it.wireMock.isRunning shouldBe false }
    }

    @Test
    fun `a static field is shared across tests, cleared between them, and stopped with its class`() {
        run(OnAStaticField::class.java)

        OnAStaticField.registry.wireMock.isRunning shouldBe false
    }

    private fun run(testClass: Class<*>) {
        System.setProperty(FIXTURES, "true")
        try {
            EngineTestKit.engine("junit-jupiter").selectors(selectClass(testClass)).execute()
                .testEvents().assertStatistics { it.succeeded(2).failed(0) }
        } finally {
            System.clearProperty(FIXTURES)
        }
    }

    // Gradle finds these as test classes of their own; they run only when this test runs them.
    @EnabledIfSystemProperty(named = FIXTURES, matches = "true")
    class OnAnInstanceField {
        @JvmField
        @RegisterExtension
        val registry = PelicanWireMockExtension(JacksonCodecs)

        @Test
        fun first() = stubbedFresh(registry).also { seen += registry }

        @Test
        fun second() = stubbedFresh(registry).also { seen += registry }

        companion object {
            val seen = mutableListOf<PelicanWireMock>()
        }
    }

    @EnabledIfSystemProperty(named = FIXTURES, matches = "true")
    class OnAStaticField {
        @Test
        fun first() = stubbedFresh(registry)

        @Test
        fun second() = stubbedFresh(registry)

        companion object {
            @JvmField
            @RegisterExtension
            val registry = PelicanWireMockExtension(JacksonCodecs)
        }
    }
}

private const val FIXTURES = "pelican.wiremock.lifecycleFixtures"

/** Running, and holding no stub or request from a test before it. */
private fun stubbedFresh(registry: PelicanWireMock) {
    registry.wireMock.isRunning shouldBe true
    registry.calls(lookupChip) shouldBe 0
    registry.stub(lookupChip, In3(1L, "eu", 1)) answers ok(Chip("1", "Petshop"))
    registry.wireMock.stubMappings.size shouldBe 1
}
