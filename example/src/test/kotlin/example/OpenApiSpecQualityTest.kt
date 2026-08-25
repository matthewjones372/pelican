package example

import example.bookmarks.bookmarksSpec
import example.secured.securedSpec
import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.openapi.OpenApiVersion
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.openapi.openApiYaml
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The documents this repository emits, read back by somebody else's parser.
 *
 * Every other test here asks whether the generator produced the JSON *we*
 * expected — which is worth something, and is worth nothing at all if what we
 * expected is not a valid OpenAPI document. swagger-parser is an independent
 * implementation of the specification: it resolves the `$ref`s, checks the
 * shape of every object against 3.1, and reports what it could not make sense
 * of. A generator marking its own homework is the failure mode this is here to
 * rule out.
 *
 * The three specs are the ones the example module publishes, chosen because
 * between them they use nearly everything the generator can emit: streaming
 * responses, declared failures, security schemes, cookies, forms, multipart
 * uploads and hidden endpoints.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiSpecQualityTest {

    data class Spec(val name: String, val json: String, val yaml: String, val json32: String) {
        override fun toString() = name
    }

    private fun spec(name: String, spec: ApiSpec) = Spec(
        name,
        spec.openApiJson(),
        spec.openApiYaml(),
        spec.openApiJson(OpenApiVersion.V3_2_0),
    )

    @Suppress("unused") // @MethodSource
    private fun specs() = listOf(
        spec("orders", ordersSpec()),
        spec("secured-reports", securedSpec()),
        spec("bookmarks", bookmarksSpec()),
    )

    private fun parse(json: String) = OpenAPIV3Parser().readContents(
        json,
        null,
        // Resolve `$ref`s rather than taking them on trust: a reference to a
        // schema the document never defines is exactly the kind of break this
        // test exists to catch, and it only shows up on resolution.
        ParseOptions().apply {
            isResolve = true
            isResolveFully = true
            isValidateExternalRefs = false
        },
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("specs")
    fun `the document parses, with nothing the specification does not allow`(spec: Spec) {
        val result = parse(spec.json)

        withClue("${spec.name}: swagger-parser reported ${result.messages}") {
            result.messages.orEmpty() shouldBe emptyList()
        }
        withClue("${spec.name}: parsed to nothing at all") {
            result.openAPI.shouldNotBeNull()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specs")
    fun `it is 3_1, and the parser agrees rather than falling back`(spec: Spec) {
        val parsed = parse(spec.json).openAPI

        parsed.openapi shouldBe "3.1.0"
        parsed.jsonSchemaDialect.shouldBeNull()
    }

    /**
     * Why 3.1 is still the default, asserted rather than only written down.
     *
     * `pelican-openapi` can write 3.2, and the reference manual argues that
     * 3.2 is the revision in which these documents are actually correct about
     * cookies and streams. It is not the default because the JVM tooling has
     * not arrived: swagger-parser 2.1.47 is the newest release, `swagger-models`
     * knows `SpecVersion.V30` and `V31` and nothing after them, and a 3.2.0
     * document comes back as nothing at all — with an empty `messages` list, so
     * a caller who does not check for null never learns why.
     *
     * When that stops being true this test fails, and the failure is the signal
     * to move the default. That is the whole point of writing it down here: the
     * reason for a default should expire loudly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("specs")
    fun `the 3_2 rendering is still unreadable to swagger-parser, which is why 3_1 is the default`(spec: Spec) {
        val parsed = parse(spec.json32)

        withClue("swagger-parser reads 3.2 now — move the default and delete this test") {
            parsed.openAPI.shouldBeNull()
        }
    }

    /**
     * The 3.2 rendering says the three things 3.1 could not, and the rest of
     * the document is the one the parser above already validated.
     *
     * Whole-document validation of the 3.2 rendering is not available from any
     * parser this build can depend on, so what is checked here is the delta:
     * every difference from the 3.1 document is one of the three the emitter
     * documents, and each of them says what it should.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("specs")
    fun `the 3_2 rendering differs from the 3_1 one only where the emitter says it does`(spec: Spec) {
        val v31 = Json.parseToJsonElement(spec.json).jsonObject
        val v32 = Json.parseToJsonElement(spec.json32).jsonObject

        val moved = differences(v31, v32).map { it.substringAfterLast('/') }.toSet()

        withClue("differences at: ${differences(v31, v32)}") {
            moved shouldBe moved.intersect(setOf("openapi", "schema", "itemSchema", "style"))
        }
        withClue("a 3.2 document that changed nothing would pass the assertion above vacuously") {
            differences(v31, v32) shouldContain "/openapi"
        }
    }

    /** Every path at which two documents disagree, as JSON pointers. */
    private fun differences(left: JsonElement?, right: JsonElement?, at: String = ""): List<String> = when {
        left is JsonObject && right is JsonObject ->
            (left.keys + right.keys).sorted().flatMap { key -> differences(left[key], right[key], "$at/$key") }

        left is JsonArray && right is JsonArray && left.size == right.size ->
            left.indices.flatMap { i -> differences(left[i], right[i], "$at/$i") }

        left == right -> emptyList()

        else -> listOf(at)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specs")
    fun `every path the description declares survives a parse`(spec: Spec) {
        val parsed = parse(spec.json).openAPI

        withClue("${spec.name} declared no paths at all") {
            parsed.paths.shouldNotBeEmpty()
        }
        parsed.paths.forEach { (path, item) ->
            val operations = item.readOperationsMap()
            withClue("$path has no operations after parsing") {
                operations.shouldNotBeEmpty()
            }
            operations.forEach { (method, operation) ->
                withClue("$path $method documents no responses") {
                    operation.responses.shouldNotBeEmpty()
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specs")
    fun `the YAML rendering parses as the same document`(spec: Spec) {
        val fromYaml = parse(spec.yaml)

        withClue("${spec.name}: swagger-parser reported ${fromYaml.messages} for the YAML") {
            fromYaml.messages.orEmpty() shouldBe emptyList()
        }
        fromYaml.openAPI shouldBe parse(spec.json).openAPI
    }

    /**
     * The discriminated union, read by the parser rather than by us.
     *
     * A `mapping` is the part of a hierarchy that a reader cannot reconstruct,
     * and it is also the part that is easiest to write in a way only its author
     * can read: a key pointing at a schema the document does not define
     * resolves to nothing, and swagger-parser is what says so. Every branch is
     * checked against the components, and the values are the ones the Kotlin
     * annotations put on the wire.
     */
    @Test
    fun `a hierarchy is published as a union whose every branch the parser can resolve`() {
        val parsed = parse(ordersSpec().openApiJson()).openAPI
        val union = parsed.components.schemas["PaymentMethod"].shouldNotBeNull()

        union.discriminator.propertyName shouldBe "method"
        union.discriminator.mapping.keys shouldBe setOf("card", "bank_transfer", "invoice")
        withClue("a mapping pointing at a schema the document does not define is a dead reference") {
            union.discriminator.mapping.values.forEach { target ->
                parsed.components.schemas.keys shouldContain target.substringAfterLast('/')
            }
        }
        withClue("resolved fully, so each branch arrives as the schema it referenced") {
            union.oneOf.map { branch -> branch.properties.keys } shouldBe
                listOf(setOf("number", "expiry"), setOf("iban"), setOf("reference"))
        }
    }

    /**
     * The security schemes an endpoint requires have to be schemes the document
     * defines. swagger-parser does not treat a dangling requirement as an
     * error, and Swagger UI renders it as a padlock that cannot be opened, so
     * this one is asked here rather than left to the parser.
     */
    @Test
    fun `every security requirement names a scheme the document defines`() {
        val parsed = parse(securedSpec().openApiJson()).openAPI
        val defined = parsed.components?.securitySchemes?.keys.orEmpty()

        val required = parsed.paths.values
            .flatMap { it.readOperations() }
            .flatMap { it.security.orEmpty() }
            .flatMap { it.keys }
            .toSet()

        required.forEach { scheme -> defined shouldContain scheme }
    }
}
