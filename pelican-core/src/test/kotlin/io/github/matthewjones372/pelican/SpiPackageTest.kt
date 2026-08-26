package io.github.matthewjones372.pelican

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The plumbing lives at its own address, asserted against the compiled classes
 * rather than against the checked-in dump, which no build task hands this test.
 *
 * A backend is a separate module by design, so none of these can be `internal`;
 * the package is what keeps them out of the DSL's autocomplete and out of the
 * compatibility promise the root package makes.
 */
class SpiPackageTest {

    /** Named by hand: the list is the decision, and a derived one could not fail. */
    private val spi = setOf(
        "handlerFor",
        "declaredInputCount",
        "RouteIndex",
        "routeIndex",
        "RenderedError",
        "renderError",
        "ClassifiedError",
        "classifyError",
        "RequestBodyCodecs",
        "requestBodyCodec",
        "readStrictBody",
        "successNamedBy",
        "failureNamedBy",
        "decodeList",
        "acceptable",
        "CorsHeaders",
        // `MultipartBody.decode` moved with it, but `decode` is also every
        // codec's own method name, so the boundary is the name checked.
        "multipartBoundary",
    )

    /** The directory the module's own classes were compiled into. */
    private val compiled = File(Api::class.java.protectionDomain.codeSource.location.toURI())

    /** One package, not its subpackages: `spi` is a directory beside these files. */
    private fun namesIn(pkg: String): Set<String> {
        val dir = compiled.resolve(pkg.replace('.', '/'))
        val classes = dir.listFiles { file: File -> file.isFile && file.extension == "class" }.orEmpty()
        withClue("no compiled classes under $pkg; this test is looking in the wrong place") {
            classes.toList().shouldNotBeEmpty()
        }
        return classes.flatMap { file ->
            // Loaded without initialising: a class whose initialiser refuses to
            // run outside a request is still one whose members are being read.
            val type = Class.forName("$pkg.${file.nameWithoutExtension}", false, javaClass.classLoader)
            type.declaredMethods.map { it.name } + type.simpleName
        }.toSet()
    }

    @Test
    fun `the DSL package declares no SPI name`() {
        val squatting = namesIn("io.github.matthewjones372.pelican") intersect spi
        withClue("these are interpreter plumbing and are published beside the DSL: $squatting") {
            squatting.shouldBeEmpty()
        }
    }

    @Test
    fun `the spi package declares all of them`() {
        val missing = spi - namesIn("io.github.matthewjones372.pelican.spi")
        withClue("named as SPI but not published under .spi: $missing") {
            missing.shouldBeEmpty()
        }
    }
}
