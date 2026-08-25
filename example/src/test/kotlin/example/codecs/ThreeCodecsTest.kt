package example.codecs

import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsoniter.JsoniterCodecs
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThreeCodecsTest {

    private val libraries: List<Pair<String, Codecs>> = listOf(
        "jackson" to JacksonCodecs,
        "kotlinx" to KotlinxCodecs,
        "jsoniter" to JsoniterCodecs,
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
    fun `the three write the same bytes`() {
        val bodies = apps.mapValues { (_, app) -> app.response(getNote, 2L).body }

        bodies.values.distinct() shouldBe listOf("""{"id":2,"text":"Note 2","level":"WARN","author":null}""")
    }

    @Test
    fun `a union travels the same way whichever library wrote it`() {
        // The one shape each library had to be told about, and the reason the
        // annotations in the example are lined up the way they are.
        val sent = Delivery(Note(7, "Ship it"), Sms("+441632960999"))
        val bodies = apps.mapValues { (_, app) -> app.response(deliverNote, sent).body }

        bodies.values.distinct() shouldBe listOf(
            """{"note":{"id":7,"text":"Ship it","level":"INFO","author":null},""" +
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
                    """{"note":{"id":9,"text":"Terse","level":"INFO","author":null},""" +
                    """"to":{"type":"Email","address":"ada@example.com"}}"""
            }
        }
    }

    @Test
    fun `the three publish the same document`() {
        // Compared as trees, because the one thing that does differ is the
        // order the branch schemas are registered in — swagger-core reaches
        // them after the payload that referenced the union, the other two on
        // the way through it. An object's key order carries no meaning in JSON,
        // and `oneOf`, which is an array and does carry one, is compared in
        // order by the same assertion.
        val documents = libraries.associate { (library, codecs) ->
            library to Json.parseToJsonElement(notesSpec(codecs).openApiJson())
        }

        documents.values.distinct() shouldBe listOf(documents.getValue("jackson"))
    }
}
