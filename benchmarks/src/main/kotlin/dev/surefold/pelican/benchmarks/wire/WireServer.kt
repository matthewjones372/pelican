package dev.surefold.pelican.benchmarks.wire

import dev.surefold.pelican.Api
import dev.surefold.pelican.IntCodec
import dev.surefold.pelican.ServerEndpoint
import dev.surefold.pelican.api
import dev.surefold.pelican.default
import dev.surefold.pelican.div
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.jsonBody
import dev.surefold.pelican.pathParam
import dev.surefold.pelican.queryParam
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletableFuture
import dev.surefold.pelican.gannet.Handlers as GannetHandlers
import dev.surefold.pelican.gannet.handledBy as stagedOnGannet
import dev.surefold.pelican.gannet.handledNow as onGannet
import dev.surefold.pelican.gannet.start as startGannet
import dev.surefold.pelican.gannet.streamedNow as streamedOnGannet
import dev.surefold.pelican.jetty.handledBy as stagedOnJetty
import dev.surefold.pelican.jetty.handledNow as onJetty
import dev.surefold.pelican.jetty.start as startJetty
import dev.surefold.pelican.jetty.streamedNow as streamedOnJetty
import dev.surefold.pelican.pekko.handledBy as stagedOnPekko
import dev.surefold.pelican.pekko.handledNow as onPekko
import dev.surefold.pelican.pekko.start as startPekko
import dev.surefold.pelican.pekko.streamedNow as streamedOnPekko

data class Item(val id: Long, val name: String)

data class NewItem(val name: String)

/** About 40 bytes of JSON, so a streamed response is many small frames rather than a few large ones. */
data class Frame(val id: Int, val name: String)

/** Frames in each streamed response. */
internal const val FRAMES = 100
internal const val FRAME_NAME = "pelican-wire-frame"

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

/** A handler that returns a stage already complete: nothing in answering it blocks. */
private val staged = endpoint {
    get("staged")
    json<Item>()
}

private const val STAGED_ID = 3L

private fun stagedItem(): CompletableFuture<Item> = CompletableFuture.completedFuture(Item(STAGED_ID, "staged"))

private val ndjsonFrames = endpoint {
    get("ndjson")
    ndjson<Frame>()
}

private val sseFrames = endpoint {
    get("sse")
    sse<Frame>()
}

private fun frames(): Sequence<Frame> = (1..FRAMES).asSequence().map { Frame(it, FRAME_NAME) }

/** The JMH benchmarks' GET and POST, a staged GET and the two streamed responses, bound by [bind]'s backend. */
private fun wireApi(bind: List<ServerEndpoint>): Api = api(endpoints = bind, codecs = JacksonCodecs)

/**
 * One server for [WireDriver], in a JVM of its own so neither server shares a heap or a JIT with the driver.
 *
 * Each starts through its own `start()` with its defaults; `gannet-inline` is Gannet with
 * `Handlers.onDispatcher`, so the cost of the hop to a virtual thread is a number (spec 0088). Prints the port,
 * then serves until stdin closes, which is how the driver stops it and how it stops if the driver dies.
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
                    staged stagedOnPekko { stagedItem() },
                    ndjsonFrames streamedOnPekko { Source.range(1, FRAMES).map { n -> Frame(n, FRAME_NAME) } },
                    sseFrames streamedOnPekko { Source.range(1, FRAMES).map { n -> Frame(n, FRAME_NAME) } },
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
                    staged stagedOnJetty { stagedItem() },
                    ndjsonFrames streamedOnJetty { frames() },
                    sseFrames streamedOnJetty { frames() },
                ),
            ).startJetty(port = 0)
            port = server.port
            stop = server::stop
        }

        "gannet", "gannet-inline" -> {
            val handlers = if (args.single() == "gannet") GannetHandlers.default else GannetHandlers.onDispatcher
            val server = wireApi(
                listOf(
                    getItem onGannet { (id, lim) -> Item(id, "item-$lim") },
                    createItem onGannet { body -> Item(1, body.name) },
                    staged stagedOnGannet { stagedItem() },
                    ndjsonFrames streamedOnGannet { frames() },
                    sseFrames streamedOnGannet { frames() },
                ),
            ).startGannet(port = 0, handlers = handlers)
            port = server.port
            stop = server::stop
        }

        else -> error("Expected pekko, jetty, gannet or gannet-inline, got ${args.single()}")
    }
    println("PORT $port")
    System.out.flush()
    while (System.`in`.read() != -1) {
        // Nothing is sent on stdin; reading it is only how the end of the driver is noticed.
    }
    stop()
}
