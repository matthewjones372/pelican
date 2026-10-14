package io.github.matthewjones372.pelican.importer

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The document is limited to the shapes `pelican-jetty` has binders for today,
 * `handledNow` and `handledWith`; the rest arrive with spec 0074's other entries.
 */
class JettyStubsTest {

    private val document = documentOf(
        "openapi.yaml" to document(
            """
            /pets/{petId}:
              parameters:
                - { name: petId, in: path, required: true, schema: { type: integer, format: int64 } }
              get:
                operationId: getPet
                responses:
                  "200":
                    description: The pet
                    content:
                      application/json:
                        schema: { type: object, required: [name], properties: { name: { type: string } } }
              delete:
                operationId: deletePet
                responses:
                  "204":
                    description: Gone
            """,
        ),
    )

    private val generated = Import.kotlin(document, importOptions("app", "pets") { handlers = Backend.JETTY })

    @Test
    fun `jetty stubs import the jetty binders`() {
        val stubs = generated.getValue("PetsHandlers.kt")
        stubs shouldContain "import io.github.matthewjones372.pelican.jetty.*"
        stubs shouldContain """getPet handledNow { petId -> TODO("getPet") }"""
        stubs shouldContain """deletePet handledWith { petId -> TODO("deletePet") }"""
    }

    @Test
    fun `jetty stubs compile against pelican-jetty`(@TempDir workspace: File) {
        val sources = generated.map { (name, source) -> File(workspace, name).apply { writeText(source) } }
        val errors = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() = errors.clear()
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError) errors += "${location?.path}:${location?.line}: $message"
            }
        }

        K2JVMCompiler().exec(
            collector,
            Services.EMPTY,
            K2JVMCompilerArguments().apply {
                freeArgs = sources.map { it.absolutePath }
                classpath = System.getProperty("java.class.path")
                destination = File(workspace, "out").absolutePath
                noStdlib = true
                noReflect = true
                // The classpath is built for 25; the compiler's default of 1.8 would fail every file.
                jvmTarget = "25"
            },
        )

        withClue(errors.joinToString("\n")) { errors.shouldBeEmpty() }
    }
}
