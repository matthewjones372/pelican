package dev.pelican.pekko

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.javadsl.testkit.ActorSystemResource
import org.apache.pekko.http.javadsl.testkit.JUnitRouteTestBase
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Pekko's route testkit, started and stopped by JUnit 5.
 *
 * Two reasons this is a class rather than nothing at all. The testkit's own
 * `JUnitRouteTest` drives its `ActorSystemResource` from a JUnit 4 `@Rule`,
 * which Jupiter does not run — without something calling `before()`, the
 * actor system is never created. And `RouteTest` extends `AllDirectives`, so
 * inheriting it in a test class would pull `get`, `path`, `complete` and the
 * rest of the routing DSL into scope beside the test's own helpers. Held as a
 * field, only `testRoute` and `system` are in reach.
 */
class PekkoRouteTestKit(name: String) : JUnitRouteTestBase(), BeforeAllCallback, AfterAllCallback {

    private val resource = ActorSystemResource(name, ConfigFactory.empty())

    override fun systemResource(): ActorSystemResource = resource

    override fun beforeAll(context: ExtensionContext) = resource.before()

    override fun afterAll(context: ExtensionContext) = resource.after()
}
