package example.backends

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.Socket
import java.net.URI

/**
 * The request line, asked about every string rather than the seven someone
 * thought of.
 *
 * `AllBackendsTest` pins the cases a person can name — a plus, an encoded
 * slash, a doubly-encoded escape. This asks the same question of arbitrary
 * text: one value goes out in the path *and* in the query of one typed call,
 * and the handler is asked what arrived. Anything two backends read differently
 * comes back as a single failing case naming the string that did it, which is
 * the half of the parity claim a fixed table cannot make.
 *
 * The other direction is here too. A request line no client could have built —
 * `java.net.URI` refuses to hold one — goes onto the socket by hand, and the
 * answer has to be a refusal rather than a crash.
 */
class RequestLinePropertyTest {

    companion object {
        private val running: Map<String, Running> =
            allBackends.associate { it.name to it.start(port = 0) }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        @JvmStatic
        fun backends(): List<Array<Any>> =
            allBackends.map { arrayOf(it.name, clients.getValue(it.name)) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
        }

        /**
         * Enough cases to find a class of characters nobody listed, few enough
         * that a server answering all of them still leaves a build worth
         * running. Seeded, so a failure names a case that can be reproduced.
         */
        private val everyString = PropTestConfig(iterations = 200, seed = 0x0022_0022L)

        /** Fewer: a malformed line costs a connection each, and the space is small. */
        private val everyRefusal = PropTestConfig(iterations = 40, seed = 0x0022_0023L)

        /** Every printable ASCII character, and a few that take more than one byte. */
        private val characters: List<Char> = (' '..'~').toList() + listOf('é', '€', 'ß')

        /**
         * `.` and `..` are the two segments left out. They are dot segments,
         * which RFC 3986 says to resolve away: Pekko does and answers 404,
         * where another router may hand them over as values. That is a
         * disagreement about path *normalisation* rather than about decoding,
         * and settling it is not this suite's to do.
         */
        private val anySegment: Arb<String> = Arb.list(Arb.of(characters), 1..12)
            .map { it.joinToString("") }
            .filter { it != "." && it != ".." }
    }

    // ------------------------------------------------------------ what arrived

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `whatever the caller sent is what the handler is given`(name: String, client: ApiClient) {
        runBlocking {
            checkAll(everyString, anySegment) { sent ->
                val answer = client.call(roundtrip, In2(sent, sent))

                withClue("$name was sent '$sent' in the path and in the query") {
                    answer.fromPath shouldBe sent
                    answer.fromQuery shouldBe sent
                }
            }
        }
    }

    // --------------------------------------------------- and what could not have

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a request line no client could have produced is refused, not survived`(name: String, client: ApiClient) {
        // The server is looked up rather than passed: a `Running` is
        // `AutoCloseable`, and JUnit closes an argument that is — which stops
        // the server the rest of this class is still talking to.
        val server = running.getValue(name)

        runBlocking {
            checkAll(everyRefusal, anySegment) { sent ->
                // A `%` with nothing legal after it. Each backend either
                // refuses it while parsing the target or hands it to the index,
                // which refuses it; what none of them may do is answer 500.
                val line = "/items/" + sent.filter { it.isLetterOrDigit() } + "%zz"
                val status = server.statusOf(line)

                withClue("$name answered $status to 'GET $line'") { (status in 400..499) shouldBe true }
            }
        }
    }

    // ------------------------------------------------------------- query edges

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a query parameter nobody sent is absent, and one sent empty is empty`(name: String, client: ApiClient) {
        withClue(name) {
            client.queryOf("/items/x") shouldBe null
            client.queryOf("/items/x?q=") shouldBe ""
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and one sent with no value at all is present and empty`(name: String, client: ApiClient) {
        // `?q` is an empty string here rather than a null indistinguishable
        // from unsent, which is what the caller meant by writing it.
        withClue(name) { client.queryOf("/items/x?q") shouldBe "" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a plus in a query value is a space, which is the opposite of a path`(name: String, client: ApiClient) {
        // Both answers belong to the encoding rather than to Pelican: a query
        // string is `application/x-www-form-urlencoded`, and a path is not.
        withClue(name) {
            client.queryOf("/items/x?q=a+b") shouldBe "a b"
            client.queryOf("/items/x?q=a%2Bb") shouldBe "a+b"
        }
    }

    /**
     * What arrived in the query, for a target built by hand rather than from a
     * value. A null property is left out of the body rather than written as the
     * word, by every codec module, so an absent key is how "nobody sent one"
     * arrives here.
     */
    private fun ApiClient.queryOf(rawTarget: String): String? =
        Json.parseToJsonElement(transport.send(request(roundtrip, In2("x", null)).withPath(rawTarget)).body)
            .jsonObject["fromQuery"]?.jsonPrimitive?.contentOrNull

    /**
     * One request line, written onto the socket as it stands. `URI.create`
     * rejects a malformed escape before a client could send one, so the only
     * way to ask a server what it does with one is to not use a client.
     */
    private fun Running.statusOf(requestLine: String): Int {
        val url = URI.create(baseUrl)
        val head = "GET $requestLine HTTP/1.1\r\n" +
            "Host: ${url.host}:${url.port}\r\n" +
            "Connection: close\r\n\r\n"

        Socket(url.host, url.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            out.write(head.toByteArray())
            out.flush()
            val statusLine = checkNotNull(socket.getInputStream().bufferedReader().readLine()) {
                "$baseUrl closed the connection on 'GET $requestLine' without answering"
            }
            return statusLine.split(" ")[1].toInt()
        }
    }
}

private const val SOCKET_TIMEOUT_MILLIS = 5_000
