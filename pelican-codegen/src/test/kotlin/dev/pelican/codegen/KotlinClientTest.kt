// See pelican-openapi's OpenApi.kt: the `jsonObj { ... }` schema built here
// reads as the JSON it stands for, and ktlint's `wrapping` rule would break it
// into a staircase.
@file:Suppress("ktlint:standard:wrapping")

package dev.pelican.codegen

import dev.pelican.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * The generated file is checked here as text, which is all this module can do:
 * whether it compiles and *runs* is asserted in `:example`, where the generated
 * client is checked in against a server that can answer it.
 *
 * As in `pelican-openapi`, the schemas are hand-written, so these tests need no
 * codec module — and prove the generator reads whatever the spec's own
 * [SchemaSource] produced rather than having its own opinion about a Kotlin
 * class.
 */
class KotlinClientTest {

    object WidgetSchemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(name, jsonObj {
                    "type" to "object"
                    put("properties", jsonObj {
                        put("id", jsonObj {
                            "type" to "integer"
                            "format" to "int64"
                        })
                        put("name", jsonObj { "type" to "string" })
                        // Required *and* nullable, so the `?` in the generated
                        // client can only have come from reading the union.
                        put("note", jsonObj {
                            put("type", jsonStrings(listOf("string", "null")))
                        })
                        put("tags", jsonObj {
                            "type" to "array"
                            put("items", jsonObj { "type" to "string" })
                        })
                        put("colour", jsonObj {
                            "type" to "string"
                            put("enum", jsonStrings(listOf("RED", "GREEN")))
                        })
                        // The other 3.1 nullability spelling: a reference has no
                        // `type` to widen, so it goes under `anyOf` beside null.
                        // 3.0 could not say this at all.
                        put("parent", jsonObj {
                            put("anyOf", jsonArr(listOf(
                                components.ref(name),
                                jsonObj { "type" to "null" },
                            )))
                        })
                    })
                    put("required", jsonStrings(listOf("id", "name", "note", "colour")))
                })
            }
            return components.ref(name)
        }
    }

    data class Widget(val id: Long, val name: String)
    data class NewWidget(val name: String)
    data class Problem(val detail: String)

    enum class Colour { RED, GREEN }

    private val widgetId = pathParam<Long>("widgetId")
    private val page = queryParam<Int>("page").default(1)
    private val colour = queryParam<Colour>("colour").optional()
    private val traceId = headerParam<String>("X-Trace-Id").optional()
    private val apiKey = headerParam<String>("X-Api-Key")
    private val newWidget = jsonBody<NewWidget>()
    private val upload = rawBody()
    private val theme = cookieParam<String>("theme").optional()
    private val widgetForm = formBody<NewWidget>()
    private val label = textPart<String>("label")
    private val attachment = filePart("attachment", contentType = "text/plain")

    private val noSuchWidget = errorJson<Problem>(404, "No widget with that id")
    private val notYours = errorJson<Problem>(403, "Someone else's widget")

    private val getWidget = endpoint(widgetId) {
        get("widgets" / widgetId)
        summary = "Fetch a widget"
        operationId = "getWidget"
        json<Widget>() orFail noSuchWidget
    }

    private val streamWidgets = endpoint(page, colour, traceId) {
        get("widgets")
        operationId = "streamWidgets"
        ndjson<Widget>()
    }

    private val listWidgets = endpoint(page) {
        get("widgets" / "all")
        operationId = "listWidgets"
        jsonArray<Widget>()
    }

    private val watchWidgets = endpoint {
        get("widgets" / "watch")
        operationId = "watchWidgets"
        sse<Widget>()
    }

    private val createWidget = endpoint(apiKey, newWidget) {
        post("widgets")
        operationId = "createWidget"
        json<Widget>(status = 201).orFail(noSuchWidget, notYours)
    }

    private val deleteWidget = endpoint(widgetId) {
        delete("widgets" / widgetId)
        operationId = "deleteWidget"
        empty(status = 204)
    }

    private val uploadWidget = endpoint(upload) {
        post("widgets" / "upload")
        operationId = "uploadWidget"
        bytes()
    }

    private val signInWidget = endpoint(widgetForm) {
        post("widgets" / "form")
        operationId = "signInWidget"
        json<Widget>()
    }

    private val importWidgets = endpoint(label, attachment) {
        post("widgets" / "import")
        operationId = "importWidgets"
        json<Widget>()
    }

    private val themed = endpoint(theme) {
        get("widgets" / "themed")
        operationId = "themed"
        json<Widget>()
    }

    private val rebuild = endpoint {
        post("internal" / "rebuild")
        hidden = true
        operationId = "rebuild"
        empty(status = 202)
    }

    /** No operationId: both interpreters fall back to the derived name. */
    private val unnamed = endpoint(widgetId) {
        get("widgets" / widgetId / "history")
        json<Widget>()
    }

    private fun spec() = ApiSpec(
        endpoints = listOf(
            getWidget, streamWidgets, listWidgets, watchWidgets, createWidget, deleteWidget,
            uploadWidget, signInWidget, importWidgets, themed, rebuild, unnamed,
        ),
        schemas = WidgetSchemas,
        title = "Widget Shop",
        version = "2.0.0",
        servers = listOf("https://widgets.example.com"),
    )

    private val client = spec().kotlinClient("com.example.widgets")

    // ------------------------------------------------------------ the shape

    @Test
    fun `it declares the package it was asked for and is named after the title`() {
        client shouldContain "package com.example.widgets"
        client shouldContain "class WidgetShopClient("
        client shouldContain """private const val DEFAULT_BASE_URL = "https://widgets.example.com""""
    }

    @Test
    fun `it needs pelican-core and nothing else`() {
        val imports = client.lines().filter { it.startsWith("import ") }.toSet()
        imports.filterNot { it.startsWith("import java") || it.startsWith("import kotlin") }.toSet() shouldBe setOf(
            "import dev.pelican.BodyCodec",
            "import dev.pelican.Codecs",
            "import dev.pelican.UploadedFile",
            "import dev.pelican.formCodec",
        )
    }

    @Test
    fun `a method is named by its operationId, and falls back to the derived name`() {
        client shouldContain "fun getWidget("
        // The same string the OpenAPI document uses as its operationId.
        client shouldContain "fun getWidgetsByWidgetIdHistory("
    }

    // ------------------------------------------------------------ requests

    @Test
    fun `path parameters are positional and percent-encoded`() {
        client shouldContain """request("GET", "/widgets/${'$'}{segment(widgetId)}")"""
    }

    @Test
    fun `query parameters and headers are named parameters with defaults`() {
        // A header name is not a legal Kotlin identifier, so it is camel-cased —
        // mechanically, keeping every part, and mapped back on the way out.
        client shouldContain
            "fun streamWidgets(page: Int? = null, colour: WidgetColour? = null, xTraceId: String? = null)"
        client shouldContain """query = listOf("page" to page, "colour" to colour)"""
        client shouldContain """headerParams = listOf("X-Trace-Id" to xTraceId)"""
    }

    @Test
    fun `a required parameter has no default, and comes before the optional ones`() {
        client shouldContain "fun createWidget(body: NewWidget, xApiKey: String)"
        client shouldContain """headerParams = listOf("X-Api-Key" to xApiKey)"""
    }

    @Test
    fun `a json body goes through the codec, a raw body through the stream`() {
        client shouldContain
            "body = HttpRequest.BodyPublishers.ofString(newWidgetCodec.encodeToString(body)), " +
            "contentType = \"application/json\""
        client shouldContain "fun uploadWidget(body: InputStream): InputStream {"
        client shouldContain "body = HttpRequest.BodyPublishers.ofInputStream { body }"
    }

    @Test
    fun `a cookie parameter is a named parameter, and cookies travel as one header`() {
        client shouldContain "fun themed(theme: String? = null)"
        client shouldContain """cookies = listOf("theme" to theme)"""
    }

    @Test
    fun `a form body goes through a codec that reads the published schema`() {
        client shouldContain "fun signInWidget(body: NewWidget)"
        client shouldContain
            "body = HttpRequest.BodyPublishers.ofString(newWidgetFormCodec.encodeToString(body)), " +
            "contentType = \"application/x-www-form-urlencoded\""
        client shouldContain
            "private val newWidgetFormCodec: BodyCodec<NewWidget> = codecs.formCodec(typeOf<NewWidget>())"
    }

    @Test
    fun `multipart parts are parameters, and the file part is the type a handler receives`() {
        client shouldContain "fun importWidgets(label: String, attachment: UploadedFile)"
        client shouldContain
            """multipart = multipart(fields = listOf("label" to label), """ +
            """files = listOf("attachment" to attachment))"""
    }

    // ------------------------------------------------------------ responses

    @Test
    fun `each streaming shape gets the reader that frames it`() {
        client shouldContain "ndjsonFrames(body.bufferedReader())"
        client shouldContain "sseFrames(body.bufferedReader())"
        client shouldContain "jsonArrayFrames(body.reader())"
        client shouldContain "fun listWidgets(page: Int? = null): Streamed<Widget> {"
    }

    @Test
    fun `an empty response returns nothing and opaque bytes return the stream`() {
        client shouldContain "fun deleteWidget(widgetId: Long) {"
        client shouldContain "fun uploadWidget(body: InputStream): InputStream {"
    }

    @Test
    fun `a codec is resolved once per payload type, when the client is built`() {
        client shouldContain "private val widgetCodec: BodyCodec<Widget> = codecs.codec(typeOf<Widget>())"
        Regex("private val widgetCodec").findAll(client).count() shouldBe 1
    }

    // ------------------------------------------------------------ failures

    @Test
    fun `declared failures become a sealed type, one member per status`() {
        client shouldContain """
            sealed interface CreateWidgetFailure {
                val status: Int

                /** No widget with that id */
                data class NotFound(val body: Problem) : CreateWidgetFailure {
                    override val status: Int get() = 404
                }

                /** Someone else's widget */
                data class Forbidden(val body: Problem) : CreateWidgetFailure {
                    override val status: Int get() = 403
                }
            }
        """.trimIndent()
    }

    @Test
    fun `a fallible call returns an Outcome, and reads the failure that arrived`() {
        client shouldContain "fun getWidget(widgetId: Long): Outcome<GetWidgetFailure, Widget> {"
        client shouldContain
            "404 -> return Outcome.Err(GetWidgetFailure.NotFound(" +
            "problemCodec.decodeFromString(response.body())))"
        client shouldContain "return Outcome.Ok(widgetCodec.decodeFromString(response.body()))"
    }

    @Test
    fun `an endpoint with no declared failures returns the value and throws otherwise`() {
        client shouldNotContain "DeleteWidgetFailure"
        client shouldContain """if (!response.succeeded()) failed("DELETE", "/widgets/{widgetId}", response)"""
    }

    // ------------------------------------------------------------ payloads

    @Test
    fun `a named schema becomes a data class, with optional properties defaulted`() {
        client shouldContain """
            data class Widget(
                val id: Long,
                val name: String,
                val note: String?,
                val tags: List<String>? = null,
                val colour: WidgetColour,
                val parent: Widget? = null,
            )
        """.trimIndent()
    }

    @Test
    fun `a nullable property is read from 3_1's type union, and a nullable reference from its anyOf`() {
        // `note` is required, so nothing but the union `["string", "null"]` can
        // have made it nullable — and it is a String, not the `Any?` a
        // generator still looking for `nullable: true` would fall back to.
        client shouldContain "val note: String?,"
        // `parent` likewise: the anyOf has to resolve to the branch that is not
        // null, or the property would be typed `Any?` and say nothing.
        client shouldContain "val parent: Widget? = null,"
    }

    @Test
    fun `an enum is declared once and reused wherever the same constants appear`() {
        withClue(client) { Regex("enum class ").findAll(client).count() shouldBe 1 }
        client shouldContain "enum class WidgetColour { RED, GREEN }"
    }

    /**
     * A constant is written exactly as the wire spells it, in backticks where
     * Kotlin needs them. `EnumCodec` matches on the constant's name, so
     * renaming `in-progress` to `IN_PROGRESS` would need a codec-specific
     * annotation to map it back — which is the one thing these declarations
     * are meant to work without.
     */
    @Test
    fun `an enum keeps a constant the wire uses, in backticks where it has to`() {
        val jobs = object : SchemaSource {
            override fun schema(type: KType, components: SchemaComponents): JsonObj {
                if (!components.isRegistered("Job")) {
                    components.register("Job", jsonObj {
                        "type" to "object"
                        put("properties", jsonObj {
                            put("state", jsonObj {
                                "type" to "string"
                                put("enum", jsonStrings(listOf("queued", "in-progress")))
                            })
                        })
                        put("required", jsonStrings(listOf("state")))
                    })
                }
                return components.ref("Job")
            }
        }
        val getJob = endpoint {
            get("jobs")
            operationId = "getJob"
            json<Widget>()
        }

        ApiSpec(listOf(getJob), jobs, title = "Jobs").kotlinClient("com.example.jobs") shouldContain
            "enum class JobState { queued, `in-progress` }"
    }

    // ------------------------------------------------------------ hidden

    @Test
    fun `a hidden endpoint is left out, as it is left out of the document`() {
        withClue("a hidden endpoint reached the generated client") { client shouldNotContain "rebuild" }
        spec().kotlinClient("com.example.widgets", includeHidden = true) shouldContain "rebuild"
    }

    // ------------------------------------------------------------ layering

    @Test
    fun `the client name and base url can be chosen`() {
        val custom = spec().kotlinClient("x", clientName = "Widgets", baseUrl = "")
        custom shouldContain "class Widgets("
        custom shouldContain "private const val DEFAULT_BASE_URL = \"\""
    }

    @Test
    fun `neither pekko nor a codec module is on this module's classpath`() {
        shouldThrow<ClassNotFoundException> { Class.forName("org.apache.pekko.http.javadsl.server.Directives") }
        shouldThrow<ClassNotFoundException> { Class.forName("com.fasterxml.jackson.databind.ObjectMapper") }
    }

    @Test
    fun `writing it lays out the package directories and returns the file`() {
        val root = Files.createTempDirectory("pelican-codegen")
        try {
            val written = spec().writeKotlinClient(root, packageName = "com.example.widgets")

            written shouldBe root.resolve("com/example/widgets/WidgetShopClient.kt")
            written.readText() shouldBe client

            // Regenerating replaces it in place: there is nothing to move after.
            val again = spec().writeKotlinClient(root, packageName = "com.example.widgets")
            again shouldBe written
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the generated file is stable`() {
        // Same spec, same bytes. A generator that reordered its output would
        // make every regeneration a diff nobody can review.
        spec().kotlinClient("com.example.widgets") shouldBe client
    }
}
