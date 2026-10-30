package example.backends

import io.github.matthewjones372.pelican.AdmissionDecision
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.NoCodecs
import io.github.matthewjones372.pelican.OperationAdmission
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jetty.mcp.handlerWithMcp
import io.github.matthewjones372.pelican.jetty.mcp.mcpHandler
import io.github.matthewjones372.pelican.mcp.mcpDispatch
import io.github.matthewjones372.pelican.mcp.mcpOptions
import io.github.matthewjones372.pelican.mcp.server.mcpServer
import io.github.matthewjones372.pelican.pekko.mcp.mcpRoutes
import io.github.matthewjones372.pelican.pekko.mcp.routeWithMcp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.reflect.KType
import io.github.matthewjones372.pelican.gannet.start as startOnGannet
import io.github.matthewjones372.pelican.gannet.toHandler as toGannet
import io.github.matthewjones372.pelican.jetty.start as startOnJetty
import io.github.matthewjones372.pelican.jetty.toHandler as toJetty
import io.github.matthewjones372.pelican.pekko.start as startOnPekko
import io.github.matthewjones372.pelican.pekko.toRoute as toPekko
import io.github.matthewjones372.pelican.test.inMemory as inCore
import io.github.matthewjones372.pelican.test.jetty.inMemory as inJetty
import io.github.matthewjones372.pelican.test.pekko.InMemoryTransport as PekkoTransport
import io.github.matthewjones372.pelican.test.pekko.inMemory as inPekko

class AdmissionConfigurationTest {
    private val configured = unsupportedApi()
    private val options = mcpOptions { include = { error("tool filtering started") } }
    private val unusedSystem = ClassicActorSystemProvider { error("the actor system was consulted") }

    @TestFactory
    fun `unsupported construction refuses admission before codecs callbacks or owned resources`(): List<DynamicTest> =
        listOf<Pair<String, () -> Any?>>(
            "Jetty handler" to { configured.toJetty() },
            "Jetty native custom handler" to { configured.startOnJetty(handler = { error("handler called") }) },
            "Jetty owned custom handler" to { configured.inJetty(handler = { error("handler called") }) },
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
