package example.codecs

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsoniter.JsoniterCodecs
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.openapi.Docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =========================================================== 1. the payloads
//
// One set of types, read and written by three JSON libraries. Jackson and
// kotlinx.serialization each have to be told the things Kotlin does not say,
// and each is told in its own vocabulary; jsoniter is told nothing, because
// `pelican-jsoniter` binds through the primary constructor and there is no
// jsoniter annotation to write.

@Serializable
data class Note(
    val id: Long,
    val text: String,
    /** A default, so a body that omits it exercises what each library does with one. */
    val level: Level = Level.INFO,
    /** Nullable, so an absent value and a null one can be told apart on the wire. */
    val author: String? = null,
)

/** No annotation for anyone: an enum is the one shape all three read off the Kotlin. */
enum class Level { INFO, WARN }

/**
 * Where a note is sent — the shape no library can read off the Kotlin, since
 * nothing in `sealed interface Channel` says which property carries the branch
 * or what selects each one.
 *
 * Lining the three up is the exercise. `pelican-jsoniter` writes a `type`
 * carrying the branch's own class name and nothing configures that, so it is
 * the fixed point: Jackson is pointed at the same property with the same
 * names, and kotlinx already calls it `type`, so each branch takes a
 * `@SerialName` that is its class name rather than the package-qualified one
 * it would default to. All three then send the same bytes.
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
//
// Descriptions, shared by all three services below. Nothing here names a JSON
// library.

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
//
// Also shared. A handler never sees the codec: it takes decoded values and
// returns values to encode.

val codecRoutes: List<ServerEndpoint> = listOf(

    getNote handledNow { id ->                        // id: Long
        Note(id, "Note $id", if (id % 2 == 0L) Level.WARN else Level.INFO)
    },

    deliverNote handledNow { sent -> sent },          // sent: Delivery
)

// ============================================================ 4. the services
//
// Three of them, differing in one argument.

/** The same endpoints and the same handlers, over whichever JSON library is passed. */
fun notesApi(codecs: Codecs): Api = Api(
    endpoints = codecRoutes,
    codecs = codecs,
    title = "Notes",
    version = "1.0.0",
    description = "The same service, read and written by three JSON libraries.",
)

/** The document, which needs only the schema half of a codec module. */
fun notesSpec(schemas: SchemaSource): ApiSpec = ApiSpec(
    endpoints = codecEndpoints,
    schemas = schemas,
    title = "Notes",
    version = "1.0.0",
)

/**
 * All three at once, so the same request can be sent to each and the answers
 * compared.
 *
 * ```
 * ./gradlew :example:runCodecs             # Jackson :8080, kotlinx :8081, jsoniter :8082
 * ./gradlew :example:runCodecs --args=9000
 * ```
 */
fun main(args: Array<String>) {
    val basePort = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val docs = Docs(docsPath = "/api-docs")

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
            jsoniter.stop().toCompletableFuture().join()
            kotlinx.stop().toCompletableFuture().join()
            jackson.stop().toCompletableFuture().join()
        },
    )
    Thread.currentThread().join()
}

/** What every example binds to unless told otherwise; `--args=8081` moves it. */
private const val DEFAULT_PORT = 8080
