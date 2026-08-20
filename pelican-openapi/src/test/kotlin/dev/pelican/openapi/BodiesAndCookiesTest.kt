package dev.pelican.openapi

import dev.pelican.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    private val caption = textPart("caption", StringCodec.minLength(3), description = "What to call it")
    private val notes = textPart<String>("notes").optional()
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

    private val upload = endpoint(caption, notes, attachment) {
        post("upload")
        operationId = "upload"
        json<Widget>()
    }

    private val document = ApiSpec(listOf(read, form, upload), Schemas, title = "Widgets").openApi()

    private val preferences = document / "paths" / "/preferences" / "get"
    private val signIn = document / "paths" / "/sign-in" / "post"
    private val uploaded = document / "paths" / "/upload" / "post"

    // ------------------------------------------------------------- cookies

    @Test
    fun `a cookie parameter is documented in the cookie location, like any other parameter`() {
        val parameters = (preferences / "parameters").arr()

        assertEquals(listOf("cookie", "cookie"), parameters.map { (it / "in").str() })
        assertEquals(listOf("locale", "session"), parameters.map { (it / "name").str() })
    }

    @Test
    fun `a cookie with a default is optional, and one without is not required either when optional`() {
        val parameters = (preferences / "parameters").arr()

        assertEquals(listOf(false, false), parameters.map { (it / "required").bool() })
        assertEquals("Which language to answer in", (parameters[0] / "description").str())
        assertEquals("string", (parameters[0] / "schema" / "type").str())
    }

    // ---------------------------------------------------------------- forms

    @Test
    fun `a form body is documented under its own media type, with the type's own schema`() {
        val body = signIn / "requestBody"

        assertEquals(setOf("application/x-www-form-urlencoded"), (body / "content").keys())
        assertEquals("The sign-in form", (body / "description").str())
        assertEquals(
            "#/components/schemas/SignIn",
            (body / "content" / "application/x-www-form-urlencoded" / "schema" / "\$ref").str(),
        )
    }

    // ------------------------------------------------------------ multipart

    @Test
    fun `a multipart body is an object with one property per part`() {
        val schema = uploaded / "requestBody" / "content" / "multipart/form-data" / "schema"

        assertEquals("object", (schema / "type").str())
        assertEquals(setOf("caption", "notes", "attachment"), (schema / "properties").keys())
        assertEquals(listOf("caption", "attachment"), (schema / "required").strings())
    }

    @Test
    fun `a file part is the binary property, and a text part carries its own refinement`() {
        val properties = uploaded / "requestBody" / "content" / "multipart/form-data" / "schema" / "properties"

        // 3.1 spells "these are opaque bytes" as contentMediaType rather than
        // 3.0's `format: binary`, and the part's own declared type is what goes
        // there — so a reader is told it is a CSV, not merely that it is a file.
        assertEquals("string", (properties / "attachment" / "type").str())
        assertEquals("text/csv", (properties / "attachment" / "contentMediaType").str())
        assertNull((properties / "attachment").obj()["format"])
        assertEquals("The file itself", (properties / "attachment" / "description").str())

        // The same facet the same refinement would put on a query parameter,
        // so Swagger UI refuses to submit what the server would reject.
        assertEquals(3, (properties / "caption" / "minLength").num())
        assertEquals("What to call it", (properties / "caption" / "description").str())
    }

    @Test
    fun `what a file part expects to carry reaches the encoding block`() {
        val encoding = uploaded / "requestBody" / "content" / "multipart/form-data" / "encoding"

        assertEquals("text/csv", (encoding / "attachment" / "contentType").str())
    }

    @Test
    fun `a multipart body has no parameters section, since its parts are not parameters`() {
        assertNull(uploaded / "parameters")
    }
}
