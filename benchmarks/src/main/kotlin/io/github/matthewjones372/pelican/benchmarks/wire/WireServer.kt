package io.github.matthewjones372.pelican.benchmarks.wire

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.jetty.handledNow as onJetty
import io.github.matthewjones372.pelican.jetty.start as startJetty
import io.github.matthewjones372.pelican.pekko.handledNow as onPekko
import io.github.matthewjones372.pelican.pekko.start as startPekko

data class Item(val id: Long, val name: String)

data class NewItem(val name: String)

private val itemId = pathParam<Long>("itemId")
private val limit = queryParam("limit", IntCodec).default(10)

private val getItem = endpoint(itemId, limit) {
    get("items" / itemId)
    json<Item>()
}

private val createItem = endpoint(jsonBody<NewItem>()) {
    post("items")
    json<Item>(status = 201)
}

/** The JMH benchmarks' GET and POST, bound by whichever backend's `handledNow` [bind] is. */
private fun wireApi(bind: List<ServerEndpoint>): Api = api(endpoints = bind, codecs = JacksonCodecs)

/**
 * One server for [WireDriver], in a JVM of its own so neither server shares a heap or a JIT with the driver.
 *
 * Both start through their own `start()` with its defaults. Prints the port, then serves until stdin closes, which
 * is how the driver stops it and how it stops if the driver dies.
 */
fun main(args: Array<String>) {
    val stop: () -> Unit
    val port: Int
    when (args.single()) {
        "pekko" -> {
            val server = wireApi(
                listOf(
                    getItem onPekko { (id, lim) -> Item(id, "item-$lim") },
                    createItem onPekko { body -> Item(1, body.name) },
                ),
            ).startPekko(port = 0, systemName = "pelican-wire")
            port = server.port
            stop = server::stop
        }

        "jetty" -> {
            val server = wireApi(
                listOf(
                    getItem onJetty { (id, lim) -> Item(id, "item-$lim") },
                    createItem onJetty { body -> Item(1, body.name) },
                ),
            ).startJetty(port = 0)
            port = server.port
            stop = server::stop
        }

        else -> error("Expected pekko or jetty, got ${args.single()}")
    }
    println("PORT $port")
    System.out.flush()
    while (System.`in`.read() != -1) {
        // Nothing is sent on stdin; reading it is only how the end of the driver is noticed.
    }
    stop()
}
