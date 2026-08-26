package example.codecs

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsoniter.JsoniterCodecs
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.openapi.Docs
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =========================================================== 1. the payloads

@Serializable
data class Note(
    val id: Long,
    val text: String,
    val level: Level = Level.INFO,
    val author: String? = null,
)

enum class Level { INFO, WARN }

/**
 * `pelican-jsoniter` writes a `type` carrying the branch's class name and
 * nothing configures that, so the annotations below are matched to it: without
 * the `@SerialName`s, kotlinx would send the package-qualified name instead.
 */
@Serializable
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = Email::class, name = "Email"),
    JsonSubTypes.Type(value = Sms::class, name = "Sms"),
)
sealed interface Channel

@Serializable
@SerialName("Email")
data class Email(val address: String) : Channel

@Serializable
@SerialName("Sms")
data class Sms(val number: String) : Channel

@Serializable
data class Delivery(val note: Note, val to: Channel)

// ========================================================= 2. the endpoints

val noteId = pathParam<Long>("noteId", description = "The note's id")

val delivery = jsonBody<Delivery>(description = "A note, and where it is going")

val getNote = endpoint(noteId) {
    get("notes" / noteId)
    summary = "Fetch one note"
    tag("notes")
    json<Note>()
}

val deliverNote = endpoint(delivery) {
    post("deliveries")
    summary = "Send a note"
    tag("notes")
    json<Delivery>(status = 202)
}

val codecEndpoints = listOf(getNote, deliverNote)

// =========================================================== 3. the handlers

val codecRoutes: List<ServerEndpoint> = listOf(

    getNote handledNow { id ->                        // id: Long
        Note(id, "Note $id", if (id % 2 == 0L) Level.WARN else Level.INFO)
    },

    deliverNote handledNow { sent -> sent },          // sent: Delivery
)

// ============================================================ 4. the services

/** The same endpoints and handlers, over whichever JSON library is passed. */
fun notesApi(codecs: Codecs): Api = api(
    endpoints = codecRoutes,
    codecs = codecs,
) {
    title = "Notes"
    version = "1.0.0"
    description = "The same service, read and written by three JSON libraries."
}

/** Documentation needs only the schema half of a codec module. */
fun notesSpec(schemas: SchemaSource): ApiSpec = ApiSpec(
    endpoints = codecEndpoints,
    schemas = schemas,
    title = "Notes",
    version = "1.0.0",
)

/** `./gradlew :example:runCodecs` — Jackson :8080, kotlinx :8081, jsoniter :8082. */
fun main(args: Array<String>) {
    val basePort = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val docs = docs { docsPath = "/api-docs" }

    val jackson = notesApi(JacksonCodecs)
        .startWithDocs(port = basePort, docs = docs, systemName = "notes-jackson")
    val kotlinx = notesApi(KotlinxCodecs)
        .startWithDocs(port = basePort + 1, docs = docs, systemName = "notes-kotlinx")
    val jsoniter = notesApi(JsoniterCodecs)
        .startWithDocs(port = basePort + 2, docs = docs, systemName = "notes-jsoniter")

    println(
        """
        |The same service, three JSON libraries:
        |
        |  Jackson      ${jackson.baseUrl}
        |  kotlinx      ${kotlinx.baseUrl}
        |  jsoniter     ${jsoniter.baseUrl}
        |
        |Ask all three the same questions:
        |
        |  curl ${jackson.baseUrl}/notes/2
        |  curl ${kotlinx.baseUrl}/notes/2
        |  curl ${jsoniter.baseUrl}/notes/2
        |
        |A union travelling inside a payload, which is the shape each library
        |had to be told about:
        |
        |  curl -X POST ${jsoniter.baseUrl}/deliveries -H 'Content-Type: application/json' \
        |    -d '{"note":{"id":1,"text":"Ship it"},"to":{"type":"Email","address":"ada@example.com"}}'
        |
        |The bytes, and the documents, should not differ:
        |
        |  diff <(curl -s ${jackson.baseUrl}/notes/2) <(curl -s ${jsoniter.baseUrl}/notes/2)
        |  diff <(curl -s ${jackson.baseUrl}/openapi.json) <(curl -s ${kotlinx.baseUrl}/openapi.json)
        |
        |Ctrl-C to stop.
        """.trimMargin(),
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            jsoniter.stop()
            kotlinx.stop()
            jackson.stop()
        },
    )
    jackson.block()
}

/** What every example binds to unless told otherwise; `--args=8081` moves it. */
private const val DEFAULT_PORT = 8080
