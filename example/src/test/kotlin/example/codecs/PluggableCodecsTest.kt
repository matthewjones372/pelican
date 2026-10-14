package example.codecs

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import example.adoption.adoptPet
import example.adoption.adoptionApi
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.openapi.openApi
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.jetty.inMemory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.reflect.typeOf

/**
 * The agreement matrix for the codec modules, run over the ones main ships.
 * A returning codec module is a row added to [libraries] and nothing else.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PluggableCodecsTest {

    private val libraries: List<Pair<String, Codecs>> = listOf(
        "jackson" to JacksonCodecs,
        "kotlinx" to KotlinxCodecs,
    )

    private lateinit var apps: Map<String, ApiClient>

    /** Spec 0063's two tagged failures under one `503`, served over each library. */
    private lateinit var adoptions: Map<String, ApiClient>

    @BeforeAll
    fun setUp() {
        apps = libraries.associate { (name, codecs) -> name to notesApi(codecs).inMemory(Handlers.onVirtualThreads) }
        adoptions =
            libraries.associate { (name, codecs) -> name to adoptionApi(codecs).inMemory(Handlers.onVirtualThreads) }
    }

    @AfterAll
    fun tearDown() = (apps.values + adoptions.values).forEach { it.close() }

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

    @Serializable
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

    @Test
    fun `a tagged failure carries its tag whichever library wrote it`() {
        val bodies = listOf(2L, 3L).map { id -> adoptions.mapValues { (_, app) -> app.response(adoptPet, id).body } }

        bodies.map { it.values.distinct() } shouldBe listOf(
            listOf("""{"kind":"not_recorded","attempt":2}"""),
            listOf("""{"kind":"registry_down","retryIn":30}"""),
        )
    }

    @Test
    fun `every library publishes the same tagged failures`() {
        // The tag as a required const in each component and the status as a
        // oneOf over them (specs 0063 and 0066), which only holds if each
        // library hoists the payload to a component the document can reach.
        val documents = libraries.associate { (library, codecs) ->
            val spec = apiSpec(listOf(adoptPet), codecs) {
                title = "Adoption"
                version = "1.0.0"
            }
            library to Json.parseToJsonElement(spec.openApiJson())
        }

        documents.values.distinct() shouldBe listOf(documents.getValue("jackson"))
    }

    /**
     * Two differences kotlinx cannot avoid, lined up here rather than matched:
     * it names a component by its `@SerialName`, which a descriptor carries in
     * place of the class name, and it lists a union's branches sorted by name
     * where Jackson follows `@JsonSubTypes`. Golden checks pair branches by tag
     * (spec 0065), so the order moves no check.
     */
    @Serializable
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes(
        JsonSubTypes.Type(value = Barter::class, name = "Barter"),
        JsonSubTypes.Type(value = Voucher::class, name = "Voucher"),
    )
    sealed interface Tender

    @Serializable
    @SerialName("Voucher")
    data class Voucher(val number: String) : Tender

    @Serializable
    @SerialName("Barter")
    data object Barter : Tender

    @Serializable
    data class Paid(val by: Tender)

    @Serializable
    data object Declined

    @Serializable
    data class OnHold(val until: String)

    private val pay = endpoint {
        post("payments")
        json<Paid>().orFail(
            errorJson<Declined>(402, "The card was declined").tagged("declined"),
            errorJson<OnHold>(402, "The payment is held for review").tagged("held"),
        )
    }

    @Test
    fun `an object with no fields is a component, as a union branch and as a tagged failure`() {
        val documents = libraries.associate { (library, codecs) ->
            library to apiSpec(listOf(pay), codecs) {
                title = "Payments"
                version = "1.0.0"
            }.openApi()
        }

        // swagger-core describes a Kotlin `object` as `{}`, which any value
        // satisfies, and kotlinx as the object it is. The one difference, so
        // it is asserted and then set aside rather than matched.
        documents.mapValues { (_, doc) -> doc.declined()["type"] } shouldBe
            mapOf("jackson" to null, "kotlinx" to JsonStr("object"))
        val compared = documents.mapValues { (_, doc) -> doc.withDeclined(JsonObj(doc.declined().fields - "type")) }
        compared.values.distinct() shouldBe listOf(compared.getValue("jackson"))
    }

    @Test
    fun `an object with no fields travels as its tag alone`() {
        val written = libraries.associate { (library, codecs) ->
            library to codecs.codec<Paid>(typeOf<Paid>()).encodeToString(Paid(Barter))
        }

        written.values.distinct() shouldBe listOf("""{"by":{"type":"Barter"}}""")
    }

    private fun JsonObj.schemas() = (this["components"] as JsonObj)["schemas"] as JsonObj

    private fun JsonObj.declined() = schemas()["Declined"] as JsonObj

    private fun JsonObj.withDeclined(schema: JsonObj): JsonObj {
        val schemas = schemas() + JsonObj(mapOf("Declined" to schema))
        return this + JsonObj(mapOf("components" to JsonObj(mapOf("schemas" to schemas))))
    }
}
