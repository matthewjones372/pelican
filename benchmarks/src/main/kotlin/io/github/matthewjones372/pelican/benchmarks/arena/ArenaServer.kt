package io.github.matthewjones372.pelican.benchmarks.arena

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.queryParam
import java.io.File
import io.github.matthewjones372.pelican.jetty.Handlers as JettyHandlers
import io.github.matthewjones372.pelican.jetty.handledNow as onJetty
import io.github.matthewjones372.pelican.jetty.start as startJetty
import io.github.matthewjones372.pelican.pekko.Handlers as PekkoHandlers
import io.github.matthewjones372.pelican.pekko.handledNow as onPekko
import io.github.matthewjones372.pelican.pekko.start as startPekko
import io.github.matthewjones372.pelican.gannet.Handlers as UringHandlers
import io.github.matthewjones372.pelican.gannet.handledNow as onUring
import io.github.matthewjones372.pelican.gannet.start as startUring

data class Rating(val score: Int, val count: Int)

data class DatasetItem(
    val id: Long,
    val name: String,
    val category: String,
    val price: Long,
    val quantity: Long,
    val active: Boolean,
    val tags: List<String>,
    val rating: Rating,
)

data class PricedItem(
    val id: Long,
    val name: String,
    val category: String,
    val price: Long,
    val quantity: Long,
    val active: Boolean,
    val tags: List<String>,
    val rating: Rating,
    val total: Long,
)

data class JsonPage(val items: List<PricedItem>, val count: Int)

private val a = queryParam<Long>("a")
private val b = queryParam<Long>("b")
private val count = pathParam<Int>("count")
private val m = queryParam<Long>("m").default(1L)

private val getSum = endpoint(a, b) {
    get("baseline11")
    text()
}

private val postSum = endpoint(a, b, jsonBody<Long>()) {
    post("baseline11")
    text()
}

private val pipeline = endpoint {
    get("pipeline")
    text()
}

private val jsonItems = endpoint(count, m) {
    get("json" / count)
    json<JsonPage>()
}

private fun page(items: List<DatasetItem>, n: Int, mult: Long): JsonPage {
    val first = items.take(n.coerceIn(0, items.size)).map {
        PricedItem(
            it.id, it.name, it.category, it.price, it.quantity, it.active, it.tags, it.rating,
            it.price * it.quantity * mult,
        )
    }
    return JsonPage(first, first.size)
}

private fun arenaApi(bind: List<ServerEndpoint>): Api = api(endpoints = bind, codecs = JacksonCodecs)

/**
 * HttpArena's baseline, pipeline and JSON endpoints as a Pelican API, for the closed-model gcannon runs:
 * `ArenaServerKt <backend> <port> <dataset.json>`. Each backend starts through its own `start()`.
 */
fun main(args: Array<String>) {
    val backend = args[0]
    val port = args[1].toInt()
    val items: List<DatasetItem> = jacksonObjectMapper().readValue(File(args[2]))
    val server: AutoCloseable
    val bound: Int
    when (backend) {
        "pekko", "pekko-dispatcher" -> {
            val handlers = if (backend == "pekko") PekkoHandlers.default else PekkoHandlers.onDispatcher
            val s = arenaApi(
                listOf(
                    getSum onPekko { (x, y) -> (x + y).toString() },
                    postSum onPekko { (x, y, body) -> (x + y + body).toString() },
                    pipeline onPekko { "ok" },
                    jsonItems onPekko { (n, mult) -> page(items, n, mult) },
                ),
            ).startPekko(port = port, systemName = "pelican-arena", handlers = handlers)
            server = AutoCloseable { s.stop() }
            bound = s.port
        }

        "jetty", "jetty-dispatcher" -> {
            val handlers = if (backend == "jetty") JettyHandlers.default else JettyHandlers.onDispatcher
            val s = arenaApi(
                listOf(
                    getSum onJetty { (x, y) -> (x + y).toString() },
                    postSum onJetty { (x, y, body) -> (x + y + body).toString() },
                    pipeline onJetty { "ok" },
                    jsonItems onJetty { (n, mult) -> page(items, n, mult) },
                ),
            ).startJetty(port = port, handlers = handlers)
            server = AutoCloseable { s.stop() }
            bound = s.port
        }

        "uring", "uring-inline" -> {
            val handlers = if (backend == "uring") UringHandlers.default else UringHandlers.onDispatcher
            val s = arenaApi(
                listOf(
                    getSum onUring { (x, y) -> (x + y).toString() },
                    postSum onUring { (x, y, body) -> (x + y + body).toString() },
                    pipeline onUring { "ok" },
                    jsonItems onUring { (n, mult) -> page(items, n, mult) },
                ),
            ).startUring(port = port, handlers = handlers)
            server = AutoCloseable { s.stop() }
            bound = s.port
        }

        else -> error("Expected pekko, pekko-dispatcher, jetty, jetty-dispatcher, uring or uring-inline, got $backend")
    }
    println("ARENA $backend ready port=$bound items=${items.size}")
    System.out.flush()
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    Thread.currentThread().join()
}
