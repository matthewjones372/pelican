package example

import dev.pelican.ApiSpec
import dev.pelican.openapi.openApiJson
import dev.pelican.openapi.openApiYaml
import example.bookmarks.bookmarksSpec
import example.secured.securedSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
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

    data class Spec(val name: String, val json: String, val yaml: String) {
        override fun toString() = name
    }

    private fun spec(name: String, spec: ApiSpec) = Spec(name, spec.openApiJson(), spec.openApiYaml())

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
        // `jsonSchemaDialect` is deliberately absent — 2020-12 is what 3.1
        // already means, and OpenApi.kt says so where it decides not to write
        // it. Asserted rather than assumed, because a parser that read this
        // document as 3.0 and filled the field in would be a real problem.
        parsed.jsonSchemaDialect.shouldBeNull()
    }

    /**
     * Round-tripping through the parser and back out has to preserve the parts
     * a reader depends on. This is narrower than it sounds: the parser
     * normalises freely, so the check is on the operations and their statuses
     * rather than on byte equality.
     */
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

    /**
     * The YAML is not a second document. It is the same tree, written the
     * other way, so the parser has to read the two into the same object —
     * which is a claim about the renderer that nothing in `pelican-openapi`
     * can make about itself: the emitter that would agree with a wrong quoting
     * rule is the one that wrote it.
     */
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
