package example.codecs

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter; brings pelican-core and Pekko HTTP
 *         implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC1")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC1")
 *         // startWithDocs, /openapi.json and Swagger UI
 *         implementation("io.github.matthewjones372:pelican-pekko-docs:1.0.0-RC1")
 *     }
 */

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow

// =========================================================== 1. the payloads

data class Note(
    val id: Long,
    val text: String,
    val level: Level = Level.INFO,
    val author: String? = null,
)

enum class Level { INFO, WARN }

/**
 * The branch names are written out rather than left to a library's default,
 * because the default is what a second codec module would have to be persuaded
 * to match: a discriminator carrying a package-qualified class name is a
 * property of the writer, not of the contract.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = Email::class, name = "Email"),
    JsonSubTypes.Type(value = Sms::class, name = "Sms"),
)
sealed interface Channel

data class Email(val address: String) : Channel

data class Sms(val number: String) : Channel

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
    description = "The same service, read and written by whichever JSON library it is handed."
}

/** Documentation needs only the schema half of a codec module. */
fun notesSpec(schemas: SchemaSource): ApiSpec = apiSpec(codecEndpoints, schemas) {
    title = "Notes"
    version = "1.0.0"
}

/** `./gradlew :example:runCodecs` — the notes service on :8080, over Jackson. */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val docs = docs { docsPath = "/api-docs" }

    val jackson = notesApi(JacksonCodecs)
        .startWithDocs(port = port, docs = docs, systemName = "notes-jackson")

    println(
        """
        |The notes service, over Jackson:
        |
        |  ${jackson.baseUrl}
        |
        |  curl ${jackson.baseUrl}/notes/2
        |
        |A union travelling inside a payload, which is the shape a codec module
        |has to be told about:
        |
        |  curl -X POST ${jackson.baseUrl}/deliveries -H 'Content-Type: application/json' \
        |    -d '{"note":{"id":1,"text":"Ship it"},"to":{"type":"Email","address":"ada@example.com"}}'
        |
        |  curl -s ${jackson.baseUrl}/openapi.json
        |
        |Ctrl-C to stop.
        """.trimMargin(),
    )

    Runtime.getRuntime().addShutdownHook(Thread { jackson.stop() })
    jackson.block()
}

/** What every example binds to unless told otherwise; `--args=8081` moves it. */
private const val DEFAULT_PORT = 8080
