// See pelican-openapi's OpenApi.kt: the `jsonObj { ... }` schema built here
// reads as the JSON it stands for, and ktlint's `wrapping` rule would break it
// into a staircase.
@file:Suppress("ktlint:standard:wrapping")

package dev.pelican.codegen

import dev.pelican.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertTrue("package com.example.widgets" in client, client.take(600))
        assertTrue("class WidgetShopClient(" in client)
        assertTrue("""private const val DEFAULT_BASE_URL = "https://widgets.example.com"""" in client)
    }

    @Test
    fun `it needs pelican-core and nothing else`() {
        val imports = client.lines().filter { it.startsWith("import ") }.toSet()
        assertEquals(
            setOf(
                "import dev.pelican.BodyCodec",
                "import dev.pelican.Codecs",
                "import dev.pelican.UploadedFile",
                "import dev.pelican.formCodec",
            ),
            imports.filterNot { it.startsWith("import java") || it.startsWith("import kotlin") }.toSet(),
        )
    }

    @Test
    fun `a method is named by its operationId, and falls back to the derived name`() {
        assertTrue("fun getWidget(" in client)
        // The same string the OpenAPI document uses as its operationId.
        assertTrue("fun getWidgetsByWidgetIdHistory(" in client)
    }

    // ------------------------------------------------------------ requests

    @Test
    fun `path parameters are positional and percent-encoded`() {
        assertTrue("""request("GET", "/widgets/${'$'}{segment(widgetId)}")""" in client, client)
    }

    @Test
    fun `query parameters and headers are named parameters with defaults`() {
        // A header name is not a legal Kotlin identifier, so it is camel-cased —
        // mechanically, keeping every part, and mapped back on the way out.
        assertTrue(
            "fun streamWidgets(page: Int? = null, colour: WidgetColour? = null, xTraceId: String? = null)" in client,
            client,
        )
        assertTrue("""query = listOf("page" to page, "colour" to colour)""" in client)
        assertTrue("""headerParams = listOf("X-Trace-Id" to xTraceId)""" in client)
    }

    @Test
    fun `a required parameter has no default, and comes before the optional ones`() {
        assertTrue("fun createWidget(body: NewWidget, xApiKey: String)" in client, client)
        assertTrue("""headerParams = listOf("X-Api-Key" to xApiKey)""" in client)
    }

    @Test
    fun `a json body goes through the codec, a raw body through the stream`() {
        assertTrue(
            "body = HttpRequest.BodyPublishers.ofString(newWidgetCodec.encodeToString(body)), " +
                "contentType = \"application/json\"" in client,
            client,
        )
        assertTrue("fun uploadWidget(body: InputStream): InputStream {" in client)
        assertTrue("body = HttpRequest.BodyPublishers.ofInputStream { body }" in client)
    }

    @Test
    fun `a cookie parameter is a named parameter, and cookies travel as one header`() {
        assertTrue("fun themed(theme: String? = null)" in client, client)
        assertTrue("""cookies = listOf("theme" to theme)""" in client, client)
    }

    @Test
    fun `a form body goes through a codec that reads the published schema`() {
        assertTrue("fun signInWidget(body: NewWidget)" in client, client)
        assertTrue(
            "body = HttpRequest.BodyPublishers.ofString(newWidgetFormCodec.encodeToString(body)), " +
                "contentType = \"application/x-www-form-urlencoded\"" in client,
            client,
        )
        assertTrue(
            "private val newWidgetFormCodec: BodyCodec<NewWidget> = codecs.formCodec(typeOf<NewWidget>())" in client,
            client,
        )
    }

    @Test
    fun `multipart parts are parameters, and the file part is the type a handler receives`() {
        assertTrue("fun importWidgets(label: String, attachment: UploadedFile)" in client, client)
        assertTrue(
            """multipart = multipart(fields = listOf("label" to label), """ +
                """files = listOf("attachment" to attachment))""" in client,
            client,
        )
    }

    // ------------------------------------------------------------ responses

    @Test
    fun `each streaming shape gets the reader that frames it`() {
        assertTrue("ndjsonFrames(body.bufferedReader())" in client)
        assertTrue("sseFrames(body.bufferedReader())" in client)
        assertTrue("jsonArrayFrames(body.reader())" in client)
        assertTrue("fun listWidgets(page: Int? = null): Streamed<Widget> {" in client)
    }

    @Test
    fun `an empty response returns nothing and opaque bytes return the stream`() {
        assertTrue("fun deleteWidget(widgetId: Long) {" in client, client)
        assertTrue("fun uploadWidget(body: InputStream): InputStream {" in client)
    }

    @Test
    fun `a codec is resolved once per payload type, when the client is built`() {
        assertTrue(
            "private val widgetCodec: BodyCodec<Widget> = codecs.codec(typeOf<Widget>())" in client,
            client,
        )
        assertEquals(1, Regex("private val widgetCodec").findAll(client).count())
    }

    // ------------------------------------------------------------ failures

    @Test
    fun `declared failures become a sealed type, one member per status`() {
        assertTrue(
            """
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
            """.trimIndent() in client,
            client,
        )
    }

    @Test
    fun `a fallible call returns an Outcome, and reads the failure that arrived`() {
        assertTrue("fun getWidget(widgetId: Long): Outcome<GetWidgetFailure, Widget> {" in client)
        assertTrue(
            "404 -> return Outcome.Err(GetWidgetFailure.NotFound(" +
                "problemCodec.decodeFromString(response.body())))" in client,
            client,
        )
        assertTrue("return Outcome.Ok(widgetCodec.decodeFromString(response.body()))" in client)
    }

    @Test
    fun `an endpoint with no declared failures returns the value and throws otherwise`() {
        assertFalse("DeleteWidgetFailure" in client)
        assertTrue("""if (!response.succeeded()) failed("DELETE", "/widgets/{widgetId}", response)""" in client)
    }

    // ------------------------------------------------------------ payloads

    @Test
    fun `a named schema becomes a data class, with optional properties defaulted`() {
        assertTrue(
            """
            data class Widget(
                val id: Long,
                val name: String,
                val note: String?,
                val tags: List<String>? = null,
                val colour: WidgetColour,
                val parent: Widget? = null,
            )
            """.trimIndent() in client,
            client,
        )
    }

    @Test
    fun `a nullable property is read from 3_1's type union, and a nullable reference from its anyOf`() {
        // `note` is required, so nothing but the union `["string", "null"]` can
        // have made it nullable — and it is a String, not the `Any?` a
        // generator still looking for `nullable: true` would fall back to.
        assertTrue("val note: String?," in client, client)
        // `parent` likewise: the anyOf has to resolve to the branch that is not
        // null, or the property would be typed `Any?` and say nothing.
        assertTrue("val parent: Widget? = null," in client, client)
    }

    @Test
    fun `an enum is declared once and reused wherever the same constants appear`() {
        assertEquals(1, Regex("enum class ").findAll(client).count(), client)
        assertTrue("enum class WidgetColour { RED, GREEN }" in client)
    }

    // ------------------------------------------------------------ hidden

    @Test
    fun `a hidden endpoint is left out, as it is left out of the document`() {
        assertFalse("rebuild" in client, "a hidden endpoint reached the generated client")
        assertTrue("rebuild" in spec().kotlinClient("com.example.widgets", includeHidden = true))
    }

    // ------------------------------------------------------------ layering

    @Test
    fun `the client name and base url can be chosen`() {
        val custom = spec().kotlinClient("x", clientName = "Widgets", baseUrl = "")
        assertTrue("class Widgets(" in custom)
        assertTrue("private const val DEFAULT_BASE_URL = \"\"" in custom)
    }

    @Test
    fun `neither pekko nor a codec module is on this module's classpath`() {
        assertTrue(runCatching { Class.forName("org.apache.pekko.http.javadsl.server.Directives") }.isFailure)
        assertTrue(runCatching { Class.forName("com.fasterxml.jackson.databind.ObjectMapper") }.isFailure)
    }

    @Test
    fun `writing it lays out the package directories and returns the file`() {
        val root = Files.createTempDirectory("pelican-codegen")
        try {
            val written = spec().writeKotlinClient(root, packageName = "com.example.widgets")

            assertEquals(
                root.resolve("com/example/widgets/WidgetShopClient.kt"),
                written,
            )
            assertEquals(client, written.readText())

            // Regenerating replaces it in place: there is nothing to move after.
            val again = spec().writeKotlinClient(root, packageName = "com.example.widgets")
            assertEquals(written, again)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the generated file is stable`() {
        // Same spec, same bytes. A generator that reordered its output would
        // make every regeneration a diff nobody can review.
        assertEquals(client, spec().kotlinClient("com.example.widgets"))
    }
}
