package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.LongCodec
import io.github.matthewjones372.pelican.RefusalObserver
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.commaSeparated
import io.github.matthewjones372.pelican.cookieParam
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.positive
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.repeated
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.start as jettyStart

data class RoundTrip(val fromPath: String, val fromQuery: String?)

data class Filters(val tags: List<String>, val ids: List<Long>, val features: List<String>, val seen: List<String>)

data class Strictly(val term: String, val key: String, val jar: String)

/** Path, query, header and cookie inputs on both servers, asked the same questions over a socket. */
@RequiresGannet
class ParamsReadAsJettyReadsThemTest {

    /** What a caller can see of an answer, and so what the two servers are compared on. */
    data class Seen(val status: Int, val contentType: String?, val body: String)

    companion object {
        private val n = pathParam<Int>("n")
        private val segment = pathParam<String>("segment")
        private val q = queryParam<String>("q").optional()
        private val shout = queryParam<Boolean>("shout").default(false)
        private val tags = queryParam<String>("tag").repeated().optional()
        private val ids = queryParam("id", LongCodec.positive()).commaSeparated().optional()
        private val features = headerParam<String>("X-Feature").commaSeparated().optional()
        private val seen = cookieParam<String>("seen").repeated().optional()
        private val term = queryParam<String>("term")
        private val key = headerParam<String>("X-Key")
        private val jar = cookieParam<String>("jar")

        private val count = endpoint(n) {
            get("count" / n)
            text()
        }

        private val roundtrip = endpoint(segment, q) {
            get("items" / segment)
            json<RoundTrip>()
        }

        private val greet = endpoint(segment, shout) {
            get("hello" / segment)
            text()
        }

        private val filters = endpoint(tags, ids, features, seen) {
            get("filters")
            json<Filters>()
        }

        private val strict = endpoint(term, key, jar) {
            get("strict")
            json<Strictly>()
        }

        private val refused: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private fun served(endpoints: List<ServerEndpoint>, observer: RefusalObserver? = null) =
            api(endpoints, JacksonCodecs) { observer?.let { onRefusal(it) } }

        private val gannet = served(
            listOf(
                count handledNow { it.toString() },
                roundtrip handledNow { (p, q) -> RoundTrip(p, q) },
                greet handledNow { (who, loud) -> if (loud) who.uppercase() else who },
                filters handledNow { (t, i, f, s) -> Filters(t.orEmpty(), i.orEmpty(), f.orEmpty(), s.orEmpty()) },
                strict handledNow { (t, k, j) -> Strictly(t, k, j) },
            ),
            { reason, status, template -> refused += "${reason.label} $status $template" },
        ).start(port = 0)

        private val jetty = served(
            listOf(
                count jettyHandledNow { it.toString() },
                roundtrip jettyHandledNow { (p, q) -> RoundTrip(p, q) },
                greet jettyHandledNow { (who, loud) -> if (loud) who.uppercase() else who },
                filters jettyHandledNow { (t, i, f, s) ->
                    Filters(t.orEmpty(), i.orEmpty(), f.orEmpty(), s.orEmpty())
                },
                strict jettyHandledNow { (t, k, j) -> Strictly(t, k, j) },
            ),
        ).jettyStart(port = 0)

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopBoth() {
            client.close()
            gannet.stop()
            jetty.stop()
        }

        private val none = emptyList<Pair<String, String>>()

        /** A target, and the header lines sent with it: two under one name are two field lines. */
        @JvmStatic
        fun requests(): List<Array<Any>> = listOf(
            arrayOf("/count/3", none),
            arrayOf("/count/not-a-number", none),
            arrayOf("/hello/ada", none),
            arrayOf("/hello/ada?shout=true", none),
            arrayOf("/hello/ada?shout=loudly", none),
            arrayOf("/items/c++", none),
            arrayOf("/items/c%2B%2B", none),
            arrayOf("/items/a%2Fb", none),
            arrayOf("/items/ada%20lovelace", none),
            arrayOf("/items/%61da", none),
            arrayOf("/items/a%5Cb", none),
            arrayOf("/items/a%3Bb", none),
            arrayOf("/items/x?Q=upper", none),
            arrayOf("/items/%2561", none),
            arrayOf("/items/x/", none),
            arrayOf("/items/./x", none),
            arrayOf("/items/../items/x", none),
            arrayOf("/items/x/..", none),
            arrayOf("/items/x/.", none),
            arrayOf("/items/..", none),
            arrayOf("/items/.", none),
            arrayOf("/items/%2E%2E", none),
            arrayOf("/items/%2e", none),
            arrayOf("/items/.%2E", none),
            arrayOf("/items/%2E%2E/x", none),
            arrayOf("/items/%5C..", none),
            arrayOf("/items/..%5C", none),
            arrayOf("/items/..%2Fx", none),
            arrayOf("/items/a/../../items/x", none),
            arrayOf("/items/x?q=..", none),
            arrayOf("/items/x?q=a+b", none),
            arrayOf("/items/x?q=a%2Bb", none),
            arrayOf("/items/x?q", none),
            arrayOf("/items/x?q=", none),
            arrayOf("/items/x?q=first&q=second", none),
            arrayOf(
                "/filters?tag=kotlin&tag=http&id=1%2C2",
                listOf("X-Feature" to "beta,dark", "Cookie" to "seen=x; seen=y"),
            ),
            arrayOf("/filters", listOf("X-Feature" to "beta", "X-Feature" to "dark")),
            arrayOf("/filters?id=2,0", none),
            arrayOf("/filters", none),
            arrayOf("/strict?term=t", listOf("X-Key" to "k", "Cookie" to "jar=j")),
            arrayOf("/strict", listOf("X-Key" to "k", "Cookie" to "jar=j")),
            arrayOf("/strict?term=t", listOf("Cookie" to "jar=j")),
            arrayOf("/strict?term=t", listOf("X-Key" to "k")),
        )

        @JvmStatic
        fun traversals(): List<String> =
            listOf(".", "..", "%2E", "%2e%2e", ".%2E", "%2E.", "%2E%2E/", "x/..", "x/%2E%2E").map { "/items/$it" }

        @JvmStatic
        fun malformed(): List<String> =
            listOf("/items/ada%zz", "/items/x?q=%zz", "/items/x?other=%zz", "/items/x?q=a&other=%4")
    }

    private fun ask(baseUrl: String, target: String, headers: List<Pair<String, String>>): Seen {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + target))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .GET()
            .build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Seen(res.statusCode(), res.headers().firstValue("Content-Type").orElse(null), res.body())
    }

    /** One request line written onto the socket as it stands, which no client would send. */
    private fun statusOf(baseUrl: String, target: String): Int {
        val url = URI.create(baseUrl)
        Socket(url.host, url.port).use { socket ->
            socket.getOutputStream().write(
                "GET $target HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            val statusLine = checkNotNull(socket.getInputStream().bufferedReader().readLine())
            return statusLine.split(" ")[1].toInt()
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("requests")
    fun `gannet reads every input as jetty does`(target: String, headers: List<Pair<String, String>>) {
        withClue("GET $target $headers") {
            ask(gannet.baseUrl, target, headers) shouldBe ask(jetty.baseUrl, target, headers)
        }
    }

    @Test
    fun `an encoded slash stays inside the segment that carried it`() {
        ask(gannet.baseUrl, "/items/a%2Fb", emptyList()).body shouldBe """{"fromPath":"a/b"}"""
    }

    @Test
    fun `and decoding happens once, so an encoded escape arrives as text`() {
        ask(gannet.baseUrl, "/items/%2561", emptyList()).body shouldBe """{"fromPath":"%61"}"""
    }

    @Test
    fun `a list reaches the handler as a list, whichever way it was spread`() {
        val headers = listOf("X-Feature" to "beta,dark", "Cookie" to "seen=x; seen=y")

        ask(gannet.baseUrl, "/filters?tag=kotlin&tag=http&id=1%2C2", headers).body shouldBe
            """{"tags":["kotlin","http"],"ids":[1,2],"features":["beta","dark"],"seen":["x","y"]}"""
    }

    @Test
    fun `a bad path param is a 400 counted against its own route`() {
        refused.clear()

        ask(gannet.baseUrl, "/count/not-a-number", emptyList()).status shouldBe 400
        refused shouldBe listOf("decode 400 /count/{n}")
    }

    @Test
    fun `every printable character, escaped, arrives in the path and the query as jetty reads it`() {
        val characters = (' '..'~').map { it.toString() } + listOf("é", "€", "ß")
        val differing = characters.map { "a${it}b" }.filter { sent ->
            val target = "/items/${escaped(sent)}?q=${URLEncoder.encode(sent, Charsets.UTF_8)}"
            ask(gannet.baseUrl, target, emptyList()) != ask(jetty.baseUrl, target, emptyList())
        }

        differing shouldBe emptyList()
    }

    /** Every byte outside RFC 3986's unreserved set as an escape, which is what a client sends in a path. */
    private fun escaped(segment: String): String = segment.toByteArray().joinToString("") { byte ->
        val c = byte.toInt().toChar()
        if (byte >= 0 && (c.isLetterOrDigit() || c in "-._~")) c.toString() else "%%%02X".format(byte)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("traversals")
    fun `no spelling of a dot segment reaches a handler as a value`(target: String) {
        val body = ask(gannet.baseUrl, target, emptyList()).body

        withClue("GET $target answered $body") {
            (body == """{"fromPath":".."}""" || body == """{"fromPath":"."}""") shouldBe false
        }
    }

    @Test
    fun `a path climbing above the root is routed nowhere, a 404 as on Pekko`() {
        // Jetty refuses the request line with its own 400; nothing here clamps it to `/items/x` either.
        statusOf(gannet.baseUrl, "/../items/x") shouldBe 404
        statusOf(gannet.baseUrl, "/..") shouldBe 404
        statusOf(jetty.baseUrl, "/../items/x") shouldBe 400
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    fun `a malformed escape is refused, as jetty refuses it`(target: String) {
        val status = statusOf(gannet.baseUrl, target)

        withClue("GET $target") {
            status shouldBe statusOf(jetty.baseUrl, target)
            (status in 400..499) shouldBe true
        }
    }
}
