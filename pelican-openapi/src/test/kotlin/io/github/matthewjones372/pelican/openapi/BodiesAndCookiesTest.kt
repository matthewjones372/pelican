package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.bufferedFile
import io.github.matthewjones372.pelican.cookieParam
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.filePart
import io.github.matthewjones372.pelican.formBody
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.minLength
import io.github.matthewjones372.pelican.ndjsonIn
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.textPart
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * What the document says about the three input kinds that are not a path, a
 * query, a header or a JSON payload.
 *
 * As in [SecurityTest], the schemas are hand-written, so nothing here needs a
 * codec module — and the assertions are about what the *interpreter* writes
 * rather than about what swagger-core thinks of a Kotlin class.
 */
class BodiesAndCookiesTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(name, jsonObj { "type" to "object" })
            }
            return components.ref(name)
        }
    }

    data class SignIn(val user: String)

    data class Widget(val id: Long)

    private val locale = cookieParam<String>("locale", description = "Which language to answer in").default("en")
    private val session = cookieParam<String>("session").optional()

    private val signInForm = formBody<SignIn>(description = "The sign-in form")

    private val eitherWay = formBody<SignIn>(description = "However it was posted") or jsonBody<SignIn>()

    private val caption = textPart("caption", StringCodec.minLength(3), description = "What to call it")
    private val notes = textPart<String>("notes").optional()
    private val thumbnail = bufferedFile("thumbnail", maxBytes = 4096, contentType = "image/png")
    private val attachment = filePart("attachment", contentType = "text/csv", description = "The file itself")

    private val read = endpoint(locale, session) {
        get("preferences")
        operationId = "preferences"
        json<Widget>()
    }

    private val form = endpoint(signInForm) {
        post("sign-in")
        operationId = "signIn"
        json<Widget>()
    }

    private val upload = endpoint(caption, notes, thumbnail, attachment) {
        post("upload")
        operationId = "upload"
        json<Widget>()
    }

    private val negotiated = endpoint(eitherWay) {
        post("sign-in-either-way")
        operationId = "signInEitherWay"
        json<Widget>()
    }

    private val streamed = endpoint(ndjsonIn<Widget>(description = "One widget per line")) {
        post("widgets" / "bulk")
        operationId = "bulkWidgets"
        json<Widget>(status = 202)
    }

    private val document =
        apiSpec(listOf(read, form, upload, negotiated, streamed), Schemas) {
            title = "Widgets"
        }.openApi()

    private val preferences = document / "paths" / "/preferences" / "get"
    private val signIn = document / "paths" / "/sign-in" / "post"
    private val uploaded = document / "paths" / "/upload" / "post"
    private val eitherWayOperation = document / "paths" / "/sign-in-either-way" / "post"
    private val bulk = document / "paths" / "/widgets/bulk" / "post"

    // ------------------------------------------------------------- cookies

    @Test
    fun `a cookie parameter is documented in the cookie location, like any other parameter`() {
        val parameters = (preferences / "parameters").arr()

        parameters.map { (it / "in").str() } shouldBe listOf("cookie", "cookie")
        parameters.map { (it / "name").str() } shouldBe listOf("locale", "session")
    }

    @Test
    fun `a cookie with a default is optional, and one without is not required either when optional`() {
        val parameters = (preferences / "parameters").arr()

        parameters.map { (it / "required").bool() } shouldBe listOf(false, false)
        (parameters[0] / "description").str() shouldBe "Which language to answer in"
        (parameters[0] / "schema" / "type").str() shouldBe "string"
    }

    // ---------------------------------------------------------------- forms

    @Test
    fun `a form body is documented under its own media type, with the type's own schema`() {
        val body = signIn / "requestBody"

        (body / "content").keys() shouldBe setOf("application/x-www-form-urlencoded")
        (body / "description").str() shouldBe "The sign-in form"
        (body / "content" / "application/x-www-form-urlencoded" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/SignIn"
    }

    @Test
    fun `a body with several encodings is one content entry per encoding, all one schema`() {
        val body = eitherWayOperation / "requestBody"

        (body / "content").keys() shouldBe
            setOf("application/x-www-form-urlencoded", "application/json")
        // The same schema under both, because the entries are two ways of
        // sending one payload rather than two payloads.
        (body / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/SignIn"
        (body / "content" / "application/x-www-form-urlencoded" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/SignIn"
        (body / "description").str() shouldBe "However it was posted"
    }

    // ------------------------------------------------------------ multipart

    @Test
    fun `a multipart body is an object with one property per part`() {
        val schema = uploaded / "requestBody" / "content" / "multipart/form-data" / "schema"

        (schema / "type").str() shouldBe "object"
        (schema / "properties").keys() shouldBe setOf("caption", "notes", "thumbnail", "attachment")
        (schema / "required").strings() shouldBe listOf("caption", "thumbnail", "attachment")
    }

    @Test
    fun `a file part is the binary property, and a text part carries its own refinement`() {
        val properties = uploaded / "requestBody" / "content" / "multipart/form-data" / "schema" / "properties"

        // 3.1 spells "these are opaque bytes" as contentMediaType rather than
        // 3.0's `format: binary`, and the part's own declared type is what goes
        // there — so a reader is told it is a CSV, not merely that it is a file.
        (properties / "attachment" / "type").str() shouldBe "string"
        (properties / "attachment" / "contentMediaType").str() shouldBe "text/csv"
        (properties / "attachment").obj()["format"].shouldBeNull()
        (properties / "attachment" / "description").str() shouldBe "The file itself"

        // The same facet the same refinement would put on a query parameter,
        // so Swagger UI refuses to submit what the server would reject.
        (properties / "caption" / "minLength").num() shouldBe 3
        (properties / "caption" / "description").str() shouldBe "What to call it"
    }

    @Test
    fun `a buffered part publishes the bound it will be read with`() {
        val properties = uploaded / "requestBody" / "content" / "multipart/form-data" / "schema" / "properties"

        // What the server will hold is part of the contract rather than
        // something a caller finds out by being refused — and it is the one
        // thing about such a part an import could not otherwise recover.
        (properties / "thumbnail" / "maxLength").num() shouldBe 4096
        (properties / "attachment").obj()["maxLength"].shouldBeNull()
    }

    @Test
    fun `what a file part expects to carry reaches the encoding block`() {
        val encoding = uploaded / "requestBody" / "content" / "multipart/form-data" / "encoding"

        (encoding / "attachment" / "contentType").str() shouldBe "text/csv"
    }

    @Test
    fun `a multipart body has no parameters section, since its parts are not parameters`() {
        (uploaded / "parameters").shouldBeNull()
    }

    // ------------------------------------------------------------- a streamed body

    @Test
    fun `a streamed body publishes one frame's schema under the ndjson media type`() {
        val content = bulk / "requestBody" / "content"

        content.keys() shouldBe setOf("application/x-ndjson")
        // 3.1 has only `schema`; `OpenApi32Test` is where it moves to
        // `itemSchema`, which is the field that means one frame.
        (content / "application/x-ndjson" / "schema" / "\$ref").str() shouldBe "#/components/schemas/Widget"
        (bulk / "requestBody" / "description").str() shouldBe "One widget per line"
        (bulk / "requestBody" / "required").bool() shouldBe true
    }
}
