package example.backends

import dev.surefold.pelican.AdmissionDecision
import dev.surefold.pelican.Api
import dev.surefold.pelican.BodyCodec
import dev.surefold.pelican.Codecs
import dev.surefold.pelican.NoCodecs
import dev.surefold.pelican.OperationAdmission
import dev.surefold.pelican.ServerEndpoint
import dev.surefold.pelican.api
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.jetty.Handlers
import dev.surefold.pelican.jetty.docs.handlerWithDocs
import dev.surefold.pelican.jetty.docs.startWithDocs
import dev.surefold.pelican.jetty.mcp.handlerWithMcp
import dev.surefold.pelican.jetty.mcp.mcpHandler
import dev.surefold.pelican.mcp.mcpDispatch
import dev.surefold.pelican.mcp.mcpOptions
import dev.surefold.pelican.mcp.server.mcpServer
import dev.surefold.pelican.pekko.mcp.mcpRoutes
import dev.surefold.pelican.pekko.mcp.routeWithMcp
import dev.surefold.pelican.test.jetty.client
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KType
import dev.surefold.pelican.gannet.start as startOnGannet
import dev.surefold.pelican.gannet.toHandler as toGannet
import dev.surefold.pelican.jetty.start as startOnJetty
import dev.surefold.pelican.pekko.start as startOnPekko
import dev.surefold.pelican.pekko.toRoute as toPekko
import dev.surefold.pelican.test.inMemory as inCore
import dev.surefold.pelican.test.jetty.inMemory as inJetty
import dev.surefold.pelican.test.pekko.InMemoryTransport as PekkoTransport
import dev.surefold.pelican.test.pekko.inMemory as inPekko

class AdmissionConfigurationTest {
    private val configured = unsupportedApi()
    private val options = mcpOptions { include = { error("tool filtering started") } }
    private val unusedSystem = ClassicActorSystemProvider { error("the actor system was consulted") }

    @TestFactory
    fun `unsupported construction refuses admission before codecs callbacks or owned resources`(): List<DynamicTest> =
        listOf<Pair<String, () -> Any?>>(
            "Jetty native custom handler" to { configured.startOnJetty(handler = { error("handler called") }) },
            "Jetty owned custom handler" to { configured.inJetty(handler = { error("handler called") }) },
            "Jetty HTTP and docs handler" to { configured.handlerWithDocs() },
            "Jetty native docs handler" to { configured.startWithDocs(port = 0) },
            "Pekko route" to { configured.toPekko(unusedSystem) },
            "Pekko owned native system" to { configured.startOnPekko(systemName = "invalid/name") },
            "Pekko owned test system" to { configured.inPekko("invalid/name") },
            "Gannet handler" to { configured.toGannet() },
            "Gannet native custom handler" to { configured.startOnGannet(handler = { error("handler called") }) },
            "MCP dispatch" to { configured.mcpDispatch(options) },
            "MCP server" to { configured.mcpServer(options) },
            "Jetty MCP handler" to { configured.mcpHandler(options) },
            "Pekko MCP routes" to { configured.mcpRoutes(options) },
            "Jetty HTTP and MCP handler" to { configured.handlerWithMcp(options = options) },
            "Pekko HTTP and MCP route" to { configured.routeWithMcp(unusedSystem, options) },
        ).map { (name, construct) -> DynamicTest.dynamicTest(name) { refuses(construct) } }

    @Test
    fun `the core test client applies supported admission before dispatch`() {
        val endpoint = endpoint { get("core"); empty() }
        val api = api(listOf(ServerEndpoint(endpoint) { error("handler called") })) {
            admission(OperationAdmission { AdmissionDecision.Refuse(403) })
        }
        api.inCore().use { it.response(endpoint, Unit).status shouldBe 403 }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Jetty default owned paths apply admission and preserve the supplied handler policy`(native: Boolean) {
        val checked = AtomicInteger()
        val virtual = AtomicReference<Boolean>()
        val endpoint = endpoint { get("jetty"); empty() }
        val api = api(listOf(ServerEndpoint(endpoint) { error("handler called") })) {
            admission(
                OperationAdmission {
                    checked.incrementAndGet()
                    virtual.set(Thread.currentThread().isVirtual)
                    AdmissionDecision.Refuse(403)
                },
            )
        }
        if (native) {
            api.startOnJetty(port = 0, handlers = Handlers.onDispatcher).use { server ->
                server.client().use { it.response(endpoint, Unit).status shouldBe 403 }
            }
        } else {
            api.inJetty(handlers = Handlers.onDispatcher).use { it.response(endpoint, Unit).status shouldBe 403 }
        }
        checked.get() shouldBe 1
        virtual.get() shouldBe false
    }

    @Test
    fun `borrowed systems remain alive when construction is refused`() {
        val ping = endpoint { get("owner"); text() }
        val routes = listOf(ServerEndpoint(ping) { error("owner handler called") })
        api(routes).inPekko("admission-borrowed").use { owner ->
            val system = (owner.transport as PekkoTransport).system
            refuses { configured.startOnPekko(system, route = { error("route called") }) }
            refuses { configured.inPekko(system) }
            refuses { PekkoTransport(configured, system, ownsSystem = false) }
            system.getWhenTerminated().toCompletableFuture().isDone shouldBe false
        }
    }
}

private fun refuses(construct: () -> Any?) {
    shouldThrow<IllegalArgumentException> { construct() }.message shouldContain "operation admission"
}

private fun unsupportedApi(): Api {
    val operation = endpoint { get("admission"); json<String>() }
    val codecs = object : Codecs by NoCodecs {
        override fun <T> codec(type: KType): BodyCodec<T> = error("codec construction started")
    }
    return api(listOf(ServerEndpoint(operation) { error("endpoint invoked") }), codecs) {
        admission(OperationAdmission { error("admission invoked at construction") })
    }
}
