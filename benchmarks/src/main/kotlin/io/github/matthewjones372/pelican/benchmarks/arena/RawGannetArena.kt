package io.github.matthewjones372.pelican.benchmarks.arena

import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.gannet.GannetServer
import io.github.matthewjones372.gannet.HttpHandler

private val text = listOf("Content-Type" to "text/plain")
private val ok = "ok".toByteArray()

/** The value of [name] in a raw query, or 0; the arena only ever sends digits. */
private fun param(query: String?, name: String): Long {
    var at = 0
    val q = query ?: return 0
    while (at < q.length) {
        val amp = q.indexOf('&', at).let { if (it < 0) q.length else it }
        val eq = at + name.length
        if (eq < amp && q[eq] == '=' && q.regionMatches(at, name, 0, name.length)) {
            return q.substring(eq + 1, amp).toLong()
        }
        at = amp + 1
    }
    return 0
}

/**
 * HttpArena's baseline and pipeline endpoints written straight on Gannet's API, the ceiling Pelican on Gannet is
 * measured against in the same run (spec 0089): `RawGannetArenaKt <port>`. The body is read with `readAll`, which
 * reads exactly `Content-Length` bytes into one array.
 */
fun main(args: Array<String>) {
    val handler = HttpHandler { ex ->
        val r = ex.request
        when (r.path) {
            "/baseline11" -> {
                var sum = param(r.query, "a") + param(r.query, "b")
                if (r.method == "POST") sum += String(r.body.readAll()).trim().toLong()
                ex.respond(200, text, sum.toString().toByteArray())
            }

            "/pipeline" -> ex.respond(200, text, ok)

            else -> ex.respond(404)
        }
    }
    val server = GannetServer.start(GannetConfig(host = "0.0.0.0", port = args[0].toInt()), handler)
    println("ARENA raw-gannet ready port=${server.port}")
    System.out.flush()
    server.awaitStopped()
}
