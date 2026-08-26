package example.codecs

import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.reflect.typeOf

/**
 * The agreement matrix for the codec modules, run over the ones main ships.
 *
 * 1.0 ships one, so [libraries] holds one and every claim below is a claim
 * about a singleton — true, and asserting less than it used to. The shape is
 * kept because a returning codec module is a row added here and nothing else;
 * the `multi-backend` branch is where the same file runs three.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PluggableCodecsTest {

    private val libraries: List<Pair<String, Codecs>> = listOf(
        "jackson" to JacksonCodecs,
    )

    private lateinit var apps: Map<String, ApiClient>

    @BeforeAll
    fun setUp() {
        apps = libraries.associate { (name, codecs) -> name to notesApi(codecs).inMemory("notes-$name") }
    }

    @AfterAll
    fun tearDown() = apps.values.forEach { it.close() }

    @Test
    fun `every library reads and writes the same payloads`() {
        val sent = Delivery(Note(1, "Ship it", author = "ada"), Email("ada@example.com"))

        apps.forEach { (library, app) ->
            withClue(library) {
                app.call(getNote, 2L) shouldBe Note(2, "Note 2", Level.WARN)
                app.call(deliverNote, sent) shouldBe sent
            }
        }
    }

    @Test
    fun `every library writes the same bytes`() {
        val bodies = apps.mapValues { (_, app) -> app.response(getNote, 2L).body }

        // No `"author":null`: the schema marks a nullable property optional, so
        // a library leaves one out rather than choosing a spelling for it.
        bodies.values.distinct() shouldBe listOf("""{"id":2,"text":"Note 2","level":"WARN"}""")
    }

    @Test
    fun `a union travels the same way whichever library wrote it`() {
        // The one shape a library has to be told about, and the reason the
        // annotations in the example are lined up the way they are.
        val sent = Delivery(Note(7, "Ship it"), Sms("+441632960999"))
        val bodies = apps.mapValues { (_, app) -> app.response(deliverNote, sent).body }

        bodies.values.distinct() shouldBe listOf(
            """{"note":{"id":7,"text":"Ship it","level":"INFO"},""" +
                """"to":{"type":"Sms","number":"+441632960999"}}""",
        )
    }

    @Test
    fun `a body may leave out what has a default, whichever library reads it`() {
        val partial = """{"note":{"id":9,"text":"Terse"},"to":{"type":"Email","address":"ada@example.com"}}"""

        apps.forEach { (library, app) ->
            withClue(library) {
                val request = app.request(deliverNote, Delivery(Note(9, "Terse"), Email("ada@example.com")))
                app.transport.send(request.withBody(partial)).body shouldBe
                    """{"note":{"id":9,"text":"Terse","level":"INFO"},""" +
                    """"to":{"type":"Email","address":"ada@example.com"}}"""
            }
        }
    }

    @Test
    fun `every library reads a body any of them wrote`() {
        // Leaving a null property out is only honest if absent reads back as
        // null, and a consumer holding bytes from one of these services has to
        // be able to post them to the others. They agree on the bytes, so this
        // passes trivially today and stops the day one of them stops agreeing —
        // which is the day it is worth knowing.
        val sent = Delivery(Note(9, "Terse"), Email("ada@example.com"))
        val written = apps.mapValues { (_, app) -> app.response(deliverNote, sent).body }

        apps.forEach { (reader, app) ->
            written.forEach { (writer, body) ->
                withClue("$reader reading what $writer wrote: $body") {
                    app.transport.send(app.request(deliverNote, sent).withBody(body)).body shouldBe
                        written.getValue(reader)
                }
            }
        }
    }

    data class Reading(val missing: String?, val samples: List<Long?>, val tagged: Map<String, String?>)

    @Test
    fun `a null inside a list or a map is a value there, and every library writes it`() {
        // The line the omission stops at, and the reason `defaultMapper()` sets
        // content inclusion apart from property inclusion: an absent property
        // is one the schema called optional, while a null element is what the
        // list contains. Asked of the codecs rather than of a route, because it
        // is a claim about the libraries and not about a request.
        val value = Reading(null, listOf(1L, null), mapOf("k" to null))
        val written = libraries.associate { (library, codecs) ->
            library to codecs.codec<Reading>(typeOf<Reading>()).encodeToString(value)
        }

        withClue("the libraries wrote $written") {
            written.values.distinct() shouldBe listOf("""{"samples":[1,null],"tagged":{"k":null}}""")
        }
    }

    @Test
    fun `every library publishes the same document`() {
        // Compared as trees, because the one thing that does differ is the
        // order the branch schemas are registered in — swagger-core reaches
        // them after the payload that referenced the union, another library on
        // the way through it. An object's key order carries no meaning in JSON,
        // and `oneOf`, which is an array and does carry one, is compared in
        // order by the same assertion.
        val documents = libraries.associate { (library, codecs) ->
            library to Json.parseToJsonElement(notesSpec(codecs).openApiJson())
        }

        documents.values.distinct() shouldBe listOf(documents.getValue("jackson"))
    }
}
